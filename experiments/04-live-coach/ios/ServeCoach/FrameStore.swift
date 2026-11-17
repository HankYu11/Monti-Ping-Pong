import AVFoundation
import CoreMedia

/** One encoded frame: its presentation time, whether it is a keyframe, and the sample itself (Frames.kt EncodedFrame). */
struct EncodedFrame {
    let ptsUs: Int64
    let key: Bool
    let sample: CMSampleBuffer
}

/**
 * Encoded frames kept in decoding order (ClipRecorder's store, Frames.kt FrameStore): live, the
 * last [keepSeconds]; for a video test, the whole file. A capture is cut by decoding a span again.
 */
final class FrameStore {
    private(set) var format: CMVideoFormatDescription?
    private var frames: [EncodedFrame] = []
    private let lock = NSLock()
    let keepSeconds: Double?
    init(keepSeconds: Double?) { self.keepSeconds = keepSeconds }

    func setFormat(_ f: CMVideoFormatDescription) { lock.lock(); format = f; lock.unlock() }

    func append(_ f: EncodedFrame) {
        lock.lock(); defer { lock.unlock() }
        frames.append(f)
        if let keep = keepSeconds {
            let cut = f.ptsUs - Int64(keep * 1_000_000)
            var drop = 0
            while drop < frames.count && frames[drop].ptsUs < cut { drop += 1 }
            if drop > 0 { frames.removeFirst(drop) }
        }
    }

    var all: [EncodedFrame] { lock.lock(); defer { lock.unlock() }; return frames }

    /**
     * Frames.kt between(): in decoding order, from the last keyframe at or before [fromUs] (else
     * the first keyframe) up to, not including, the first keyframe after [toUs].
     */
    func between(_ fromUs: Int64, _ toUs: Int64) -> [EncodedFrame] {
        lock.lock(); let fs = frames; lock.unlock()
        var start = fs.lastIndex { $0.key && $0.ptsUs <= fromUs } ?? fs.firstIndex { $0.key } ?? -1
        if start < 0 { return [] }
        var end = fs.count
        for i in (start + 1)..<max(start + 1, fs.count) where fs[i].key && fs[i].ptsUs > toUs { end = i; break }
        start = max(0, start)
        return Array(fs[start..<end])
    }
}

/**
 * A video file's frames, undecoded (Frames.kt FileFrames): every sample of the first video track in
 * decoding order, and the frame rate it was filmed at.
 */
enum FileFrames {
    struct Loaded { let store: FrameStore; let captureFps: Int; let width: Int; let height: Int }

    static func load(_ url: URL) throws -> Loaded {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else { throw err("no video in the file") }
        let reader = try AVAssetReader(asset: asset)
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: nil)   // compressed, as stored
        out.alwaysCopiesSampleData = false
        reader.add(out)
        guard reader.startReading() else { throw err("can't read the video: \(reader.error?.localizedDescription ?? "?")") }
        let store = FrameStore(keepSeconds: nil)
        var pts: [Int64] = []
        while let sb = out.copyNextSampleBuffer() {
            guard CMSampleBufferGetNumSamples(sb) > 0 else { continue }
            if store.format == nil, let f = CMSampleBufferGetFormatDescription(sb) { store.setFormat(f) }
            let t = CMSampleBufferGetPresentationTimeStamp(sb)
            let us = Int64((CMTimeGetSeconds(t) * 1_000_000).rounded())
            store.append(EncodedFrame(ptsUs: us, key: isKey(sb), sample: sb))
            pts.append(us)
        }
        if reader.status == .failed { throw err("reading the video: \(reader.error?.localizedDescription ?? "?")") }
        guard let f = store.format else { throw err("no frames in the video") }
        let d = CMVideoFormatDescriptionGetDimensions(f)
        return Loaded(store: store, captureFps: captureFpsOf(pts), width: Int(d.width), height: Int(d.height))
    }

    /** Frames.kt captureFpsOf: 120 if the median gap is 6-12 ms, else 240 (slow-motion files stretched for playback included) */
    static func captureFpsOf(_ pts: [Int64]) -> Int {
        let s = pts.sorted()
        var gaps: [Int64] = []
        for i in 1..<max(1, s.count) where s[i] > s[i - 1] { gaps.append(s[i] - s[i - 1]) }
        if gaps.isEmpty { return 240 }
        let g = gaps.sorted()[gaps.count / 2]
        return (6_000...12_000).contains(g) ? 120 : 240
    }

    static func isKey(_ sb: CMSampleBuffer) -> Bool {
        guard let a = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]], let d = a.first else { return true }
        return !((d[kCMSampleAttachmentKey_NotSync] as? Bool) ?? false)
    }

    static func err(_ s: String) -> NSError { NSError(domain: "ServeCoach", code: 1, userInfo: [NSLocalizedDescriptionKey: s]) }
}
