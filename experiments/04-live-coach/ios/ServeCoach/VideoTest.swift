import Foundation
import AVFoundation
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

    /** [n] frames spread evenly over the clip as bi-planar buffers (for the table's pose) */
    static func spreadFrames(_ url: URL, _ n: Int, _ each: (CVPixelBuffer) -> Void) {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { return }
        let dur = CMTimeGetSeconds(asset.duration)
        for k in 0..<n {
            guard let r = try? AVAssetReader(asset: asset) else { return }
            let out = AVAssetReaderTrackOutput(track: track, outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange])
            r.add(out)
            r.timeRange = CMTimeRange(start: CMTime(seconds: dur * Double(k) / Double(n), preferredTimescale: 600), duration: CMTime(seconds: 0.05, preferredTimescale: 600))
            guard r.startReading(), let sb = out.copyNextSampleBuffer(), let pb = CMSampleBufferGetImageBuffer(sb) else { continue }
            each(pb); r.cancelReading()
        }
    }

    func run(_ url: URL, firstId: Int = 0, prime: Bool = true) {
        state("Video test: reading the video…")
        Thread.detachNewThread { [self] in
            do {
                let t0 = nowMs()
                let ff = try FileFrames.load(url)
                let frames = ff.store.all
                log("replay", "\(frames.count) frames, \(ff.width)x\(ff.height), filmed at \(ff.captureFps) fps (\(nowMs() - t0) ms to read)")
                let p = Pipeline(store: ff.store, blocking: true, captureFps: ff.captureFps, captureDir: captureDir, firstId: firstId)
                p.pose = PoseWatcher(fps: ff.captureFps)
                pipeline = p
                p.onError = { e in self.log("error", e) }
                p.onNote = { e in self.log("note", e) }
                p.onCaptureStarted = { id, _ in DispatchQueue.main.async { self.onCaptureStarted(id) } }
                p.onCapture = { id, n, _, ms, _, _ in self.log("capture", "#\(id): \(n) frames, written in \(ms) ms"); self.onCapture(id, n, ms) }
                // -posecheck <pitch>: the table's pose from 8 frames spread over the clip, through the
                // same IosTablePose path the live setup check takes, at that camera pitch (clips carry
                // no gravity), then into every capture header as live captures carry it
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-posecheck"), i + 1 < args.count, let pitch = Double(args[i + 1]) {
                    let kit = IosTablePose(keep: 8)
                    VideoTest.spreadFrames(url, 8) { pb in
                        CVPixelBufferLockBaseAddress(pb, .readOnly); defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
                        guard let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0), let c = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return }
                        kit.add(width: Int32(CVPixelBufferGetWidth(pb)), height: Int32(CVPixelBufferGetHeight(pb)), y: y,
                                yRowStride: Int32(CVPixelBufferGetBytesPerRowOfPlane(pb, 0)), cbcr: c, cRowStride: Int32(CVPixelBufferGetBytesPerRowOfPlane(pb, 1)))
                    }
                    let r = pitch * .pi / 180
                    let look = kit.look(frames: kit.snapshot(), dx: 0, dy: cos(r), dz: -sin(r), hfovDeg: 70.72)
                    log("pose", "\(kit.count) frames at pitch \(pitch): \(look.description())")
                    let dn = KotlinDoubleArray(size: 3); dn.set(index: 1, value: cos(r)); dn.set(index: 2, value: -sin(r))
                    let head = kit.header(down: dn, pose: look.pose, poseDown: dn)
                    log("pose", "header fields \(head)")
                    p.optics = { head }
                }
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
                if let pose = p.pose { log("replay", "pose: looked \(pose.looked), found a player \(pose.found), \(String(format: "%.1f", pose.msAvg)) ms a look, \(pose.rejected) not serves") }
                p.stop()
                DispatchQueue.main.async { if !self.cancelled { self.onFinished(found, rejected) } }
            } catch {
                log("error", "replay: \(error.localizedDescription)")
                DispatchQueue.main.async { self.onFailed(error.localizedDescription) }
            }
        }
    }
}
