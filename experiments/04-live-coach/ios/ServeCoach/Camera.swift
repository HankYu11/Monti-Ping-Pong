import AVFoundation
import VideoToolbox
import ServeCoachCore

/**
 * HighSpeedCamera.kt on iOS: the back wide camera in its slow-motion mode (1920x1080 at 240 fps
 * if it has it, else 720p240, else 120), one video output giving every frame, stabilisation off,
 * and exposure, white balance and focus held still 1.5 s into a session.
 */
final class Camera: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    let session = AVCaptureSession()
    private(set) var device: AVCaptureDevice?
    private(set) var format: AVCaptureDevice.Format?
    private(set) var cfg: CameraConfig?
    private(set) var fullRange = true
    private let output = AVCaptureVideoDataOutput()
    let queue = DispatchQueue(label: "camera", qos: .userInteractive)
    /** every frame, on [queue]: its picture and time */
    var onFrame: ((CMSampleBuffer) -> Void)?
    private(set) var lockNote = "not held"
    private var configured = false

    /** what the camera offers, for the report */
    func probe() -> [String: Any] {
        guard let d = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { return ["error": "no back camera"] }
        let modes = d.formats.compactMap { f -> String? in
            let dim = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            let fps = f.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0
            return fps >= 120 ? "\(dim.width)x\(dim.height)@\(Int(fps)) \(fourcc(CMFormatDescriptionGetMediaSubType(f.formatDescription)))" : nil
        }
        return ["camera": d.localizedName, "slowMotion": Array(Set(modes)).sorted()]
    }

    /** HighSpeedCamera.choose: +1000 for 240 fps, +100 for 1080 high, +50 for 720; full range preferred */
    func choose() -> CameraConfig? {
        guard let d = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { return nil }
        var best: (Int, AVCaptureDevice.Format, Int)?
        for f in d.formats {
            let dim = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
            guard abs(Double(dim.width) / Double(dim.height) - 16.0 / 9) < 0.02 else { continue }
            let sub = CMFormatDescriptionGetMediaSubType(f.formatDescription)
            guard sub == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange || sub == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange else { continue }
            let maxFps = Int(f.videoSupportedFrameRateRanges.map { $0.maxFrameRate }.max() ?? 0)
            guard maxFps >= 120 else { continue }
            let fps = maxFps >= 240 ? 240 : 120
            var score = fps >= 240 ? 1000 : 0
            score += dim.height >= 1080 ? 100 : (dim.height >= 720 ? 50 : 0)
            if dim.height > 1080 { continue }
            score += sub == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange ? 2 : 0
            if best == nil || score > best!.0 { best = (score, f, fps) }
        }
        guard let b = best else { return nil }
        device = d; format = b.1
        fullRange = CMFormatDescriptionGetMediaSubType(b.1.formatDescription) == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        let dim = CMVideoFormatDescriptionGetDimensions(b.1.formatDescription)
        cfg = CameraConfig(width: Int(dim.width), height: Int(dim.height), fps: b.2)
        return cfg
    }

    private func configure() throws {
        guard !configured, let d = device, let f = format else { return }
        session.beginConfiguration()
        session.sessionPreset = .inputPriority
        let input = try AVCaptureDeviceInput(device: d)
        if session.canAddInput(input) { session.addInput(input) }
        output.alwaysDiscardsLateVideoFrames = false
        output.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: CMFormatDescriptionGetMediaSubType(f.formatDescription)]
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        if let c = output.connection(with: .video) {
            if c.isVideoStabilizationSupported { c.preferredVideoStabilizationMode = .off }
            if c.isVideoRotationAngleSupported(0) { c.videoRotationAngle = 0 }
        }
        try d.lockForConfiguration()
        d.activeFormat = f
        d.unlockForConfiguration()
        session.commitConfiguration()
        configured = true
    }

    /** the camera at [fps] (30 for the idle preview, the full rate for a session); on its own queue */
    func run(fps: Int, hold: Bool, done: @escaping (Error?) -> Void) {
        queue.async { [self] in
            do {
                try configure()
                guard let d = device else { throw FileFrames.err("no camera") }
                try d.lockForConfiguration()
                let t = CMTime(value: 1, timescale: CMTimeScale(fps))
                d.activeVideoMinFrameDuration = t; d.activeVideoMaxFrameDuration = t
                if d.isExposureModeSupported(.continuousAutoExposure) { d.exposureMode = .continuousAutoExposure }
                if d.isWhiteBalanceModeSupported(.continuousAutoWhiteBalance) { d.whiteBalanceMode = .continuousAutoWhiteBalance }
                if d.isFocusModeSupported(.continuousAutoFocus) { d.focusMode = .continuousAutoFocus }
                d.unlockForConfiguration()
                lockNote = "not held"
                if !session.isRunning { session.startRunning() }
                done(nil)
                if hold { queue.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.holdStill() } }
            } catch { done(error) }
        }
    }

    /** exposure, white balance and focus held (a change moves the whole picture and every pixel reads as moving) */
    private func holdStill() {
        guard let d = device, session.isRunning else { return }
        do {
            try d.lockForConfiguration()
            var held: [String] = []
            if d.isExposureModeSupported(.locked) { d.exposureMode = .locked; held.append("exposure") }
            if d.isWhiteBalanceModeSupported(.locked) { d.whiteBalanceMode = .locked; held.append("white balance") }
            if d.isFocusModeSupported(.locked) { d.focusMode = .locked; held.append("focus") }
            d.unlockForConfiguration()
            lockNote = held.isEmpty ? "the camera wouldn't hold" : held.joined(separator: ", ") + " held"
        } catch { lockNote = "couldn't hold: \(error.localizedDescription)" }
    }

    func stop() { queue.async { [self] in if session.isRunning { session.stopRunning() } } }

    /** the format's horizontal field of view, degrees (68 when unknown) */
    func hfov() -> Double {
        guard let d = device else { return 68 }
        let f = Double(d.activeFormat.videoFieldOfView)
        return (30...120).contains(f) ? f : 68
    }

    /** the capture header's optics: the format's field of view and the exposure now (HighSpeedCamera.optics) */
    func optics() -> String {
        guard let d = device else { return Optics.shared.headerFields(hfov: nil, exposureNs: nil, detail: "") }
        let fov = Double(d.activeFormat.videoFieldOfView)
        let hfov: KotlinDouble? = (30...120).contains(fov) ? KotlinDouble(value: fov) : nil
        let exp = d.exposureDuration
        let ns: KotlinLong? = exp.isValid ? KotlinLong(value: Int64(CMTimeGetSeconds(exp) * 1e9)) : nil
        return Optics.shared.headerFields(hfov: hfov, exposureNs: ns, detail: "{\"from\":\"videoFieldOfView\",\"zoom\":\(d.videoZoomFactor)}")
    }

    func captureOutput(_ o: AVCaptureOutput, didOutput sb: CMSampleBuffer, from c: AVCaptureConnection) { onFrame?(sb) }
    func captureOutput(_ o: AVCaptureOutput, didDrop sb: CMSampleBuffer, from c: AVCaptureConnection) { dropped += 1 }
    private(set) var dropped = 0

    private func fourcc(_ c: FourCharCode) -> String {
        String(bytes: [UInt8(c >> 24 & 255), UInt8(c >> 16 & 255), UInt8(c >> 8 & 255), UInt8(c & 255)], encoding: .ascii) ?? "\(c)"
    }
}

/**
 * ClipRecorder.kt: the camera's frames into HEVC (H.264 on Android) in real time, a keyframe every 0.1 s, no frame
 * reordering (decode order is display order), kept in a [FrameStore] for the last 5 s.
 */
final class Encoder {
    let store = FrameStore(keepSeconds: 5)
    private(set) var codec = "video/hevc"
    private var session: VTCompressionSession?
    private(set) var framesTotal: Int64 = 0
    private(set) var failures = 0
    var onError: (String) -> Void = { _ in }
    private var times: [CFTimeInterval] = []
    private let lock = NSLock()

    init(width: Int, height: Int, fps: Int) throws {
        var s: VTCompressionSession?
        // HEVC: the iPhone 11 Pro's H.264 encoder gave 184 frames a second at 1080p240, whatever the
        // settings (speed over quality, not real time, baseline); its HEVC encoder keeps all 240.
        // Android takes H.264 and falls back to HEVC. (-enc h264 to try H.264 again.)
        let hevc = !(ProcessInfo.processInfo.arguments.firstIndex(of: "-enc").map { ProcessInfo.processInfo.arguments[$0 + 1].contains("h264") } ?? false)
        self.codec = hevc ? "video/hevc" : "video/avc"
        let st = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
                                            encoderSpecification: nil,
                                            imageBufferAttributes: nil, compressedDataAllocator: nil, outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        guard st == noErr, let s else { throw FileFrames.err("encoder: \(st)") }
        let bitrate = min(width * height * fps / 14, 120_000_000)
        let props: [CFString: Any] = [
            kVTCompressionPropertyKey_RealTime: true,
            kVTCompressionPropertyKey_AllowFrameReordering: false,
            kVTCompressionPropertyKey_AverageBitRate: bitrate,
            kVTCompressionPropertyKey_ExpectedFrameRate: fps,
            kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration: 0.1,
            kVTCompressionPropertyKey_MaxKeyFrameInterval: max(1, fps / 10),
        ]
        for (k, v) in props { VTSessionSetProperty(s, key: k, value: v as CFTypeRef) }
        // trying settings on a phone (-enc speed,norealtime,main)
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-enc"), i + 1 < args.count {
            let o = args[i + 1].split(separator: ",").map(String.init)
            if o.contains("speed") { VTSessionSetProperty(s, key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, value: kCFBooleanTrue) }
            if o.contains("norealtime") { VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanFalse) }
            if o.contains("main") { VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel) }
            if o.contains("baseline") { VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Baseline_AutoLevel) }
        }
        if !hevc { VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_High_AutoLevel) }
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
    }

    func encode(_ sb: CMSampleBuffer) {
        guard let s = session, let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sb)
        let st = VTCompressionSessionEncodeFrame(s, imageBuffer: pb, presentationTimeStamp: pts, duration: .invalid, frameProperties: nil, infoFlagsOut: nil) { [weak self] status, _, out in
            guard let self else { return }
            guard status == noErr, let out, CMSampleBufferDataIsReady(out) else { self.failures += 1; return }
            if self.store.format == nil, let f = CMSampleBufferGetFormatDescription(out) { self.store.setFormat(f) }
            let us = Int64((CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(out)) * 1_000_000).rounded())
            self.store.append(EncodedFrame(ptsUs: us, key: FileFrames.isKey(out), sample: out))
            self.lock.lock(); self.framesTotal += 1; self.times.append(CACurrentMediaTime()); if self.times.count > 480 { self.times.removeFirst(self.times.count - 480) }; self.lock.unlock()
        }
        if st != noErr { failures += 1; if failures == 1 { onError("encoder: \(st)") } }
    }

    /** frames a second over the last second or so */
    func recentFps() -> Double {
        lock.lock(); defer { lock.unlock() }
        let now = CACurrentMediaTime()
        return Double(times.filter { now - $0 <= 1 }.count)
    }

    func stop() {
        if let s = session { VTCompressionSessionCompleteFrames(s, untilPresentationTimeStamp: .invalid); VTCompressionSessionInvalidate(s) }
        session = nil
    }

    /** a span of the store as an .mp4, the encoded frames copied as they are */
    static func writeClip(_ frames: [EncodedFrame], format: CMVideoFormatDescription, to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let w = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil, sourceFormatHint: format)
        input.expectsMediaDataInRealTime = false
        w.add(input)
        guard w.startWriting(), let first = frames.first else { throw w.error ?? FileFrames.err("clip: nothing to write") }
        w.startSession(atSourceTime: CMSampleBufferGetPresentationTimeStamp(first.sample))
        for f in frames {
            while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.002) }
            if !input.append(f.sample) { throw w.error ?? FileFrames.err("clip: append failed") }
        }
        input.markAsFinished()
        let sem = DispatchSemaphore(value: 0)
        w.finishWriting { sem.signal() }
        sem.wait()
        if w.status != .completed { throw w.error ?? FileFrames.err("clip: not finished") }
    }
}
