import Foundation
import ServeCoachCore

/**
 * TEST VIDEO (MainActivity.onActivityResult request 7): a recorded serve video through the same
 * pipeline as the camera, every serve captured (no turns). It only feeds the pipeline: the captures
 * go to the controller's engine queue as they start, as live ones do.
 */
final class VideoTest {
    let captureDir: URL
    var log: (String, String) -> Void = { _, _ in }        // kind, text
    var state: (String) -> Void = { _ in }                 // on the main thread
    var onCaptureStarted: (Int) -> Void = { _ in }         // on the main thread
    var onCapture: (Int, Int, Int64) -> Void = { _, _, _ in }  // id, frames, write ms
    /** on the main thread: serves spotted, the tracker's rejections; or an error */
    var onFinished: (Int, [String: Int]) -> Void = { _, _ in }
    var onFailed: (String) -> Void = { _ in }
    private(set) var pipeline: Pipeline?
    private var cancelled = false

    init(captureDir: URL) { self.captureDir = captureDir }

    func cancel() { cancelled = true }

    func run(_ url: URL, firstId: Int = 0, prime: Bool = true) {
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
                p.onCaptureStarted = { id, _ in DispatchQueue.main.async { self.onCaptureStarted(id) } }
                p.onCapture = { id, n, _, ms, _, _ in self.log("capture", "#\(id): \(n) frames, written in \(ms) ms"); self.onCapture(id, n, ms) }
                if prime { p.prime(frames) }
                p.start()
                for (i, f) in frames.enumerated() {
                    if cancelled { break }
                    p.offer(f)
                    if (i + 1) % 60 == 0 { let pc = 100 * (i + 1) / max(1, frames.count); DispatchQueue.main.async { if !self.cancelled { self.state("Video test: looking for serves… \(pc)%") } } }
                }
                p.finish()
                p.waitForWorkers()
                let found = p.servesFound
                let rejected = p.rejected
                log("replay", "done: \(found) serve(s) spotted, tracker \(String(format: "%.1f", p.trackMsAvg)) ms a frame")
                log("replay", "tracker turned down: \(rejected)")
                p.stop()
                DispatchQueue.main.async { if !self.cancelled { self.onFinished(found, rejected) } }
            } catch {
                log("error", "replay: \(error.localizedDescription)")
                DispatchQueue.main.async { self.onFailed(error.localizedDescription) }
            }
        }
    }
}
