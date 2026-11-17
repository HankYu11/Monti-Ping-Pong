import Foundation
import ServeCoachCore

/**
 * Pictures in, serves out, each serve cut into a capture for the engine: LivePipeline.kt's default
 * path (decode -> Downsample -> Detector.find -> ServeTracker.pushFound -> writeCapture), with the
 * same threads, queues and numbers. Android's GPU tap, window ring and scene background are an
 * experiment there (off by default) and are not here.
 *
 * Threads, as on Android: a decoder (video test) or the camera (live) calls [onPicture]; three
 * finders; the "serve-tracker" linker; a worker per capture. The tracker is used from exactly two of
 * them, the picture's thread (sample/background/needsFrame) and the linker (pushFound/cropFor).
 */
final class Pipeline {
    let store: FrameStore
    let blocking: Bool
    let captureFps: Int
    let captureDir: URL
    private let step = 2

    var optics: () -> String = { "" }
    /** live: a serve is being measured; others are ignored until [resume] */
    private(set) var paused = false
    private(set) var ignoredWhileMeasuring = 0
    func resume() { paused = false }

    var onSpotted: ((Int) -> Void)?
    var onCaptureStarted: ((Int, Int) -> Void)?            // id, frames guessed
    var onCapture: ((Int, Int, String, Int64, Int64, Int64) -> Void)?   // id, n, head, writeMs, fromUs, toUs
    var onNote: (String) -> Void = { _ in }
    /** is the player walking? (PoseWatcher) A serve it vetoes is not spotted, measured or saved */
    var pose: PoseWatcher?
    /** a movement the tracker turned down, as a span of the recording: number, why, from, to (µs) (LivePipeline.watchSkips) */
    var onSkippedSpan: ((Int, String, Int64, Int64) -> Void)?
    private(set) var skippedClips = 0
    var onError: (String) -> Void = { _ in }

    private let queue: BlockingQueue<EncodedFrame?>
    private let jobs: BlockingQueue<Job>
    private let finders = OperationQueue()
    private let workers = OperationQueue()
    private var running = true
    private var decoderThread: Thread?
    private var linkerThread: Thread?
    private let linkerDone = DispatchSemaphore(value: 0)
    private let decoderDone = DispatchSemaphore(value: 0)

    private(set) var tracker: ServeTracker?
    private(set) var decoded: Int64 = 0
    private(set) var dropped: Int64 = 0
    private(set) var skipped: Int64 = 0
    private(set) var servesFound = 0
    private(set) var captures = 0
    private(set) var trimmed = 0
    private var frameW: Int32 = 0, frameH: Int32 = 0
    private var ptsRing = [Int64](repeating: 0, count: 8192)
    private(set) var shrinkMsAvg = 0.0, findMsAvg = 0.0, linkMsAvg = 0.0
    var trackMsAvg: Double { shrinkMsAvg + findMsAvg + linkMsAvg }

    private final class Job {
        let index: Int64; let found: Future<FoundBox>?; let end: Bool
        init(_ index: Int64, _ found: Future<FoundBox>?, end: Bool = false) { self.index = index; self.found = found; self.end = end }
    }

    /** debugging (-dumpq): every tracker frame's picture and candidates, as the desktop harness's DUMPQ prints them */
    static var dumpQ = false
    private let dumps = DumpTable()

    /** the captures being written or measured, by number: the engine's reads wait on them */
    static let writing = WritingTable()

    init(store: FrameStore, blocking: Bool, captureFps: Int, captureDir: URL, firstId: Int = 0) {
        self.store = store; self.blocking = blocking; self.captureFps = captureFps; self.captureDir = captureDir
        captures = firstId - 1
        queue = BlockingQueue(capacity: blocking ? 64 : 720)
        jobs = BlockingQueue(capacity: blocking ? 16 : 24)
        finders.maxConcurrentOperationCount = 3
        finders.qualityOfService = .userInitiated
        workers.qualityOfService = .utility
    }

    func start() {
        let d = Thread { [weak self] in self?.runDecoder(); self?.decoderDone.signal() }
        d.name = "decoder"; d.qualityOfService = .userInitiated
        let l = Thread { [weak self] in self?.link(); self?.linkerDone.signal() }
        l.name = "serve-tracker"; l.qualityOfService = .userInitiated
        decoderThread = d; linkerThread = l
        if blocking { d.start() }
        l.start()
    }

    func stop() {
        running = false
        _ = decoderDone.wait(timeout: .now() + 1); _ = linkerDone.wait(timeout: .now() + 1)
        finders.cancelAllOperations()
    }

    /** video test: the file ended; decode what's left and let a serve still being watched out. Blocks until done. */
    func finish() {
        queue.put(nil)
        decoderDone.wait(); linkerDone.wait()
        finders.waitUntilAllOperationsAreFinished()
    }

    /** the capture workers, waited for */
    func waitForWorkers() { workers.waitUntilAllOperationsAreFinished() }

    // ---- the video test's decoder -----------------------------------------------------------

    /** a frame of the file: blocks while the decoder is behind */
    func offer(_ f: EncodedFrame) {
        if blocking { queue.put(f); return }
        if !queue.offer(f) { dropped += 1 }
    }

    /**
     * Before the video plays, the background from keyframes spread over it (LivePipeline.prime):
     * live, the camera has been running a while; a video may start with the serve.
     */
    func prime(_ frames: [EncodedFrame], count: Int = 5) {
        guard let fmt = store.format else { return }
        let keys = frames.filter { $0.key }
        if keys.isEmpty { return }
        var seen = Set<Int>(); var pick: [EncodedFrame] = []
        for i in 0..<count { let k = i * (keys.count - 1) / max(1, count - 1); if seen.insert(k).inserted { pick.append(keys[k]) } }
        var qs: [Quarter] = []
        for k in pick {
            guard let dec = try? VideoDecoder(format: fmt) else { continue }
            dec.decode(k) { _, yuv in if qs.count < count { qs.append(Downsample.shared.quarter(f: yuv)) } }
            dec.flush { _, yuv in if qs.count < count { qs.append(Downsample.shared.quarter(f: yuv)) } }
        }
        if Pipeline.dumpQ {
            print("SCPRIME keys \(keys.count) picked \(pick.map { $0.ptsUs }) got \(qs.count)")
            for q in qs { print("SCPRIME " + IosDump.shared.line(index: -1, q: q, found: nil)) }
        }
        guard let q = qs.first else { return }
        let tr = tracker ?? watchSkips(ServeTracker(w: q.w, h: q.h, fps: Int32(captureFps / step)))
        tracker = tr
        tr.prime(spread: qs)
    }

    private func runDecoder() {
        var dec: VideoDecoder?
        while running {
            guard let item = queue.poll(ms: 20) else { continue }
            guard let fr = item else {          // the end
                dec?.flush { pts, yuv in onPicture(pts, yuv) }
                jobs.put(Job(decoded / Int64(step), nil, end: true))
                return
            }
            if dec == nil {
                guard let fmt = store.format else { continue }
                do { dec = try VideoDecoder(format: fmt) } catch { onError("decoder: \(error.localizedDescription)"); return }
            }
            dec!.decode(fr) { pts, yuv in onPicture(pts, yuv) }
        }
    }

    // ---- every picture ----------------------------------------------------------------------

    private func avg(_ old: Double, _ x: Double, _ n: Int64) -> Double { old + (x - old) / Double(max(1, min(n, 240))) }
    private var shrinkN: Int64 = 0

    /** the picture's thread: its time noted; every 2nd one shrunk and sent to the finders */
    func onPicture(_ pts: Int64, _ yuv: Yuv) {
        let n = decoded
        ptsRing[Int(n % Int64(ptsRing.count))] = pts
        decoded = n + 1
        picture(n, yuv)
    }

    // ---- live: the camera's own pictures, numbered by their time (GlTap: n = round((ts - t0) * fps)) ----
    private var t0Us: Int64 = -1
    private var lastNo: Int64 = -1
    /** frames the camera never delivered, by their numbers */
    private(set) var cameraGaps: Int64 = 0
    /** pictures in arrival time, live: how far the newest picture is behind the clock */
    private(set) var lastPictureUs: Int64 = 0
    func onCameraFrame(_ ptsUs: Int64, _ yuv: Yuv) {
        if t0Us < 0 { t0Us = ptsUs }
        let n = Int64((Double(ptsUs - t0Us) * Double(captureFps) / 1e6).rounded())
        if n <= lastNo { return }                               // a repeat
        let ring = Int64(ptsRing.count)
        if lastNo >= 0 && n > lastNo + 1 {                      // gaps: their times extrapolated
            let prev = ptsRing[Int(lastNo % ring)]
            for k in (lastNo + 1)..<n { ptsRing[Int(k % ring)] = prev + (k - lastNo) * 1_000_000 / Int64(captureFps) }
            cameraGaps += n - lastNo - 1
        }
        ptsRing[Int(n % ring)] = ptsUs
        lastNo = n
        decoded = n + 1
        lastPictureUs = ptsUs
        picture(n, yuv)
    }

    private func picture(_ n: Int64, _ yuv: Yuv) {
        if n % Int64(step) != 0 { return }
        let index = n / Int64(step)
        if !blocking && jobs.remainingCapacity == 0 { skipped += 1; return }   // the finders are behind
        frameW = yuv.width; frameH = yuv.height
        let t0 = DispatchTime.now().uptimeNanoseconds
        let rows = Downsample.shared.rows(f: yuv)
        shrinkN += 1
        shrinkMsAvg = avg(shrinkMsAvg, Double(DispatchTime.now().uptimeNanoseconds - t0) / 1e6, shrinkN)
        let tr = tracker ?? watchSkips(ServeTracker(w: rows.w, h: rows.h, fps: Int32(captureFps / step)))
        tracker = tr
        // the background learns from every 24th frame, before that frame's blobs are found
        var q0: Quarter?
        if tr.needsFrame(index: index) { let q = Downsample.shared.quarter(r: rows); tr.sample(q: q, index: index); q0 = q }
        guard let bg = tr.background() else {
            if Pipeline.dumpQ { let q = q0 ?? Downsample.shared.quarter(r: rows); dumps.set(index, IosDump.shared.line(index: index, q: q, found: nil)) }
            jobs.put(Job(index, nil)); return
        }
        let fut = Future<FoundBox>()
        let w = rows.w, h = rows.h
        finders.addOperation { [weak self] in
            let d = Pipeline.detector(w, h)
            let t = DispatchTime.now().uptimeNanoseconds
            let q = q0 ?? Downsample.shared.quarter(r: rows)
            let c = IosTrack.shared.find(d: d, q: q, bg: bg)
            if index % 16 == 0, let pose = self?.pose { pose.offer(q, index * Int64(self?.step ?? 2)) }
            if Pipeline.dumpQ, let self { self.dumps.set(index, IosDump.shared.line(index: index, q: q, found: c)) }
            if let self { self.findMsAvg = self.avg(self.findMsAvg, Double(DispatchTime.now().uptimeNanoseconds - t) / 1e6, index + 1) }
            fut.set(c)
        }
        jobs.put(Job(index, fut))
    }

    /** one Detector per finder thread (LivePipeline's ThreadLocal) */
    private static func detector(_ w: Int32, _ h: Int32) -> Detector {
        let key = "servecoach.detector.\(w)x\(h)"
        if let d = Thread.current.threadDictionary[key] as? Detector { return d }
        let d = Detector(w: w, h: h)
        Thread.current.threadDictionary[key] = d
        return d
    }

    /** the tracker's own thread: blobs linked into tracks, frame by frame in order */
    private func link() {
        var n: Int64 = 0
        while true {
            guard let job = jobs.poll(ms: 50) else { if running { continue } else { break } }
            guard let tr = tracker else { continue }
            if job.end {
                var i = job.index
                for _ in 0..<120 { if let s = IosTrack.shared.push(tr: tr, index: i, found: nil) { onServe(tr, s) }; i += 1 }
                break
            }
            let found = job.found?.get()
            let t = DispatchTime.now().uptimeNanoseconds
            let serve = IosTrack.shared.push(tr: tr, index: job.index, found: found)
            if Pipeline.dumpQ, let l = dumps.take(job.index) { print("SCQ " + l) }
            n += 1
            linkMsAvg = avg(linkMsAvg, Double(DispatchTime.now().uptimeNanoseconds - t) / 1e6, n)
            if let serve { onServe(tr, serve) }
        }
    }

    private func watchSkips(_ tr: ServeTracker) -> ServeTracker {
        guard onSkippedSpan != nil else { return tr }
        tr.onSkipped = { [weak self] f, l, why in
            guard let self else { return }
            let st = Int64(self.step)
            let first = f.int64Value * st, last = l.int64Value * st + st - 1, dec = self.decoded
            let ring = Int64(self.ptsRing.count)
            guard !self.paused, self.skippedClips < 30, dec - first <= ring, first < dec else { return }
            self.skippedClips += 1
            let fromUs = self.ptsRing[Int(first % ring)] - 1, toUs = self.ptsRing[Int(min(last, dec - 1) % ring)] + 1
            self.onSkippedSpan?(self.skippedClips, why, fromUs, toUs)
        }
        return tr
    }

    // ---- a serve --------------------------------------------------------------------------

    private func onServe(_ tr: ServeTracker, _ serve: ServeTracker.Serve) {
        servesFound += 1
        let st = Int64(step)
        var first = serve.first * st, last = serve.last * st + st - 1
        if let why = pose?.veto(first, last) { onNote("not a serve: \(why)"); return }
        if onSpotted != nil {
            if paused { ignoredWhileMeasuring += 1; onNote("a serve while the last one was being measured: ignored"); return }
            paused = true
        }
        // only the frames some track covers, and 0.05 s either side
        let fs = IosCapture.shared.trackFrames(serve: serve, step: Int32(step)).map { $0.int64Value }
        if let lo = fs.min(), let hi = fs.max() {
            let margin = Int64(captureFps / 20)
            first = max(first, lo - margin); last = min(last, hi + margin)
        }
        // at most 300 MB of frames: the run-up trimmed to 0.1 s first, then the end
        do {
            let r = tr.cropFor(s: serve, W: frameW, H: frameH).swift
            let per = Int64(r[2]) * Int64(r[3]) + 2 * Int64(r[2] / 2) * Int64(r[3] / 2)
            let maxN = max(Int64(300_000_000) / max(1, per), 60)
            if last - first + 1 > maxN {
                let flightStart = (serve.track.first.map { Int64($0.get(index: 0)) } ?? serve.first) * st
                first = max(first, flightStart - Int64(captureFps / 10))
                if last - first + 1 > maxN { last = first + maxN - 1 }
                trimmed += 1
            }
        }
        let dec = decoded
        if dec - first > Int64(ptsRing.count) || first >= dec { return }    // too long ago to find again
        let fromUs = ptsRing[Int(first % Int64(ptsRing.count))] - 1
        let toUs = ptsRing[Int(min(last, dec - 1) % Int64(ptsRing.count))] + 1
        let w = frameW, h = frameH
        let rect = tr.cropFor(s: serve, W: w, H: h)
        captures += 1
        let id = captures
        onSpotted?(id)
        let firstFrame = first
        workers.addOperation { [weak self] in self?.writeCapture(id, serve, firstFrame, fromUs, toUs, rect, w, h) }
    }

    /** captures written as windows round the ball (Android's default since 0.10.8) */
    var windowCaptures = true

    private func writeCapture(_ id: Int, _ serve: ServeTracker.Serve, _ firstFrame: Int64, _ fromUs: Int64, _ toUs: Int64,
                              _ rect: KotlinIntArray, _ w: Int32, _ h: Int32) {
        let t0 = nowMs()
        let r = rect.swift
        let per = Int(r[2] * r[3] + 2 * (r[2] / 2) * (r[3] / 2))
        let prog = Writing(per: per)
        Pipeline.writing.set(id, prog)
        defer { prog.finish() }
        // live: the encoder is a few frames behind the camera; wait (up to 1 s) for it to reach the end
        if !blocking { var waited = 0; while store.newestUs < toUs && waited < 1000 { Thread.sleep(forTimeInterval: 0.01); waited += 10 } }
        let frames = store.between(fromUs, toUs)
        guard let fmt = store.format else { return }
        if frames.isEmpty { onError("capture #\(id): its frames were no longer in memory"); return }
        let bin = captureDir.appendingPathComponent("cap_\(id).bin")
        let nGuess = frames.filter { $0.ptsUs >= fromUs && $0.ptsUs <= toUs }.count
        let tracks = IosCapture.shared.tracks(serve: serve, step: Int32(step), firstFrame: firstFrame, w: w)
        let windowed = windowCaptures && !tracks.isEmpty
        writeHead(id, Capture(width: w, height: h, rect: rect, fullRange: false, fps: Int32(captureFps)).header(
            extra: ",\"id\":\(id),\"partial\":true" + (windowed ? ",\"windowed\":true" : "") + optics(), n: Int32(nGuess)))
        onCaptureStarted?(id, nGuess)
        let builder = windowed ? IosCapture.shared.builder(rect: rect, frames: Int32(nGuess + captureFps / 4), tracks: tracks) : nil
        FileManager.default.createFile(atPath: bin.path, contents: nil)
        let out = builder == nil ? try? FileHandle(forWritingTo: bin) : nil
        var cap: Capture?
        var n = 0
        do {
            let dec = try VideoDecoder(format: fmt)
            let sink: (Int64, Yuv) -> Void = { pts, yuv in
                if pts < fromUs || pts > toUs { return }
                let c = cap ?? Capture(width: w, height: h, rect: rect, fullRange: dec.fullRange, fps: Int32(self.captureFps))
                cap = c
                let crop = c.crop(f: yuv)
                if let builder { builder.add(crop: crop) } else { out?.write(crop.data); prog.wrote(n + 1) }
                n += 1
            }
            for f in frames { dec.decode(f, sink) }
            dec.flush(sink)
        } catch { onError("capture #\(id): \(error.localizedDescription)") }
        try? out?.close()
        guard let c = cap else { return }
        if let builder {
            n = Int(builder.count)
            IosCapture.shared.writeWindowed(b: builder, path: bin.path)
        }
        let head = c.header(extra: ",\"id\":\(id),\"fromUs\":\(fromUs),\"toUs\":\(toUs),\"why\":\"\(serve.why)\"" + optics() +
                            (builder?.headerExtra() ?? "") + serve.tracksJson(step: Int32(step), scale: Float(w) / Float(w / 4), f0: firstFrame, n: Int32(n)),
                            n: Int32(n))
        writeHead(id, head)
        prog.finish()
        onCapture?(id, n, head, nowMs() - t0, fromUs, toUs)
    }

    /** the header, swapped in whole (the engine may be reading the old one) */
    private func writeHead(_ id: Int, _ head: String) {
        let tmp = captureDir.appendingPathComponent("cap_\(id).json.tmp")
        let dst = captureDir.appendingPathComponent("cap_\(id).json")
        try? head.write(to: tmp, atomically: false, encoding: .utf8)
        _ = try? FileManager.default.replaceItemAt(dst, withItemAt: tmp)
        if !FileManager.default.fileExists(atPath: dst.path) { try? FileManager.default.moveItem(at: tmp, to: dst) }
    }

    var rejected: [String: Int] {
        guard let tr = tracker else { return [:] }
        var out: [String: Int] = [:]
        for (k, v) in IosTrack.shared.rejected(tr: tr) { out[k as! String] = (v as! NSNumber).intValue }
        return out
    }
}

/** A capture being written: how many whole frames are done, and whether it is finished (LivePipeline.Writing). */
final class Writing {
    let per: Int
    private let cond = NSCondition()
    private(set) var frames = 0
    private(set) var done = false
    init(per: Int) { self.per = per }
    func wrote(_ n: Int) { cond.lock(); if n > frames { frames = n; cond.broadcast() }; cond.unlock() }
    func finish() { cond.lock(); done = true; cond.broadcast(); cond.unlock() }
    /** until [upto] frames are written or the capture is done, or [timeoutMs] passes */
    func waitFor(_ upto: Int, timeoutMs: Int) {
        cond.lock(); defer { cond.unlock() }
        let end = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        while !done && frames < upto { if !cond.wait(until: end) { break } }
    }
    func awaitDone(timeoutMs: Int) { waitFor(Int.max, timeoutMs: timeoutMs) }
}

final class WritingTable {
    private var map: [Int: Writing] = [:]
    private let lock = NSLock()
    func set(_ id: Int, _ w: Writing) { lock.lock(); map[id] = w; lock.unlock() }
    func get(_ id: Int) -> Writing? { lock.lock(); defer { lock.unlock() }; return map[id] }
    func remove(_ id: Int) { lock.lock(); map[id] = nil; lock.unlock() }
    var maxId: Int? { lock.lock(); defer { lock.unlock() }; return map.keys.max() }
}

final class DumpTable {
    private var m: [Int64: String] = [:]
    private let lock = NSLock()
    func set(_ i: Int64, _ s: String) { lock.lock(); m[i] = s; lock.unlock() }
    func take(_ i: Int64) -> String? { lock.lock(); defer { lock.unlock() }; return m.removeValue(forKey: i) }
}
