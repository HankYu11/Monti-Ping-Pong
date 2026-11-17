import Foundation
import ServeCoachCore

/**
 * TEST VIDEO (MainActivity.onActivityResult request 7): a recorded serve video through the same
 * pipeline and engine as the camera, every serve captured (no turns), the engine measuring while
 * the video is still being read.
 */
final class VideoTest {
    let engine: Engine
    let captureDir: URL
    var log: (String, String) -> Void = { _, _ in }      // kind, text
    var state: (String) -> Void = { _ in }
    var onServeResult: (Int, [String: Any]) -> Void = { _, _ in }
    var onDone: (Int, Int, Int) -> Void = { _, _, _ in }  // spotted, read, clear

    private var waiting: [Int] = []
    private var engineBusy = false
    var engineReady = false
    private var spotted = -1, read = 0, good = 0
    private(set) var pipeline: Pipeline?

    init(engine: Engine, captureDir: URL) { self.engine = engine; self.captureDir = captureDir }

    func run(_ url: URL, firstId: Int = 0, prime: Bool = true) {
        spotted = -1; read = 0; good = 0
        state("Video test: reading the video…")
        Thread.detachNewThread { [self] in
            do {
                let t0 = nowMs()
                let ff = try FileFrames.load(url)
                let frames = ff.store.all
                log("replay", "\(frames.count) frames, \(ff.width)x\(ff.height), filmed at \(ff.captureFps) fps (\(nowMs() - t0) ms to read)")
                let p = Pipeline(store: ff.store, blocking: true, captureFps: ff.captureFps, captureDir: captureDir, firstId: firstId)
                pipeline = p
                p.onError = { e in self.log("error", e) }
                p.onNote = { e in self.log("note", e) }
                p.onCaptureStarted = { id, _ in DispatchQueue.main.async { self.waiting.append(id); self.next() } }
                p.onCapture = { id, n, _, ms, _, _ in self.log("capture", "#\(id): \(n) frames, written in \(ms) ms") }
                if prime { p.prime(frames) }
                p.start()
                for (i, f) in frames.enumerated() {
                    p.offer(f)
                    if i % 60 == 0 { let pc = i * 100 / max(1, frames.count); DispatchQueue.main.async { self.state("Video test: looking for serves… \(pc)%") } }
                }
                p.finish()
                p.waitForWorkers()
                let found = p.servesFound
                log("replay", "done: \(found) serve(s) spotted, tracker \(String(format: "%.1f", p.trackMsAvg)) ms a frame")
                log("replay", "tracker turned down: \(p.rejected)")
                p.stop()
                DispatchQueue.main.async {
                    self.spotted = found
                    if found == 0 { self.state("Video test done: no serve found"); self.onDone(0, 0, 0) }
                    else { self.state("Video test: measuring \(found) serve(s)…"); self.finishTest() }
                }
            } catch {
                DispatchQueue.main.async { self.state("Video test failed: \(error.localizedDescription)") }
                log("error", "replay: \(error.localizedDescription)")
            }
        }
    }

    func next() {
        guard engineReady, !engineBusy, !waiting.isEmpty else { return }
        let id = waiting.removeFirst()
        engineBusy = true
        state("Measuring serve #\(id)…")
        engine.analyseCapture(id)
    }

    func result(_ id: Int, _ json: String) {
        engineBusy = false
        Pipeline.writing.remove(id)
        try? FileManager.default.removeItem(at: captureDir.appendingPathComponent("cap_\(id).bin"))
        try? FileManager.default.removeItem(at: captureDir.appendingPathComponent("cap_\(id).json"))
        let r = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] ?? [:]
        read += 1
        if let s = r["serve"] as? [String: Any], s["clear"] as? Bool == true { good += 1 }
        onServeResult(id, r)
        finishTest()
        next()
    }

    private func finishTest() {
        if spotted > 0 && read >= spotted {
            state("Video test done: \(good) of \(spotted) serve(s) read")
            onDone(spotted, read, good)
        }
    }
}
