import Foundation
import Vision
import CoreGraphics
import ServeCoachCore

/**
 * PoseWatcher.kt: is the player walking? A serve is played standing. The hips' sideways travel
 * within any 1 s, in torso lengths, was 0.04-0.68 in all 21 serves and 1.37-3.32 when walking:
 * over 1.0 is not a serve. No one seen: no say.
 *
 * Android runs MediaPipe's pose landmarker; this uses Apple Vision's body pose on the same small
 * picture (the tracker's quarter-size frame, BT.709 to RGB), with the same hips and shoulders.
 */
final class PoseWatcher {
    private let fps: Int
    private let queue = DispatchQueue(label: "pose", qos: .utility)
    private let lock = NSLock()
    private var busy = false
    private var lastNo: Int64 = -1
    private struct Seen { let no: Int64; let hipX: Double; let torso: Double }
    private var seen: [Seen] = []
    private(set) var looked: Int64 = 0, found: Int64 = 0, rejected = 0
    private(set) var msAvg = 0.0
    let ready = true

    init(fps: Int) { self.fps = fps }

    var reportJson: [String: Any] { ["ready": ready, "looked": looked, "found": found, "ms": msAvg, "notServes": rejected, "by": "Apple Vision body pose"] }

    /** a small picture (frame [no], whole frames): looked at if the model is free, else skipped */
    func offer(_ q: Quarter, _ no: Int64) {
        lock.lock()
        if busy || no <= lastNo { lock.unlock(); return }
        busy = true; lastNo = no
        lock.unlock()
        queue.async { [self] in
            defer { lock.lock(); busy = false; lock.unlock() }
            let t0 = DispatchTime.now().uptimeNanoseconds
            guard let img = PoseWatcher.image(q) else { return }
            let req = VNDetectHumanBodyPoseRequest()
            do { try VNImageRequestHandler(cgImage: img).perform([req]) } catch { if looked == 0 { print("SCLOG pose: \(error.localizedDescription) (the simulator cannot run the body pose model)") } }
            looked += 1
            msAvg += (Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6 - msAvg) / Double(min(looked, 60))
            guard let p = req.results?.first, let pts = try? p.recognizedPoints(.all) else { return }
            func at(_ j: VNHumanBodyPoseObservation.JointName) -> CGPoint? {
                guard let r = pts[j], r.confidence > 0.1 else { return nil }
                return CGPoint(x: r.location.x * CGFloat(q.w), y: (1 - r.location.y) * CGFloat(q.h))
            }
            guard let lh = at(.leftHip), let rh = at(.rightHip), let ls = at(.leftShoulder), let rs = at(.rightShoulder) else { return }
            let hx = (lh.x + rh.x) / 2, hy = (lh.y + rh.y) / 2, sx = (ls.x + rs.x) / 2, sy = (ls.y + rs.y) / 2
            found += 1
            lock.lock()
            seen.append(Seen(no: no, hipX: Double(hx), torso: Double(hypot(sx - hx, sy - hy))))
            if seen.count > 600 { seen.removeFirst(seen.count - 600) }
            lock.unlock()
        }
    }

    /** why the movement in frames [from, to] (whole frames) isn't a serve, or nil: the player walking */
    func veto(_ from: Int64, _ to: Int64) -> String? {
        lock.lock(); let pts = seen.filter { $0.no >= from - Int64(fps / 2) && $0.no <= to }; lock.unlock()
        if pts.count < 3 { return nil }
        let torso = pts.map { $0.torso }.sorted()[pts.count / 2]
        if torso < 5 { return nil }
        var travel = 0.0
        for i in pts.indices { for j in (i + 1)..<pts.count { if pts[j].no - pts[i].no > Int64(fps) { break }; travel = max(travel, abs(pts[j].hipX - pts[i].hipX)) } }
        let t = travel / torso
        if t <= 1.0 { return nil }
        rejected += 1
        return "the player was walking (hips moved \(String(format: "%.1f", t)) torso lengths in a second)"
    }

    /** brightness and colour (the tracker's units) to RGB, BT.709 */
    private static func image(_ q: Quarter) -> CGImage? {
        let w = Int(q.w), h = Int(q.h), n = w * h
        var px = [UInt8](repeating: 255, count: n * 4)
        let ys = q.y, us = q.u, vs = q.v
        for i in 0..<n {
            let y = ys.get(index: Int32(i)), u = us.get(index: Int32(i)), v = vs.get(index: Int32(i))
            px[i * 4] = UInt8(max(0, min(255, Int(y + 1.5748 * v))))
            px[i * 4 + 1] = UInt8(max(0, min(255, Int(y - 0.1873 * u - 0.4681 * v))))
            px[i * 4 + 2] = UInt8(max(0, min(255, Int(y + 1.8556 * u))))
        }
        return px.withUnsafeMutableBytes { b in
            CGContext(data: b.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)?.makeImage()
        }
    }
}
