import UIKit
import AVFoundation
import UniformTypeIdentifiers
import ServeCoachCore

/**
 * MainActivity.kt: the one screen and what drives it. Modes IDLE (preview and setup check), LIVE
 * (a serving session, turn by turn) and REPLAY (a video test). Answers from the engine come back
 * here, are shown and spoken, and (live) end the turn.
 */
final class CoachViewController: UIViewController, AVSpeechSynthesizerDelegate, UIDocumentPickerDelegate {
    enum Mode { case idle, live, replay }

    let screen = HudScreen()
    private(set) var mode = Mode.idle
    private var engine: Engine!
    let capDir = FileManager.default.temporaryDirectory.appendingPathComponent("captures")
    private var engineReady = false
    private var engineBusy = false
    private var waiting: [Int] = []
    private var unclear = 0
    private var measuring: Int?
    private var started: Int64 = 0
    private var test: VideoTest?
    private var testSpotted = 0, testRead = 0, testGood = 0
    private var statsTimer: Timer?
    let report = Report()
    lazy var live = LiveCoach(self)

    private let tips = TipPicker()
    private let noReads = NoRead.Advisor(every: 4)
    private var turnId = 0
    private var latestSpotted = 0
    private var spottedAt: [Int: Int64] = [:]
    private var captureFrames: [Int: Int] = [:]
    private var writeMsOf: [Int: Int64] = [:]

    private let tts = AVSpeechSynthesizer()
    private var readyUtterance: AVSpeechUtterance?
    private let chimer = Chime()

    private let prefs = UserDefaults.standard
    var windowRingOn: Bool { prefs.bool(forKey: "windowRing") }
    var shareClipsOn: Bool { prefs.bool(forKey: "shareClips") }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .landscapeRight }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .bottom }

    override func loadView() { view = screen }

    override func viewDidLoad() {
        super.viewDidLoad()
        UIApplication.shared.isIdleTimerDisabled = true
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
        tts.delegate = self
        try? FileManager.default.removeItem(at: capDir)
        try? FileManager.default.createDirectory(at: capDir, withIntermediateDirectories: true)
        Sessions.prune()

        engine = Engine(captureDir: capDir)
        screen.addSubview(engine.web)
        engine.web.frame = CGRect(x: 0, y: screen.bounds.height - 2, width: 2, height: 2)
        engine.web.autoresizingMask = [.flexibleTopMargin]
        engine.onReady = { [weak self] codecs, mp4, eng in
            guard let self else { return }
            self.report.engine = ["webCodecs": codecs, "mp4box": mp4, "engine": eng]
            self.log("load", "engine: webcodecs \(codecs), mp4box \(mp4), engine \(eng)")
            if !eng { self.log("error", "the engine didn't load") }
            self.engineReady = eng
            self.next()
        }
        engine.onProgress = { [weak self] id, _, frac in
            guard let self, self.measuring == id, self.mode != .replay || true else { return }
            let pc = Int(frac * 100)
            self.state(pc > 0 ? "Measuring serve #\(id)… \(pc)%" : "Measuring serve #\(id)…")
        }
        engine.onResult = { [weak self] id, json in self?.answer(id, json) }

        screen.onStart = { [weak self] in guard let self else { return }; if self.mode == .idle { self.live.start() } else { self.stopAll() } }
        screen.onTest = { [weak self] in self?.pickVideo() }
        screen.onReport = { [weak self] in self?.copyReport() }
        screen.onAi = { [weak self] in self?.live.toggleAi() }
        screen.onWindows = { [weak self] in guard let self else { return }
            self.prefs.set(!self.windowRingOn, forKey: "windowRing"); self.screen.windowsState(self.windowRingOn)
            self.log("camera", "windows cut on the chip: " + (self.windowRingOn ? "on" : "off")) }
        screen.onShareClips = { [weak self] in guard let self else { return }
            self.prefs.set(!self.shareClipsOn, forKey: "shareClips"); self.screen.shareState(self.shareClipsOn) }
        screen.aiState(live.aiOn); screen.windowsState(windowRingOn); screen.shareState(shareClipsOn)

        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in self?.updateStats() }
        updateStats()
        NotificationCenter.default.addObserver(self, selector: #selector(paused), name: UIApplication.willResignActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(resumed), name: UIApplication.didBecomeActiveNotification, object: nil)

        let args = ProcessInfo.processInfo.arguments
        Pipeline.dumpQ = args.contains("-dumpq")
        if args.contains("-selftest") { _ = SelfTest.run() }
        if let i = args.firstIndex(of: "-replay"), i + 1 < args.count {
            // a bare name is a file in the app's Documents (copied there from the Mac)
            let a = args[i + 1]
            let url = a.hasPrefix("/") ? URL(fileURLWithPath: a) : FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(a)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.startVideoTest(url, prime: !args.contains("-noprime")) }
        } else {
            live.checkCamera()
            // testing on a phone nobody is holding: -live <seconds> presses START after 4 s and STOP after that long
            if let i = args.firstIndex(of: "-live"), i + 1 < args.count, let secs = Double(args[i + 1]) {
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    guard self.mode == .idle else { return }
                    self.live.start()
                    DispatchQueue.main.asyncAfter(deadline: .now() + secs) { if self.mode == .live { self.stopAll() } }
                }
            }
        }
    }

    @objc private func paused() {
        live.paused()
        if mode != .idle { stopAll() }
    }
    @objc private func resumed() { if mode == .idle { live.resumed() } }

    // ------------------------------------------------------------------ screen helpers
    func state(_ s: String) { screen.state(s); print("SCLOG state: \(s)") }
    func tone(_ c: UIColor) { screen.tone(c) }

    private var statsLogged: Int64 = 0
    private func updateStats() {
        if mode == .live, nowMs() - statsLogged > 5000, let p = live.pipeline {
            statsLogged = nowMs()
            print("SCLOG stats: recording \(Int(live.recordingFps.rounded())) fps, decoded \(p.decoded), gaps \(p.cameraGaps), skipped \(p.skipped), \(String(format: "%.1f", p.trackMsAvg)) ms a frame, \(live.lagMs) ms behind, camera dropped \(live.camera.dropped)")
        }
        var s = "App \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")\n"
        if let c = live.cfg { s += "Camera \(c.width)×\(c.height) @ \(c.fps) fps\n" }
        if let p = live.pipeline {
            s += "Recording \(Int(live.recordingFps.rounded())) frames/s\n"
            let missed = p.dropped + p.skipped
            s += "Watching: \(String(format: "%.1f", p.trackMsAvg)) ms a frame, \(live.lagMs) ms behind" + (missed > 0 ? ", \(missed) frames missed" : "") + "\n"
        }
        s += "Engine \(engineReady ? "ready" : "loading")" + (waiting.isEmpty ? "" : " · \(waiting.count) waiting") +
             (unclear > 0 ? " · \(unclear) movements weren't readable serves" : "")
        screen.stats(s)
    }

    // ------------------------------------------------------------------ the engine queue
    /** a capture has started being written: it can be measured while it is still being cut */
    func captureStarted(_ id: Int) {
        let limit = mode == .live ? 12 : 30
        waiting.append(id)
        while waiting.count > limit { let old = waiting.removeFirst(); log("error", "#\(old): dropped, too many waiting"); drop(old) }
        next()
    }
    func captured(_ id: Int, frames: Int, writeMs: Int64) { captureFrames[id] = frames; writeMsOf[id] = writeMs }
    private func drop(_ id: Int) {
        Pipeline.writing.remove(id)
        try? FileManager.default.removeItem(at: capDir.appendingPathComponent("cap_\(id).bin"))
        try? FileManager.default.removeItem(at: capDir.appendingPathComponent("cap_\(id).json"))
    }
    var pendingCount: Int { waiting.count + (engineBusy ? 1 : 0) }

    private func next() {
        guard engineReady, !engineBusy, !waiting.isEmpty else { return }
        let id = waiting.removeFirst()
        engineBusy = true; measuring = id; started = nowMs()
        state("Measuring serve #\(id)…")
        engine.analyseCapture(id)
    }

    private func answer(_ id: Int, _ json: String) {
        engineBusy = false; measuring = nil
        print("SCLOG result: #\(id) \(json)")
        var r = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        let data = r.removeValue(forKey: "data")
        r["capture"] = id; r["wallMs"] = nowMs() - started
        if let n = captureFrames.removeValue(forKey: id) { r["captureFrames"] = n }
        if let w = writeMsOf.removeValue(forKey: id) { r["writeMs"] = w }
        if let s = spottedAt.removeValue(forKey: id) { r["answerMs"] = nowMs() - s }
        if let d = try? Data(contentsOf: capDir.appendingPathComponent("cap_\(id).json")),
           let head = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any],
           let t = head["track"] as? [[Double]], let W = head["W"] as? Int {
            let c = NoRead.shared.crossed(xs: t.map { KotlinDouble(value: $0[1]) }, w: Int32(W))
            if c.isFinite { r["crossed"] = c }
        }
        if mode != .replay { report.results.append(r); live.saveServeData(id, r, data) }
        drop(id)
        show(r)
        if screen.source != "SERVE" { testRead += 1; if (r["serve"] as? [String: Any])?["clear"] as? Bool == true { testGood += 1 }; finishTest() }
        if mode == .live { live.writeReport() }
        next()
    }

    // ------------------------------------------------------------------ what to say (MainActivity.show)
    private func show(_ r: [String: Any]) {
        let id = r["capture"] as? Int ?? 0
        let sv = r["serve"] as? [String: Any]
        if r["net"] as? Bool == true {
            unclear += 1; log("unclear", "#\(id): into the net")
            noReads.read()
            screen.noRead(id, "Couldn't read it: Into the net")
            if mode == .live { turnDone("Into the net.", id) }
            return
        }
        guard let s = sv, s["clear"] as? Bool == true else {
            unclear += 1
            let err = r["error"] as? String
            let issues = (sv?["issues"] as? [Any])?.map { "\($0)" } ?? []
            let why = (err?.isEmpty == false ? err! : nil) ?? issues.first ?? "no clear reading"
            var cause: NoRead.Reason?
            if let s = sv {
                cause = NoRead.shared.of(marking: s["marking"] as? String, toward: (s["toward"] as? Double) ?? .nan, issues: issues,
                                         error: err, crossed: (r["crossed"] as? Double) ?? .nan)
            }
            let p = noReads.next(r: cause)
            let spoken = p.first as! String, shown = p.second as! String
            log("unclear", "#\(id): \(why)" + (cause.map { " -> \($0.cause)" } ?? ""))
            screen.noRead(id, cause != nil ? shown : "Couldn't read it: \(why)")
            if mode == .live { turnDone(spoken, id) }
            return
        }
        let phases = s["phases"] as? [[String: Any]] ?? []
        let own = phases.contains { $0["clear"] as? Bool == true && ($0["phase"] as? String) != "bounce" }
        let rps = (s["rps"] as? Double) ?? 0
        if !own && rps < 10 {
            unclear += 1
            log("unclear", "#\(id): only read after a bounce (\(Int((rps * 60).rounded())) RPM \(s["type"] as? String ?? "")): not counted as a serve")
            if mode == .live { turnDone(noReads.next(r: nil).first as! String, id) }
            return
        }
        let type = s["type"] as? String ?? ""
        let kind = (r["coach"] as? [String: Any])?["kind"] as? String ?? ""
        let ballPx = (s["ballPx"] as? Double) ?? 0
        let closer = (1.0...24.0).contains(ballPx) ? "The ball is only \(Int(ballPx.rounded(.toNearestOrAwayFromZero))) px across: move the phone closer." : ""
        let tip = tips.next(kind)
        noReads.read()
        let rpm = SpinLevel.rpm(rps)
        let spin: Spin? = s["lift"] != nil ? Spin(lift: (s["lift"] as? Double) ?? 0, curve: (s["curve"] as? Double) ?? 0, gyro: (s["gyro"] as? Double) ?? 0,
                                                 dirKnown: s["gyroDirKnown"] as? Bool ?? false, afterBounce: (s["phase"] as? String) == "bounce") : nil
        let kmh = ((s["speed"] as? Double) ?? 0) > 0.1 ? Int(((s["speed"] as! Double) * 3.6).rounded(.toNearestOrAwayFromZero)) : nil
        screen.reading(id, rpm: rpm, type: type, coach: closer.isEmpty ? (tip.map { "TIP  \($0.0). \($0.1)" } ?? "") : closer, spin: spin, kmh: kmh)
        turnDone("\(SpinLevel.outcome(rpm, type)), \(rpm)." + (tip.map { " Tip: \($0.1)" } ?? ""), id)
    }

    // ------------------------------------------------------------------ turn by turn (live)
    func setReady(_ spoken: String?) {
        live.pipeline?.resume()
        turnId = 0
        tone(Hud.CYAN)
        state("Ready: serve")
        if mode == .live { screen.readyCue() }
        if let s = spoken { say(s, queue: true, ready: true) } else if mode == .live { chimer.play() }
    }
    func onSpotted(_ id: Int) {
        guard mode == .live else { return }
        turnId = id; latestSpotted = id
        spottedAt[id] = nowMs()
        tone(Hud.AMBER)
        state("Got it: measuring serve #\(id)…")
        screen.busy(true)
        say("Got it.")
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, self.mode == .live, self.turnId == id else { return }
            self.log("error", "#\(id): no answer in 30 s"); self.setReady("Ready.")
        }
    }
    private func turnDone(_ spoken: String, _ id: Int) {
        screen.busy(false)
        if mode == .live && id == turnId { setReady("\(spoken) Ready.") }
        else if mode != .live || id >= latestSpotted { say(spoken, queue: true) }
    }
    /** a new live session (MainActivity.startLive's resets) */
    func sessionStarted() {
        tips.reset(); noReads.reset(); latestSpotted = 0; turnId = 0
        screen.source = "SERVE"
        mode = .live
        screen.running(true)
        tone(Hud.CYAN)
    }

    // ------------------------------------------------------------------ voice
    /** queue false: stop what is being said and say this; queue true: after it. ready: the chime follows */
    func say(_ text: String, queue: Bool = false, ready: Bool = false) {
        if !queue { tts.stopSpeaking(at: .immediate) }
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: "en-US")
        u.rate = 0.5
        u.pitchMultiplier = 1.0
        if ready { readyUtterance = u }
        tts.speak(u)
    }
    func stopSpeaking() { tts.stopSpeaking(at: .immediate) }
    func speechSynthesizer(_ s: AVSpeechSynthesizer, didFinish u: AVSpeechUtterance) {
        if u === readyUtterance { readyUtterance = nil; if mode == .live && turnId == 0 { chimer.play() } }
    }

    // ------------------------------------------------------------------ stop
    func stopAll() {
        let wasLive = mode == .live
        test?.cancel(); test = nil
        live.stop()
        mode = .idle
        turnId = 0
        screen.running(false)
        tone(Hud.CYAN)
        state("Stopped")
        if wasLive { live.finishSession() }
        live.startPreview()
    }

    // ------------------------------------------------------------------ video test
    private func pickVideo() {
        guard mode == .idle else { return }
        let p = UIDocumentPickerViewController(forOpeningContentTypes: [.movie, .video, .mpeg4Movie], asCopy: true)
        p.delegate = self
        present(p, animated: true)
    }
    func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first, mode == .idle else { return }
        startVideoTest(u)
    }

    func startVideoTest(_ url: URL, prime: Bool = true) {
        live.stopPreview()
        mode = .replay
        screen.running(true)
        screen.testStarted(url.lastPathComponent.isEmpty ? "the video" : url.lastPathComponent)
        tone(Hud.AMBER)
        testSpotted = -1; testRead = 0; testGood = 0
        let t = VideoTest(captureDir: capDir)
        test = t
        t.log = { [weak self] k, s in DispatchQueue.main.async { self?.log(k, s) } }
        t.state = { [weak self] s in guard let self, self.mode == .replay else { return }; self.state(s) }
        t.onCaptureStarted = { [weak self] id in self?.captureStarted(id) }
        t.onCapture = { [weak self] id, n, ms in DispatchQueue.main.async { self?.captured(id, frames: n, writeMs: ms) } }
        t.onFailed = { [weak self] e in guard let self else { return }; self.stopAll(); self.tone(Hud.RED); self.state("Video test failed: \(e)") }
        t.onFinished = { [weak self] found, rejected in
            guard let self, self.mode == .replay else { return }
            self.test = nil
            self.stopAll()
            self.testSpotted = found
            if found == 0 {
                self.screen.testFoundNone(Words.whyNoServe(rejected)); self.screen.source = "SERVE"
                self.tone(Hud.CYAN); self.state("Video test done: no serve found")
                self.say("No serve found in this video.")
                print("SCLOG done: spotted 0")
            } else {
                self.tone(Hud.AMBER); self.state("Video test: measuring \(found) serve(s)…")
                self.finishTest()
            }
        }
        t.run(url, firstId: Sessions.firstFreeCaptureId(), prime: prime)
    }
    private func finishTest() {
        guard testSpotted > 0, testRead >= testSpotted else { return }
        tone(Hud.CYAN); state("Video test done: \(testGood) of \(testSpotted) serve(s) read")
        screen.source = "SERVE"
        print("SCLOG done: spotted \(testSpotted), read \(testRead), clear \(testGood)")
        testSpotted = 0
    }

    // ------------------------------------------------------------------ report and errors
    func log(_ kind: String, _ text: String) {
        report.event(kind, text)
        print("SCLOG \(kind): \(text)")
        guard kind == "error" else { return }
        if screen.devMode { toast(text, long: true) }
        if let line = playerLine(text) { screen.problem(line) }
    }
    /** MainActivity.playerLine: an error the player can act on, in their words */
    private func playerLine(_ e: String) -> String? {
        let again = mode == .idle ? "Press Start to try again." : "Press Stop, then Start."
        let l = e.lowercased()
        if ["camera error", "the camera refused", "request:", "session:"].contains(where: { l.hasPrefix($0) }) { return "The camera stopped. Press Start to try again." }
        if l.hasPrefix("no frames from the camera") { return "No picture from the camera. \(again)" }
        if l.hasPrefix("encoder") { return "Recording failed. \(again)" }
        if l.hasPrefix("decoder:") || l.hasPrefix("tracker:") { return "Serve watching stopped. \(again)" }
        if l.hasPrefix("the engine didn't load") { return "The spin measurer didn't load. Close Serve Coach and open it again." }
        if l.hasPrefix("picture check failed") { return "Picture check failed: readings may be off. Watching anyway." }
        if l.hasPrefix("setup check:") { return "Setup check is off. Line up the table by eye." }
        if l.range(of: #"^#\d+: no answer in 30 s"#, options: .regularExpression) != nil { return "That serve wasn't measured. Serve again." }
        if l.hasPrefix("save ") { return "Couldn't save a clip. Is the phone's storage full?" }
        return nil
    }

    private func copyReport() {
        UIPasteboard.general.string = report.text(allSessions: true)
        toast("Report copied (it is also in the session's folder)", long: true)
    }

    func toast(_ text: String, long: Bool = false) {
        let l = PaddedLabel()
        l.text = text; l.numberOfLines = 0; l.font = Hud.mono(13); l.textColor = .white; l.textAlignment = .center
        l.backgroundColor = UIColor(white: 0.15, alpha: 0.92); l.layer.cornerRadius = 16; l.clipsToBounds = true
        let size = l.sizeThatFits(CGSize(width: min(560, view.bounds.width - 80), height: 400))
        l.frame = CGRect(x: (view.bounds.width - size.width) / 2, y: view.bounds.height - size.height - 40, width: size.width, height: size.height)
        view.addSubview(l)
        UIView.animate(withDuration: 0.3, delay: long ? 3.5 : 2, options: [], animations: { l.alpha = 0 }) { _ in l.removeFromSuperview() }
    }
}

final class PaddedLabel: UILabel {
    let pad = UIEdgeInsets(top: 10, left: 18, bottom: 10, right: 18)
    override func drawText(in rect: CGRect) { super.drawText(in: rect.inset(by: pad)) }
    override func sizeThatFits(_ s: CGSize) -> CGSize {
        let r = super.sizeThatFits(CGSize(width: s.width - pad.left - pad.right, height: s.height))
        return CGSize(width: r.width + pad.left + pad.right, height: r.height + pad.top + pad.bottom)
    }
}

/**
 * The ready chime (MainActivity.chime): ToneGenerator TONE_PROP_BEEP2 for 220 ms at volume 90.
 * TONE_PROP_BEEP2 is a 400 + 1200 Hz double beep (100 ms on, 100 off, 100 on), cut at 220 ms.
 */
final class Chime {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private var buffer: AVAudioPCMBuffer?
    init() {
        let rate = 44100.0
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1) else { return }
        let n = AVAudioFrameCount(rate * 0.22)
        guard let b = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: n) else { return }
        b.frameLength = n
        let p = b.floatChannelData![0]
        let fade = rate * 0.005
        for i in 0..<Int(n) {
            let t = Double(i) / rate
            let seg = t < 0.1 ? (0.0, 0.1) : (t >= 0.2 ? (0.2, 0.22) : nil)
            guard let (s0, s1) = seg else { p[i] = 0; continue }
            let env = min(1, (t - s0) * rate / fade, (s1 - t) * rate / fade)
            p[i] = Float(0.9 * 0.5 * (sin(2 * .pi * 400 * t) + sin(2 * .pi * 1200 * t)) * max(0, env))
        }
        buffer = b
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: fmt)
    }
    func play() {
        guard let b = buffer else { return }
        if !engine.isRunning { try? engine.start() }
        node.scheduleBuffer(b, at: nil, options: [.interrupts])
        node.play()
    }
}
