import SwiftUI

/// Debug-only screen used by the headless simulator runner. It exercises the
/// same host-app networking objects as SettingsView and gives screenshot QA a
/// stable, readable result instead of relying on private simulator UI state.
struct E2EHarnessView: View {
    @State private var tokenResult = "Running…"
    @State private var usageResult = "Running…"
    @State private var settingsResult = "Running…"

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("LiveType simulator QA")
                        .font(.largeTitle.bold())
                    Text("Headless protocol and frontend smoke test")
                        .foregroundStyle(.secondary)

                    checkRow("Settings defaults", settingsResult)
                    checkRow("Token provider", tokenResult)
                    checkRow("Usage renderer", usageResult)

                    VStack(spacing: 10) {
                        Text("Keyboard preview")
                            .font(.headline)
                        Text("Ready")
                            .foregroundStyle(.green)
                        Text("🎙")
                            .font(.system(size: 38))
                            .frame(width: 88, height: 58)
                            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                            .accessibilityIdentifier("keyboard-mic-preview")
                        Text("The extension uses the same shared settings and realtime URL.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18))
                }
                .padding(24)
            }
            .navigationTitle("QA")
        }
        .task { await runChecks() }
    }

    private func checkRow(_ title: String, _ result: String) -> some View {
        let identifier = title.lowercased().replacingOccurrences(of: " ", with: "-")
        let passed = result == "PASS" || result.hasPrefix("PASS:")
        return HStack {
            Image(systemName: passed ? "checkmark.circle.fill" : "circle.dotted")
                .foregroundStyle(passed ? .green : .secondary)
            VStack(alignment: .leading) {
                Text(title).font(.headline)
                Text(result).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("check-\(identifier)")
    }

    @MainActor
    private func runChecks() async {
        let settings = AppSettings.load()
        settingsResult = settings.isConfigured ? "PASS" : "FAIL: configure defaults"
        guard settings.isConfigured else {
            tokenResult = "SKIP"
            usageResult = "SKIP"
            return
        }

        do {
            _ = try await TokenProvider().fetch(settings: settings)
            tokenResult = "PASS"
        } catch {
            tokenResult = "FAIL: \(error.localizedDescription)"
        }

        switch await UsageReporter().fetchSummary(settings: settings) {
        case .loaded(let summary):
            usageResult = "PASS: \(MoneyFormat.usd(usdMicros: summary.today.usdMicros)) today"
        case .unauthorized:
            usageResult = "FAIL: unauthorized"
        case .workerError(let status, let detail):
            usageResult = "FAIL: HTTP \(status) \(detail)"
        case .unreachable:
            usageResult = "FAIL: unreachable"
        case .malformed:
            usageResult = "FAIL: malformed response"
        }
    }
}
