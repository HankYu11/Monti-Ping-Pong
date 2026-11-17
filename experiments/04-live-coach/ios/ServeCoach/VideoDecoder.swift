import CoreMedia
import CoreVideo
import VideoToolbox
import ServeCoachCore

/**
 * Encoded frames in, pictures out, in display order (FrameDecoder.kt). The pictures are bi-planar
 * 4:2:0 in the stream's own range, unconverted, so the bytes are the ones Android's decoder gives:
 * each reaches [each] as the core's Yuv, valid only during the call.
 */
final class VideoDecoder {
    let fullRange: Bool
    private var session: VTDecompressionSession?
    /** pictures held back until it is sure no earlier one is still to come (streams with reordered frames) */
    private var pending: [(Int64, CVPixelBuffer)] = []
    private let reorder = 4

    init(format: CMVideoFormatDescription) throws {
        let ext = CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_FullRangeVideo) as? Bool
        fullRange = ext ?? false
        let attrs: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: fullRange ? kCVPixelFormatType_420YpCbCr8BiPlanarFullRange : kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var s: VTDecompressionSession?
        let st = VTDecompressionSessionCreate(allocator: nil, formatDescription: format, decoderSpecification: nil,
                                              imageBufferAttributes: attrs as CFDictionary, outputCallback: nil, decompressionSessionOut: &s)
        guard st == noErr, let s else { throw FileFrames.err("decoder: \(st)") }
        session = s
    }

    deinit { if let s = session { VTDecompressionSessionInvalidate(s) } }

    /** one frame decoded; the pictures that are ready go to [each] */
    func decode(_ f: EncodedFrame, _ each: (Int64, Yuv) -> Void) {
        guard let s = session else { return }
        var got: [(Int64, CVPixelBuffer)] = []
        let st = VTDecompressionSessionDecodeFrame(s, sampleBuffer: f.sample, flags: [], infoFlagsOut: nil) { status, _, image, pts, _ in
            if status == noErr, let image { got.append((Int64((CMTimeGetSeconds(pts) * 1_000_000).rounded()), image)) }
        }
        if st != noErr { return }
        VTDecompressionSessionWaitForAsynchronousFrames(s)
        pending.append(contentsOf: got)
        pending.sort { $0.0 < $1.0 }
        while pending.count > reorder { emit(pending.removeFirst(), each) }
    }

    /** the end: every picture still held */
    func flush(_ each: (Int64, Yuv) -> Void) {
        if let s = session { VTDecompressionSessionFinishDelayedFrames(s); VTDecompressionSessionWaitForAsynchronousFrames(s) }
        while !pending.isEmpty { emit(pending.removeFirst(), each) }
    }

    private func emit(_ p: (Int64, CVPixelBuffer), _ each: (Int64, Yuv) -> Void) {
        VideoDecoder.withYuv(p.1, fullRange: fullRange) { each(p.0, $0) }
    }

    /** a bi-planar picture as the core's Yuv, read in place while it is locked */
    static func withYuv(_ pb: CVPixelBuffer, fullRange: Bool, _ body: (Yuv) -> Void) {
        CVPixelBufferLockBaseAddress(pb, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0), let c = CVPixelBufferGetBaseAddressOfPlane(pb, 1) else { return }
        let yuv = IosFrames.shared.yuv(width: Int32(CVPixelBufferGetWidth(pb)), height: Int32(CVPixelBufferGetHeight(pb)),
                                       y: y, yRowStride: Int32(CVPixelBufferGetBytesPerRowOfPlane(pb, 0)),
                                       cbcr: c, cRowStride: Int32(CVPixelBufferGetBytesPerRowOfPlane(pb, 1)), fullRange: fullRange)
        body(yuv)
    }
}
