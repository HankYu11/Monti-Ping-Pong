import Foundation
import WebKit

/**
 * The spin meter engine, run headless in a web view as on Android: engine.html, engine.js and
 * mp4box.js are the Android app's own files, unchanged. Its `Android.onReady/onProgress/onResult`
 * calls reach Swift through a shim, and its capture reads (`${base}/head`, `/head/final`, `/all`,
 * `/frames/k/c`, `/k`) are answered here exactly as MainActivity.captureResponse answers them,
 * waiting for a capture still being cut -- off the web view's thread, never blocking it.
 */
final class Engine: NSObject, WKScriptMessageHandler {
    static let base = "app://local"
    let web: WKWebView
    private let handler: SchemeHandler
    var onReady: (Bool, Bool, Bool) -> Void = { _, _, _ in }
    var onProgress: (Int, String, Double) -> Void = { _, _, _ in }
    var onResult: (Int, String) -> Void = { _, _ in }

    init(captureDir: URL) {
        let cfg = WKWebViewConfiguration()
        handler = SchemeHandler(captureDir: captureDir)
        cfg.setURLSchemeHandler(handler, forURLScheme: "app")
        let shim = """
        window.Android = {
          onReady: (a, b, c) => webkit.messageHandlers.onReady.postMessage([!!a, !!b, !!c]),
          onProgress: (id, step, frac) => webkit.messageHandlers.onProgress.postMessage([id, String(step), +frac || 0]),
          onResult: (id, json) => webkit.messageHandlers.onResult.postMessage([id, json]),
        };
        """
        cfg.userContentController.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: CGRect(x: 0, y: 0, width: 2, height: 2), configuration: cfg)
        super.init()
        for n in ["onReady", "onProgress", "onResult"] { cfg.userContentController.add(self, name: n) }
        if #available(iOS 16.4, *) { web.isInspectable = true }
        web.load(URLRequest(url: URL(string: "\(Engine.base)/engine.html")!))
    }

    func analyseCapture(_ id: Int) {
        web.evaluateJavaScript("analyseCapture('\(Engine.base)/capture/\(id)', \(id))", completionHandler: nil)
    }

    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let a = m.body as? [Any] else { return }
        switch m.name {
        case "onReady": onReady(a[0] as? Bool ?? false, a[1] as? Bool ?? false, a[2] as? Bool ?? false)
        case "onProgress": onProgress((a[0] as? NSNumber)?.intValue ?? 0, a[1] as? String ?? "", (a[2] as? NSNumber)?.doubleValue ?? 0)
        case "onResult": onResult((a[0] as? NSNumber)?.intValue ?? 0, a[1] as? String ?? "{}")
        default: break
        }
    }
}

private final class SchemeHandler: NSObject, WKURLSchemeHandler {
    let captureDir: URL
    private let io = DispatchQueue(label: "engine-io", qos: .userInitiated, attributes: .concurrent)
    private var stopped = Set<ObjectIdentifier>()
    private let lock = NSLock()
    init(captureDir: URL) { self.captureDir = captureDir }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        let path = task.request.url?.path ?? "/"
        let id = ObjectIdentifier(task)
        io.async { [self] in
            var data: Data?, mime = "application/octet-stream"
            do {
                if path.hasPrefix("/capture/") { data = capture(path); mime = path.hasSuffix("/head") || path.hasSuffix("/final") ? "application/json" : mime }
                else {
                    let name = (path as NSString).lastPathComponent
                    if let u = Bundle.main.url(forResource: name, withExtension: nil, subdirectory: "engine") { data = try Data(contentsOf: u) }
                    mime = name.hasSuffix(".html") ? "text/html" : name.hasSuffix(".js") ? "application/javascript" : mime
                }
            } catch { data = nil }
            DispatchQueue.main.async { [self] in
                lock.lock(); let gone = stopped.remove(id) != nil; lock.unlock()
                if gone { return }
                let url = task.request.url!
                guard let data else {
                    task.didReceive(HTTPURLResponse(url: url, statusCode: 404, httpVersion: "HTTP/1.1", headerFields: nil)!)
                    task.didFinish(); return
                }
                task.didReceive(HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                                headerFields: ["Content-Type": mime, "Content-Length": "\(data.count)", "Access-Control-Allow-Origin": "*"])!)
                task.didReceive(data)
                task.didFinish()
            }
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) { lock.lock(); stopped.insert(ObjectIdentifier(task)); lock.unlock() }

    /** MainActivity.captureResponse: /capture/N/(head | head/final | all | frames/k/c | k) */
    private func capture(_ path: String) -> Data? {
        let p = path.split(separator: "/").map(String.init)      // ["capture", N, ...]
        guard p.count >= 3, let id = Int(p[1]) else { return nil }
        let head = captureDir.appendingPathComponent("cap_\(id).json")
        let bin = captureDir.appendingPathComponent("cap_\(id).bin")
        let w = Pipeline.writing.get(id)
        let wait = 20_000
        switch p[2] {
        case "head":
            if p.count >= 4 && p[3] == "final" { w?.awaitDone(timeoutMs: wait) }
            return try? Data(contentsOf: head)
        case "all":
            w?.awaitDone(timeoutMs: wait)
            return try? Data(contentsOf: bin)
        case "frames":
            guard p.count >= 5, let k = Int(p[3]), let c = Int(p[4]), let per = perOf(head) else { return nil }
            w?.waitFor(k + c, timeoutMs: wait)
            return read(bin, from: k * per, frames: c, per: per)
        default:
            guard let k = Int(p[2]), let per = perOf(head) else { return nil }
            w?.waitFor(k + 1, timeoutMs: wait)
            return read(bin, from: k * per, frames: 1, per: per)
        }
    }

    /** whole frames only: at most [frames], as many as are on disk, possibly none */
    private func read(_ bin: URL, from: Int, frames: Int, per: Int) -> Data {
        guard let fh = try? FileHandle(forReadingFrom: bin) else { return Data() }
        defer { try? fh.close() }
        let size = Int((try? fh.seekToEnd()) ?? 0)
        let have = max(0, min(frames, (size - from) / per))
        if have == 0 { return Data() }
        try? fh.seek(toOffset: UInt64(from))
        return (try? fh.read(upToCount: have * per)) ?? Data()
    }

    private func perOf(_ head: URL) -> Int? {
        guard let d = try? Data(contentsOf: head), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let r = j["rect"] as? [Int], r.count == 4 else { return nil }
        return r[2] * r[3] + 2 * (r[2] / 2) * (r[3] / 2)
    }
}
