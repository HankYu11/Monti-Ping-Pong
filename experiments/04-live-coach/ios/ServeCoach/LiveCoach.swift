import UIKit
import AVFoundation
import CoreMotion
import CoreImage
import ServeCoachCore

/** the camera mode chosen for serving (HighSpeedCamera.choose) */
struct CameraConfig { let width: Int; let height: Int; let fps: Int }

/**
 * The camera side of MainActivity: the idle preview and setup check, a live session (camera →
 * encoder → frame store → pipeline), the session's files and report, and the setup assistant.
 */
final class LiveCoach {
    unowned let vc: CoachViewController
    let camera = Camera()
    private(set) var cfg: CameraConfig?
    private(set) var pipeline: Pipeline?
    private var encoder: Encoder?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private(set) var previewing = false
    private var starting = false
    private var active = true
    private let guide = SetupGuide()
    private var lastAdviceKind: SetupCheck.Kind?
    private var lastAdviceSaid: Int64 = 0
    private(set) var session = ""
    private var sessionFolder: URL? { session.isEmpty ? nil : Sessions.folder(session) }
    private var sessionFiles: [URL] = []
    private var sessionBytes: Int64 = 0
    private var sessionClips = 0
    let ai = GeminiCoach()

    var recordingFps: Double { encoder?.recentFps() ?? 0 }
    var lagMs: Int64 {
        guard let p = pipeline, p.lastPictureUs > 0 else { return 0 }
        let now = Int64(CMTimeGetSeconds(CMClockGetTime(CMClockGetHostTimeClock())) * 1_000_000)
        return max(0, (now - p.lastPictureUs) / 1000)
    }
    var aiOn: Bool { UserDefaults.standard.bool(forKey: "ai") }

    init(_ vc: CoachViewController) {
        self.vc = vc
        guide.onAdvice = { [weak self] a in DispatchQueue.main.async { self?.showAdvice(a) } }
        guide.onJpeg = { [weak self] j in self?.ai.sendFrame(j) }
        ai.onText = { [weak self] t in DispatchQueue.main.async { self?.vc.screen.aiText(t) } }
        ai.onStatus = { [weak self] s in DispatchQueue.main.async { self?.aiStatus(s) } }
    }

    // ------------------------------------------------------------------ camera
    func checkCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { if ok { self.checkCamera() } else { self.vc.state("The coach needs the camera") } } }
            return
        case .authorized: break
        default: vc.state("The coach needs the camera"); return
        }
        vc.report.camera = camera.probe()
        cfg = camera.choose()
        if let c = cfg {
            vc.report.chosen = ["camera": "back wide", "size": "\(c.width)x\(c.height)", "fps": c.fps, "codec": "video/avc", "encoder": "VideoToolbox"]
        } else { vc.report.chosen = NSNull() }
        vc.state(cfg == nil ? "This phone doesn't offer slow motion to apps" : "Ready")
        if cfg != nil { attachPreview(); startPreview() }
    }

    private func attachPreview() {
        guard previewLayer == nil, let c = cfg else { return }
        let l = AVCaptureVideoPreviewLayer(session: camera.session)
        l.videoGravity = .resizeAspect
        if let conn = l.connection, conn.isVideoRotationAngleSupported(0) { conn.videoRotationAngle = 0 }
        vc.screen.preview.layer.addSublayer(l)
        previewLayer = l
        vc.screen.onLayout = { [weak self] b in
            guard let l = self?.previewLayer else { return }
            let w = b.height * CGFloat(c.width) / CGFloat(c.height)
            l.frame = CGRect(x: (b.width - w) / 2, y: 0, width: w, height: b.height)
        }
        vc.screen.setNeedsLayout()
    }

    /** idle: the camera at 30 fps to set the phone up by, with the setup check (and the assistant) */
    func startPreview() {
        guard cfg != nil, vc.mode == .idle, !previewing, active, AVCaptureDevice.authorizationStatus(for: .video) == .authorized else { return }
        previewing = true
        lastAdviceKind = nil
        guide.start()
        camera.onFrame = { [weak self] sb in self?.guide.offer(sb) }
        camera.run(fps: 30, hold: false) { [weak self] e in
            DispatchQueue.main.async {
                guard let self else { return }
                if let e { self.previewing = false; self.vc.log("error", "camera error: \(e.localizedDescription)")
                    self.vc.screen.problem("The camera didn't open. Close other camera apps, then open Serve Coach again."); return }
                self.vc.log("camera", "preview running")
                // the preview's connection exists only once the camera has an input: landscape right, unrotated
                if let conn = self.previewLayer?.connection, conn.isVideoRotationAngleSupported(0) { conn.videoRotationAngle = 0 }
                if self.aiOn { self.startAi() }
            }
        }
    }
    func stopPreview() {
        guard previewing else { return }
        previewing = false
        camera.onFrame = nil
        guide.stop()
        ai.stop()
    }

    // ------------------------------------------------------------------ setup advice
    private func showAdvice(_ a: SetupCheck.Advice) {
        guard vc.mode == .idle, previewing else { return }
        let short: String
        switch a.kind {
        case .good: short = "looks good"
        case .noTable: short = "no table in view"
        case .moving: short = "hold still"
        case .dark: short = "too dark"
        case .tilted: short = "level the phone"
        case .far: short = "move closer"
        case .near: short = "move back"
        case .tableHigh: short = "tilt up"
        case .tableLow: short = "tilt down"
        case .endOn: short = "face the side"
        default: short = a.kind.name.lowercased()
        }
        vc.screen.advice(short: short, text: a.text, good: a.ok)
        vc.log("setup", a.description)
        if ai.ready { ai.ask(a.text) }
        else if a.kind != .moving {
            let now = nowMs()
            if a.kind != lastAdviceKind || now - lastAdviceSaid > 15_000 { lastAdviceSaid = now; lastAdviceKind = a.kind; vc.say(a.text) }
        }
    }

    // ------------------------------------------------------------------ a session
    func start() {
        guard let c = cfg, vc.mode == .idle, !starting else { return }
        starting = true
        if previewing { stopPreview(); vc.state("Starting…") }
        session = Sessions.newName()
        sessionFiles = []; sessionBytes = 0; sessionClips = 0
        vc.report.eventsFrom = vc.report.eventsEnd; vc.report.resultsFrom = vc.report.results.count
        vc.sessionStarted()
        vc.state("Starting: wait for Ready…")
        let enc: Encoder
        do { enc = try Encoder(width: c.width, height: c.height, fps: c.fps) }
        catch { starting = false; vc.log("error", "encoder: \(error.localizedDescription)"); vc.stopAll(); return }
        enc.onError = { [weak self] e in DispatchQueue.main.async { self?.vc.log("error", e) } }
        encoder = enc
        let p = Pipeline(store: enc.store, blocking: false, captureFps: c.fps, captureDir: vc.capDir, firstId: Sessions.firstFreeCaptureId())
        p.optics = { [weak self] in self?.camera.optics() ?? "" }
        p.onError = { [weak self] e in DispatchQueue.main.async { self?.vc.log("error", e) } }
        p.onNote = { [weak self] e in DispatchQueue.main.async { self?.vc.log("load", e) } }
        p.onSpotted = { [weak self] id in DispatchQueue.main.async { self?.vc.onSpotted(id) } }
        p.onCaptureStarted = { [weak self] id, _ in DispatchQueue.main.async { self?.vc.captureStarted(id) } }
        p.onCapture = { [weak self] id, n, _, ms, fromUs, toUs in
            DispatchQueue.main.async { self?.vc.captured(id, frames: n, writeMs: ms); self?.vc.log("capture", "#\(id): \(n) frames, written in \(ms) ms") }
            self?.saveClip(id, fromUs, toUs)
        }
        p.pose = PoseWatcher(fps: c.fps)
        p.onSkippedSpan = { [weak self] n, why, fromUs, toUs in self?.saveSkipped(n, why, fromUs, toUs) }
        pipeline = p
        p.start()
        let full = camera.fullRange
        camera.onFrame = { [weak p, weak enc] sb in
            enc?.encode(sb)
            guard let p, let pb = CMSampleBufferGetImageBuffer(sb) else { return }
            let us = Int64((CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sb)) * 1_000_000).rounded())
            VideoDecoder.withYuv(pb, fullRange: full) { p.onCameraFrame(us, $0) }
        }
        camera.run(fps: c.fps, hold: true) { [weak self] e in
            DispatchQueue.main.async {
                guard let self else { return }
                self.starting = false
                if let e { self.vc.log("error", "camera error: \(e.localizedDescription)"); self.vc.stopAll(); return }
                self.vc.log("camera", "session running: \(c.width)x\(c.height) @ \(c.fps)")
                self.waitForReady(p, tries: 0)
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    guard let self, self.pipeline === p, p.decoded == 0 else { return }
                    self.vc.tone(Hud.RED); self.vc.state("No frames from the camera: stop and send the report")
                    self.vc.log("error", "no frames from the camera in 3 s")
                }
            }
        }
        reportEvery(session)
    }

    /**
     * The first Ready. Android waits for its GPU picture check; iOS has no GPU tap, so it waits for
     * what that check was there to protect: the tracker's background, learnt from the first frames.
     */
    private func waitForReady(_ p: Pipeline, tries: Int) {
        guard pipeline === p, vc.mode == .live else { return }
        if p.tracker?.background() != nil { vc.setReady("Ready."); return }
        if tries >= 20 {
            vc.say("Picture check failed. Watching anyway; send the report.")
            vc.log("error", "picture check failed: no background after 10 s")
            vc.setReady("Ready.")
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.waitForReady(p, tries: tries + 1) }
    }

    func stop() {
        starting = false
        camera.onFrame = nil
        if let p = pipeline {
            var w: [String: Any] = ["encoded": encoder?.framesTotal ?? 0, "decoded": p.decoded, "dropped": p.dropped, "skipped": p.skipped,
                                    "cameraGaps": p.cameraGaps, "cameraDropped": camera.dropped, "msPerFrame": p.trackMsAvg,
                                    "shrinkMs": p.shrinkMsAvg, "findMs": p.findMsAvg, "linkMs": p.linkMsAvg, "lagMs": lagMs,
                                    "serves": p.servesFound, "trimmed": p.trimmed, "ignoredWhileMeasuring": p.ignoredWhileMeasuring,
                                    "cameraHeld": camera.lockNote, "rejected": p.rejected]
            if let e = encoder { w["encoderFailures"] = e.failures }
            vc.report.extra["watching"] = w
            if let pose = p.pose { vc.report.extra["pose"] = pose.reportJson }
        }
        pipeline?.stop(); pipeline = nil
        encoder?.stop(); encoder = nil
        if !previewing { camera.stop() }
    }

    func paused() { active = false; stopPreview(); camera.stop() }
    func resumed() { active = true; startPreview() }

    // ------------------------------------------------------------------ files
    private func save(_ name: String, _ write: @escaping (URL) throws -> Void, clip: Bool = false) {
        guard let folder = sessionFolder else { return }
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let url = folder.appendingPathComponent(name)
            do {
                try write(url)
                let n = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
                DispatchQueue.main.async {
                    guard let self else { return }
                    if folder == self.sessionFolder { self.sessionFiles.append(url); self.sessionBytes += n; if clip { self.sessionClips += 1 } }
                    if clip { self.vc.log("saved", "ServeCoach/\(folder.lastPathComponent)/\(name)") }
                }
            } catch { DispatchQueue.main.async { self?.vc.log("error", "save \(name): \(error.localizedDescription)") } }
        }
    }

    /** every captured serve's clip, always, while live */
    private func saveClip(_ id: Int, _ fromUs: Int64, _ toUs: Int64) {
        guard let enc = encoder, let fmt = enc.store.format else { return }
        let frames = enc.store.between(fromUs, toUs)
        if frames.isEmpty { return }
        save(String(format: "serve%02d.mp4", id), { url in try Encoder.writeClip(frames, format: fmt, to: url) }, clip: true)
    }

    /** a movement the tracker turned down (up to 30 a session): its clip, 0.5 s before to 0.3 s after */
    private func saveSkipped(_ n: Int, _ why: String, _ fromUs: Int64, _ toUs: Int64) {
        guard let enc = encoder else { return }
        let slug = why.replacingOccurrences(of: "[^a-z]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        DispatchQueue.global(qos: .utility).async { [weak self] in
            var waited = 0
            while enc.store.newestUs < toUs + 300_000 && waited < 1500 { Thread.sleep(forTimeInterval: 0.02); waited += 20 }
            guard let fmt = enc.store.format else { return }
            let frames = enc.store.between(fromUs - 500_000, toUs + 300_000)
            if frames.isEmpty { return }
            DispatchQueue.main.async { self?.save("skipped\(n)_\(slug).mp4", { url in try Encoder.writeClip(frames, format: fmt, to: url) }, clip: true) }
        }
    }

    func saveServeData(_ id: Int, _ r: [String: Any], _ data: Any?) {
        guard !session.isEmpty else { return }
        var head: Any = NSNull()
        if let d = try? Data(contentsOf: vc.capDir.appendingPathComponent("cap_\(id).json")), let j = try? JSONSerialization.jsonObject(with: d) { head = j }
        let rec: [String: Any] = ["app": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "", "session": session,
                                  "serve": id, "clip": String(format: "serve%02d.mp4", id), "time": Int64(Date().timeIntervalSince1970 * 1000),
                                  "capture": head, "result": r, "data": data ?? NSNull()]
        guard let bytes = try? JSONSerialization.data(withJSONObject: rec, options: [.fragmentsAllowed]) else { return }
        save(String(format: "serve%02d.json", id), { url in try bytes.write(to: url) })
    }

    func writeReport(then: (() -> Void)? = nil) {
        guard let folder = sessionFolder, vc.mode != .replay else { then?(); return }
        let url = folder.appendingPathComponent("report.json")
        vc.report.write(to: folder, then: { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                if !self.sessionFiles.contains(url) { self.sessionFiles.append(url) }
                then?()
            }
        }, failed: { [weak self] e in self?.vc.log("error", e) })
    }

    private func reportEvery(_ name: String) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            guard let self, self.session == name, self.vc.mode == .live else { return }
            self.writeReport(); self.reportEvery(name)
        }
    }

    /** at Stop: the answers still coming first (up to 30 s), the report, then (if turned on) the offer to share */
    func finishSession(tries: Int = 0) {
        guard !session.isEmpty else { return }
        if vc.pendingCount > 0 && tries < 60 { DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.finishSession(tries: tries + 1) }; return }
        let name = session
        writeReport { [weak self] in
            guard let self else { return }
            self.vc.log("saved", "ServeCoach/\(name)/report.json")
            self.vc.report.eventsEnd = self.vc.report.events.count
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.offerShare(name) }
        }
    }

    private func offerShare(_ name: String) {
        guard vc.shareClipsOn, sessionFiles.count > 1, vc.presentedViewController == nil else { return }
        let files = sessionFiles, clips = sessionClips
        let what = clips == 1 ? "1 video clip that shows the player" : "\(clips) video clips that show the player"
        let a = UIAlertController(title: "Share this session's clips?",
            message: "This sends \(what), with each serve's data and report.json (\(files.count) files, \(sessionBytes / 1_000_000) MB, in Files › On My iPhone › Serve Coach Live › ServeCoach › \(name)), to wherever you pick next.\n\nOnly share them if the player agreed, and a parent or guardian too if the player is under 18.",
            preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Don't share", style: .cancel))
        a.addAction(UIAlertAction(title: "Choose where to send", style: .default) { [weak self] _ in
            guard let self else { return }
            let s = UIActivityViewController(activityItems: files, applicationActivities: nil)
            s.popoverPresentationController?.sourceView = self.vc.view
            self.vc.present(s, animated: true)
        })
        vc.present(a, animated: true)
    }

    // ------------------------------------------------------------------ the setup assistant
    func toggleAi() {
        if aiOn {
            UserDefaults.standard.set(false, forKey: "ai"); ai.stop(); vc.screen.aiState(false); return
        }
        guard let key = Keychain.get("gemini") else { askKey(); return }
        UserDefaults.standard.set(true, forKey: "ai")
        vc.screen.aiState(true)
        vc.toast("Setup assistant on, key ••••\(String(key.suffix(4)))")
        if previewing { startAi() }
    }
    private func startAi() {
        guard let key = Keychain.get("gemini"), vc.mode == .idle, previewing else { return }
        vc.stopSpeaking()
        ai.start(key: key)
    }
    private func aiStatus(_ s: String) {
        vc.log("ai", s)
        if s.contains("refused") {
            Keychain.delete("gemini"); UserDefaults.standard.set(false, forKey: "ai")
            vc.screen.aiState(false); ai.stop(); vc.toast(s, long: true)
        }
    }
    private func askKey() {
        let a = UIAlertController(title: "AI setup coach",
            message: "While you set the phone up, it sends what the camera sees, about one picture a second, to Google's Gemini and plays its spoken advice. Nothing is sent once you press Start. It uses your own Gemini API key (aistudio.google.com/apikey), kept on this phone only.",
            preferredStyle: .alert)
        a.addTextField { t in
            t.placeholder = "Gemini API key"; t.isSecureTextEntry = true; t.textContentType = .none
            t.autocorrectionType = .no; t.autocapitalizationType = .none; t.spellCheckingType = .no
        }
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        a.addAction(UIAlertAction(title: "Turn on", style: .default) { [weak self, weak a] _ in
            let k = a?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !k.isEmpty else { return }
            Keychain.set("gemini", k)
            self?.toggleAi()
        })
        vc.present(a, animated: true)
    }
}

/**
 * SetupGuide.kt: while the preview runs, a 320x180 copy of the picture is checked at most every
 * 300 ms with [SetupCheck], with the phone's tilt and movement; advice that held 900 ms is passed
 * on. About once a second a JPEG of the picture goes to the assistant.
 */
final class SetupGuide {
    var onAdvice: (SetupCheck.Advice) -> Void = { _ in }
    var onJpeg: ((Data) -> Void)?
    private let motion = CMMotionManager()
    private var up: (Double, Double, Double) = (0, 0, 9.8)
    private var movedAt: Int64 = 0
    private var lastCheck: Int64 = 0, lastJpeg: Int64 = 0
    private var pending: SetupCheck.Kind?, pendingSince: Int64 = 0, shown: SetupCheck.Kind?
    private let w = 320, h = 180
    private let y = KotlinByteArray(size: 320 * 180), u = KotlinByteArray(size: 320 * 180), v = KotlinByteArray(size: 320 * 180)
    private let ci = CIContext()
    private let lock = NSLock()

    func start() {
        pending = nil; shown = nil
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 0.06
        motion.startDeviceMotionUpdates(to: OperationQueue()) { [weak self] m, _ in
            guard let self, let m else { return }
            // Android's accelerometer reads +9.8 along the axis pointing up; Core Motion's gravity points down, in g
            self.lock.lock()
            self.up = (-m.gravity.x * 9.81, -m.gravity.y * 9.81, -m.gravity.z * 9.81)
            let r = m.rotationRate
            if (r.x * r.x + r.y * r.y + r.z * r.z).squareRoot() > 0.25 { self.movedAt = nowMs() }
            self.lock.unlock()
        }
    }
    func stop() { motion.stopDeviceMotionUpdates() }

    /** the phone's roll from level in landscape right (Android ROTATION_90): 0 when level */
    private func tilt() -> Float {
        lock.lock(); let a = up; lock.unlock()
        if abs(a.2) > 8.5 { return 0 }
        return Float(atan2(a.1, a.0) * 180 / .pi)
    }

    /** on the camera's queue */
    func offer(_ sb: CMSampleBuffer) {
        let now = nowMs()
        guard let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        if let j = onJpeg, now - lastJpeg >= 1000 { lastJpeg = now; if let d = jpeg(pb) { j(d) } }
        if now - lastCheck < 300 { return }
        lastCheck = now
        sample(pb)
        lock.lock(); let moving = now - movedAt < 600; lock.unlock()
        let r = SetupCheck.shared.advise(y: y, u: u, v: v, w: Int32(w), h: Int32(h), tiltDeg: tilt(), moving: moving)
        guard let a = r.first else { return }
        if a.kind != pending { pending = a.kind; pendingSince = now }
        if a.kind != shown && now - pendingSince >= 900 { shown = a.kind; onAdvice(a) }
    }

    /** Y, and U, V at the same size (nearest sample) from the bi-planar picture */
    private func sample(_ pb: CVPixelBuffer) {
        CVPixelBufferLockBaseAddress(pb, .readOnly); defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
        guard let py = CVPixelBufferGetBaseAddressOfPlane(pb, 0)?.assumingMemoryBound(to: Int8.self),
              let pc = CVPixelBufferGetBaseAddressOfPlane(pb, 1)?.assumingMemoryBound(to: Int8.self) else { return }
        let ys = CVPixelBufferGetBytesPerRowOfPlane(pb, 0), cs = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
        let sx = Float(CVPixelBufferGetWidth(pb)) / Float(w), sy = Float(CVPixelBufferGetHeight(pb)) / Float(h)
        for j in 0..<h {
            let yy = Int(Float(j) * sy), cy = yy / 2
            for i in 0..<w {
                let xx = Int(Float(i) * sx), cx = xx / 2, p = Int32(j * w + i)
                y.set(index: p, value: py[yy * ys + xx])
                u.set(index: p, value: pc[cy * cs + cx * 2])
                v.set(index: p, value: pc[cy * cs + cx * 2 + 1])
            }
        }
    }

    /** the picture at 640 wide, quality 70 */
    private func jpeg(_ pb: CVPixelBuffer) -> Data? {
        let img = CIImage(cvPixelBuffer: pb)
        let s = 640 / img.extent.width
        let small = img.transformed(by: CGAffineTransform(scaleX: s, y: s))
        return ci.jpegRepresentation(of: small, colorSpace: CGColorSpaceCreateDeviceRGB(), options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.7])
    }
}

/** the Gemini key: this device only, never in a backup (Android: private prefs, backup off) */
enum Keychain {
    private static func q(_ k: String) -> [CFString: Any] {
        [kSecClass: kSecClassGenericPassword, kSecAttrService: "com.monti.servecoach", kSecAttrAccount: k]
    }
    static func get(_ k: String) -> String? {
        var query = q(k); query[kSecReturnData] = true; query[kSecMatchLimit] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
    static func set(_ k: String, _ v: String) {
        delete(k)
        var a = q(k); a[kSecValueData] = Data(v.utf8); a[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(a as CFDictionary, nil)
    }
    static func delete(_ k: String) { SecItemDelete(q(k) as CFDictionary) }
}
