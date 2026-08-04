import SwiftUI

@main
struct LiveTypeApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--livetype-e2e") {
                E2EHarnessView()
            } else {
                SettingsView()
            }
            #else
            SettingsView()
            #endif
        }
    }
}
