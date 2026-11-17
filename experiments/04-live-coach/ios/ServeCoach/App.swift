import SwiftUI
import ServeCoachCore

@main
struct ServeCoachApp: App {
    var body: some Scene {
        WindowGroup { HarnessView() }
    }
}

/** For now: a video test run from the launch arguments (`-replay <path>`), logged to the console. */
struct HarnessView: View {
    @StateObject private var m = HarnessModel()
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(m.state).font(.headline)
            ScrollView { Text(m.lines.joined(separator: "\n")).font(.system(size: 10, design: .monospaced)) }
        }.padding().onAppear { m.start() }
    }
}

final class HarnessModel: ObservableObject {
    @Published var state = "starting"
    @Published var lines: [String] = []
    private var engine: Engine?
    private var test: VideoTest?

    func start() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("captures")
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let e = Engine(captureDir: dir)
        let t = VideoTest(engine: e, captureDir: dir)
        engine = e; test = t
        let say: (String, String) -> Void = { k, s in print("SCLOG \(k): \(s)"); DispatchQueue.main.async { self.lines.append("\(k): \(s)") } }
        t.log = say
        t.state = { s in print("SCLOG state: \(s)"); DispatchQueue.main.async { self.state = s } }
        t.onServeResult = { id, r in
            let d = try? JSONSerialization.data(withJSONObject: r, options: [.sortedKeys])
            say("result", "#\(id) \(String(data: d ?? Data(), encoding: .utf8) ?? "")")
        }
        t.onDone = { s, r, g in say("done", "spotted \(s), read \(r), clear \(g)") }
        e.onReady = { codecs, mp4, eng in
            say("engine", "ready: webcodecs \(codecs), mp4box \(mp4), engine \(eng)")
            t.engineReady = eng
            t.next()
        }
        e.onProgress = { _, _, _ in }
        e.onResult = { id, json in t.result(id, json) }
        let args = ProcessInfo.processInfo.arguments
        Pipeline.dumpQ = args.contains("-dumpq")
        if let i = args.firstIndex(of: "-replay"), i + 1 < args.count {
            t.run(URL(fileURLWithPath: args[i + 1]), prime: !args.contains("-noprime"))
        }
    }
}
