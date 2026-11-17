import SwiftUI
import ServeCoachCore

@main
struct ServeCoachApp: App {
    var body: some Scene {
        WindowGroup { Text("Serve Coach — core \(ServeTracker(w: 480, h: 270, fps: 120).description.isEmpty ? "" : "ready")") }
    }
}
