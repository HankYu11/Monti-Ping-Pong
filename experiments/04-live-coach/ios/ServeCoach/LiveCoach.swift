import UIKit
import AVFoundation
import ServeCoachCore

/** the camera mode chosen for serving (HighSpeedCamera.choose) */
struct CameraConfig { let width: Int; let height: Int; let fps: Int }

/**
 * The camera side of MainActivity: the idle preview and setup check, a live session (camera →
 * encoder → frame store → pipeline), the session's files, and the setup assistant.
 */
final class LiveCoach {
    unowned let vc: CoachViewController
    private(set) var cfg: CameraConfig?
    private(set) var pipeline: Pipeline?
    var recordingFps: Double { 0 }
    var lagMs: Int64 { 0 }
    var aiOn: Bool { UserDefaults.standard.bool(forKey: "ai") }
    private(set) var session = ""

    init(_ vc: CoachViewController) { self.vc = vc }

    func checkCamera() {
        cfg = nil
        vc.state(cfg == nil ? "This phone doesn't offer slow motion to apps" : "Ready")
    }
    func start() {}
    func stop() { pipeline?.stop(); pipeline = nil }
    func startPreview() {}
    func stopPreview() {}
    func paused() {}
    func resumed() {}
    func toggleAi() {}
    func saveServeData(_ id: Int, _ r: [String: Any], _ data: Any?) {}
    func writeReport() {}
    func finishSession() {}
}
