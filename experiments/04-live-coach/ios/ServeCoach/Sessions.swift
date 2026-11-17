import UIKit

/**
 * Session folders (MainActivity: Download/ServeCoach/Session_yyyyMMdd_HHmmss). On iOS:
 * Documents/ServeCoach/Session_…, visible in the Files app, kept out of backups, and deleted
 * 30 days after their newest file.
 */
enum Sessions {
    static let KEEP_DAYS = 30
    static var root: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("ServeCoach")
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        var u = d; var v = URLResourceValues(); v.isExcludedFromBackup = true; try? u.setResourceValues(v)
        return d
    }
    static func newName() -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.dateFormat = "yyyyMMdd_HHmmss"
        return "Session_" + f.string(from: Date())
    }
    static func folder(_ name: String) -> URL {
        let u = root.appendingPathComponent(name)
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    /** at launch: every session folder whose newest file is older than 30 days */
    static func prune(except current: String = "") {
        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default
            let cutoff = Date().addingTimeInterval(-Double(KEEP_DAYS) * 24 * 3600)
            guard let dirs = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
            for d in dirs where d.lastPathComponent.hasPrefix("Session_") && d.lastPathComponent != current {
                let files = (try? fm.contentsOfDirectory(at: d, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
                let newest = files.compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }.max() ?? .distantPast
                if newest < cutoff { try? fm.removeItem(at: d) }
            }
        }
    }
    /** the first capture number a new pipeline may use: past any still being written */
    static func firstFreeCaptureId() -> Int { (Pipeline.writing.maxId ?? 0) + 1 }
}

/** report.json (MainActivity.writeReport): the device, the camera, the counters, this session's events and results */
final class Report {
    var camera: Any = NSNull()
    var chosen: Any = NSNull()
    var engine: [String: Any] = [:]
    var extra: [String: Any] = [:]           // watching, pose, ... filled at Stop
    private(set) var events: [[String: Any]] = []
    var results: [[String: Any]] = []
    /** where this session's events and results start */
    var eventsFrom = 0, resultsFrom = 0, eventsEnd = 0
    private let writer = DispatchQueue(label: "report", qos: .background)

    func event(_ kind: String, _ text: String) { events.append(["t": nowMs(), "kind": kind, "text": text]) }

    private func device() -> [String: Any] {
        var u = utsname(); uname(&u)
        let model = withUnsafeBytes(of: &u.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
        return ["maker": "Apple", "model": model, "ios": UIDevice.current.systemVersion]
    }

    func json(allSessions: Bool) -> [String: Any] {
        var r: [String: Any] = ["device": device(),
                                "app": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
                                "camera": camera, "chosen": chosen, "engine": engine]
        for (k, v) in extra { r[k] = v }
        r["events"] = Array(events[min(eventsFrom, events.count)...])
        r["results"] = allSessions ? results : Array(results[min(resultsFrom, results.count)...])
        return r
    }
    func text(allSessions: Bool) -> String {
        guard let d = try? JSONSerialization.data(withJSONObject: json(allSessions: allSessions), options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed]) else { return "{}" }
        return String(decoding: d, as: UTF8.self)
    }
    /** this session's report.json, rewritten whole, on a low-priority queue, one at a time */
    func write(to folder: URL, then: (() -> Void)? = nil, failed: @escaping (String) -> Void) {
        let t = text(allSessions: false)
        writer.async {
            do { try Data(t.utf8).write(to: folder.appendingPathComponent("report.json")) }
            catch { DispatchQueue.main.async { failed("report: \(error.localizedDescription)") } }
            then?()
        }
    }
}
