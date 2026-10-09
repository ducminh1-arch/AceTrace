import SwiftUI

@main
struct AceTraceApp: App {
    var body: some Scene {
        WindowGroup {
            EditorView(initialProject: Project.createDemoProject(orientation: "vertical"))
                .preferredColorScheme(.dark)
        }
    }
}
