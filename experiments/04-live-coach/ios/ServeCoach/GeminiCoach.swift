import Foundation
import AVFoundation

/**
 * GeminiCoach.kt: the setup assistant, a Gemini Live session over a WebSocket. It sees about one
 * picture a second while the phone is set up, is asked for one change when the on-device advice
 * changes, and its spoken answer (24 kHz PCM) is played. The key is the player's own.
 */
final class GeminiCoach: NSObject, URLSessionWebSocketDelegate {
    static let INSTRUCTIONS = """
You help a table tennis player set up a phone that films their serves at 240 frames a second, to measure the ball's spin. You see what the phone's camera sees, about once a second.
A good setup: the phone in landscape, still (a tripod), level, beside the table, facing its side, roughly level with the net, 1 to 1.5 metres from where the ball flies. The server's half of the table low in the picture, with room above it for the toss; the ball must fly across the picture, not toward the camera. Even, bright light; nobody walking between the phone and the table; a plain background (a wall) behind the ball's path helps. The dot-marked ball reads best: a half-black ball can keep its black side to the camera, and then its spin can't be seen. Once the framing is good, remind the player once of the dot-marked ball, bright light and a plain background.
Speak in English, calmly, one short instruction at a time (at most 12 words), the most important first: for example "Move the phone back a little" or "Tilt it down so the whole table shows". When the setup is good, say "Looks good, press Start." Talk about nothing else.
"""
    private let endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private let models = ["gemini-3.8-live", "gemini-3.1-flash-live-preview"]
    var onText: (String) -> Void = { _ in }
    var onStatus: (String) -> Void = { _ in }
    private var task: URLSessionWebSocketTask?
    private var urlSession: URLSession?
    private var key = ""
    private(set) var ready = false
    private(set) var speaking = false
    private var closed = true
    private var modelIndex = 0
    private var transcripts = true
    private var lastAsk: Int64 = 0
    private var ping: Timer?
    private let player = PcmPlayer()

    func start(key: String) {
        self.key = key; closed = false; modelIndex = 0; transcripts = true
        connect()
    }
    func stop() {
        closed = true; ready = false; speaking = false
        task?.cancel(with: .normalClosure, reason: "setup done".data(using: .utf8))
        task = nil; ping?.invalidate(); ping = nil
        player.stop()
    }

    private func connect() {
        ready = false
        guard let url = URL(string: "\(endpoint)?key=\(key)") else { return }
        let s = URLSession(configuration: .default, delegate: self, delegateQueue: OperationQueue())
        urlSession = s
        let t = s.webSocketTask(with: url)
        task = t
        t.resume()
        receive(t)
        DispatchQueue.main.async {
            self.ping?.invalidate()
            self.ping = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in self?.task?.sendPing { _ in } }
        }
    }

    func urlSession(_ s: URLSession, webSocketTask t: URLSessionWebSocketTask, didOpenWithProtocol p: String?) {
        send(t, setupMessage())
    }
    func urlSession(_ s: URLSession, webSocketTask t: URLSessionWebSocketTask, didCloseWith code: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        closedWith(t, code.rawValue, reason.flatMap { String(data: $0, encoding: .utf8) } ?? "")
    }
    func urlSession(_ s: URLSession, task t: URLSessionTask, didCompleteWithError e: Error?) {
        if let e, let ws = t as? URLSessionWebSocketTask { closedWith(ws, ws.closeCode.rawValue, ws.closeReason.flatMap { String(data: $0, encoding: .utf8) } ?? e.localizedDescription) }
    }

    private func closedWith(_ t: URLSessionWebSocketTask, _ code: Int, _ reason: String) {
        guard t === task else { return }
        let wasReady = ready; ready = false; task = nil; speaking = false
        if closed { return }
        if !wasReady {
            if reason.range(of: "model", options: .caseInsensitive) != nil && modelIndex + 1 < models.count {
                modelIndex += 1; onStatus("AI coach: trying \(models[modelIndex])"); connect(); return
            }
            if transcripts && ["transcription", "unknown", "invalid"].contains(where: { reason.range(of: $0, options: .caseInsensitive) != nil }) {
                transcripts = false; connect(); return
            }
        }
        onStatus(code == 1008 || reason.range(of: "API key", options: .caseInsensitive) != nil ? "AI coach: the API key was refused" : "AI coach stopped: \(reason)")
    }

    private func setupMessage() -> [String: Any] {
        var setup: [String: Any] = ["model": "models/\(models[modelIndex])", "responseModalities": ["AUDIO"],
                                    "systemInstruction": ["parts": [["text": GeminiCoach.INSTRUCTIONS]]]]
        if transcripts { setup["outputAudioTranscription"] = [String: Any]() }
        return ["setup": setup]
    }

    private func send(_ t: URLSessionWebSocketTask?, _ m: [String: Any]) {
        guard let t, let d = try? JSONSerialization.data(withJSONObject: m), let s = String(data: d, encoding: .utf8) else { return }
        t.send(.string(s)) { _ in }
    }

    /** the camera's view, about once a second */
    func sendFrame(_ jpeg: Data) {
        guard ready else { return }
        send(task, ["realtimeInput": ["video": ["data": jpeg.base64EncodedString(), "mimeType": "image/jpeg"]]])
    }

    /** the next instruction, with what the phone's own check sees: not while speaking, nor more than every 5 s */
    func ask(_ onDevice: String) {
        let now = nowMs()
        guard ready, !speaking, now - lastAsk >= 5000 else { return }
        lastAsk = now
        send(task, ["realtimeInput": ["text": "The phone's own check says: \"\(onDevice)\". Look at the latest picture: what one change should I make now?"]])
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] r in
            guard let self, t === self.task else { return }
            switch r {
            case .success(.string(let s)): self.handle(s)
            case .success(.data(let d)): self.handle(String(decoding: d, as: UTF8.self))
            case .failure: return
            @unknown default: break
            }
            self.receive(t)
        }
    }

    func handle(_ text: String) {
        guard let m = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { return }
        if m["setupComplete"] != nil { ready = true; onStatus("AI coach on (\(models[modelIndex]))"); return }
        if m["goAway"] != nil { onStatus("AI coach: the session is ending") }
        guard let sc = m["serverContent"] as? [String: Any] else { return }
        if let parts = (sc["modelTurn"] as? [String: Any])?["parts"] as? [[String: Any]] {
            for p in parts {
                if let b64 = (p["inlineData"] as? [String: Any])?["data"] as? String, !b64.isEmpty, let d = Data(base64Encoded: b64) { speaking = true; player.play(d) }
                if let t = p["text"] as? String, !t.isEmpty { onText(t) }
            }
        }
        if let t = (sc["outputTranscription"] as? [String: Any])?["text"] as? String, !t.isEmpty { onText(t) }
        if sc["turnComplete"] as? Bool == true || sc["interrupted"] as? Bool == true { speaking = false; onText("\n") }
    }
}

/** the assistant's voice: 16-bit mono PCM at 24 kHz, played as it streams in */
final class PcmPlayer {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private var started = false
    private let lock = NSLock()

    func play(_ pcm: Data) {
        lock.lock(); defer { lock.unlock() }
        if !started {
            engine.attach(node); engine.connect(node, to: engine.mainMixerNode, format: format)
            started = true
        }
        if !engine.isRunning { try? engine.start() }
        let n = pcm.count / 2
        guard n > 0, let b = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)) else { return }
        b.frameLength = AVAudioFrameCount(n)
        let out = b.floatChannelData![0]
        pcm.withUnsafeBytes { raw in
            let s = raw.bindMemory(to: Int16.self)
            for i in 0..<n { out[i] = Float(Int16(littleEndian: s[i])) / 32768 }
        }
        node.scheduleBuffer(b, completionHandler: nil)
        if !node.isPlaying { node.play() }
    }
    func stop() { lock.lock(); node.stop(); if engine.isRunning { engine.stop() }; lock.unlock() }
}
