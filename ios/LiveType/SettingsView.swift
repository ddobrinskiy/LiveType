import AVFoundation
import SwiftUI
import UIKit

/// The host-app settings screen. The fields intentionally mirror Android's
/// MainActivity so a setting has the same meaning on both platforms.
struct SettingsView: View {
    @State private var settings = AppSettings.load()
    @State private var usage: UsageOutcome?
    @State private var usageLoading = false
    @State private var validationMessage: String?
    @State private var microphonePermission = AVAudioSession.sharedInstance().recordPermission

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Label(
                            microphonePermission == .granted ? "Microphone access granted" : "Microphone access needed",
                            systemImage: microphonePermission == .granted ? "checkmark.circle.fill" : "mic.slash",
                        )
                        .foregroundStyle(microphonePermission == .granted ? .green : .secondary)
                        Spacer()
                        if microphonePermission != .granted {
                            Button("Allow") { requestMicrophone() }
                                .buttonStyle(.bordered)
                        }
                    }

                    Button("Enable LiveType in Settings") { openKeyboardSettings() }
                        .accessibilityIdentifier("enable-livetype")
                } header: {
                    Text("Setup")
                } footer: {
                    Text("LiveType needs microphone permission and Full Access before the keyboard can connect to the Worker.")
                }

                Section {
                    if EndpointMode.isSelectable {
                        Picker("Which worker", selection: endpointModeBinding) {
                            ForEach(EndpointMode.allCases, id: \.rawValue) { mode in
                                Text(endpointLabel(mode))
                                    .tag(mode)
                                    .disabled(!mode.isAvailable)
                            }
                        }
                        .accessibilityIdentifier("endpoint-mode")

                        Text("Picking a worker saves it immediately. A URL typed by hand still needs Save.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }

                    TextField("Worker token endpoint", text: $settings.tokenEndpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .disabled(settings.endpointMode != .custom)
                        .accessibilityIdentifier("token-endpoint")

                    SecureField("Device secret", text: $settings.deviceSecret)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("device-secret")
                } header: {
                    Text("Connection")
                } footer: {
                    Text("Do not enter your OpenAI API key here. It stays in the Cloudflare Worker.")
                }

                Section {
                    TextField("Expected languages, comma separated", text: bindingForLanguages)
                        .accessibilityIdentifier("languages")

                    TextField("Context", text: $settings.prompt, axis: .vertical)
                        .lineLimit(3...6)
                        .accessibilityIdentifier("prompt")

                    TextField("Terms, one per line", text: bindingForKeywords, axis: .vertical)
                        .lineLimit(4...8)
                        .accessibilityIdentifier("keywords")

                    Picker("Stop recording automatically after", selection: $settings.maxRecordingMinutes) {
                        ForEach(RecordingLimit.options, id: \.self) { minutes in
                            Text(minutes == 1 ? "1 minute" : "\(minutes) minutes").tag(minutes)
                        }
                    }
                    .accessibilityIdentifier("recording-limit")

                    Text("OpenAI charges for every recorded second. The phrase is still finished normally when the limit is reached.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("Recognition")
                }

                Section {
                    if let validationMessage {
                        Text(validationMessage)
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("validation-message")
                    }

                    Button("Save settings") { save() }
                        .accessibilityIdentifier("save-settings")

                    Button {
                        Task { await loadUsage() }
                    } label: {
                        if usageLoading {
                            HStack {
                                Text("Loading spending…")
                                Spacer()
                                ProgressView()
                            }
                        } else {
                            Text("Refresh spending")
                        }
                    }
                    .disabled(usageLoading || !settings.isConfigured)
                    .accessibilityIdentifier("refresh-usage")
                }

                Section("Spending") {
                    usageView
                }
            }
            .navigationTitle("LiveType")
            .task {
                refreshPermission()
                if settings.isConfigured, usage == nil {
                    await loadUsage()
                }
            }
        }
    }

    private var bindingForLanguages: Binding<String> {
        Binding(
            get: { settings.languages.joined(separator: ",") },
            set: {
                settings.languages = $0
                    .split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
            },
        )
    }

    private var bindingForKeywords: Binding<String> {
        Binding(
            get: { settings.keywords.joined(separator: "\n") },
            set: {
                settings.keywords = $0
                    .split(whereSeparator: \.isNewline)
                    .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
                    .reduce(into: [String]()) { result, term in
                        if !result.contains(term) { result.append(term) }
                    }
            },
        )
    }

    private var endpointModeBinding: Binding<EndpointMode> {
        Binding(
            get: { settings.endpointMode },
            set: { selectEndpointMode($0) },
        )
    }

    private func selectEndpointMode(_ mode: EndpointMode) {
        guard mode.isAvailable else { return }

        let oldMode = settings.endpointMode
        if oldMode == .custom, mode != .custom {
            settings.customEndpoint = settings.tokenEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        }

        settings.endpointMode = mode
        if let endpoint = EndpointMode.endpoint(for: mode) {
            settings.tokenEndpoint = endpoint
        } else if mode == .custom {
            settings.tokenEndpoint = settings.customEndpoint
        }

        AppSettings.saveEndpointSelection(
            mode: mode,
            endpoint: settings.tokenEndpoint,
            customEndpoint: settings.customEndpoint,
        )
        validationMessage = nil
    }

    private func endpointLabel(_ mode: EndpointMode) -> String {
        switch mode {
        case .prod:
            return mode.isAvailable ? "Production worker" : "Production worker — not deployed yet"
        case .dev:
            return "Local worker / simulator"
        case .custom:
            return "Custom URL"
        }
    }

    private func save() {
        let endpoint = settings.tokenEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard isAllowedTokenEndpoint(endpoint) else {
            validationMessage = "An HTTPS or debug HTTP token endpoint is required."
            return
        }
        guard settings.deviceSecret.count >= 16 else {
            validationMessage = "Use a random device secret of at least 16 characters."
            return
        }
        guard !settings.languages.isEmpty else {
            validationMessage = "Specify at least one expected language."
            return
        }

        settings.tokenEndpoint = endpoint
        settings.prompt = settings.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.maxRecordingMinutes = RecordingLimit.from(settings.maxRecordingMinutes)
        AppSettings.save(settings)
        settings = AppSettings.load()
        validationMessage = "Settings saved"
        Task { await loadUsage() }
    }

    private func requestMicrophone() {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                microphonePermission = granted ? .granted : .denied
            }
        }
    }

    private func refreshPermission() {
        microphonePermission = AVAudioSession.sharedInstance().recordPermission
    }

    private func openKeyboardSettings() {
        let candidates = [
            URL(string: "App-prefs:root=General&path=Keyboard/Keyboards"),
            URL(string: UIApplication.openSettingsURLString),
        ]
        if let url = candidates.compactMap({ $0 }).first {
            UIApplication.shared.open(url)
        }
    }

    @MainActor
    private func loadUsage() async {
        guard settings.isConfigured else {
            usage = .workerError(status: 0, detail: "Configure the Worker endpoint and device secret first")
            return
        }
        usageLoading = true
        usage = await UsageReporter().fetchSummary(settings: settings)
        usageLoading = false
    }

    @ViewBuilder
    private var usageView: some View {
        switch usage {
        case .none:
            Text(settings.isConfigured ? "No spending data loaded yet." : "Configure the Worker above to see spending.")
                .foregroundStyle(.secondary)
        case .some(.loaded(let summary)):
            LabeledContent("Model", value: summary.model)
            LabeledContent("Price", value: priceText(summary.price))
            if summary.price.estimated {
                Text("This price is an estimate from OpenAI. Amounts below are approximate.")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }
            usageRow("Today", summary.today)
            usageRow("Last 7 days, today included", summary.last7d)
            usageRow("Last 30 days, today included", summary.last30d)
            if !summary.source.isEmpty {
                Text("Source: \(summary.source)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            if !summary.asOf.isEmpty {
                Text("As of \(summary.asOf)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text("Counts only dictation this app reported. This is a meter, not an OpenAI invoice.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .some(.unauthorized):
            Text("The Worker rejected the device secret (HTTP 401).")
                .foregroundStyle(.red)
        case .some(.workerError(let status, let detail)):
            Text("Worker error \(status): \(detail)")
                .foregroundStyle(.red)
        case .some(.unreachable):
            Text("Could not reach the Worker. Check that it is running and reachable.")
                .foregroundStyle(.red)
        case .some(.malformed):
            Text("The Worker response could not be read.")
                .foregroundStyle(.red)
        }
    }

    private func priceText(_ price: UsagePrice) -> String {
        let perMinute = MoneyFormat.usd(usdMicros: price.usdMicrosPerMinute)
        return "\(perMinute) per minute\(price.estimated ? " (estimated)" : "")"
    }

    private func usageRow(_ title: String, _ window: UsageWindow) -> some View {
        LabeledContent(title) {
            VStack(alignment: .trailing) {
                Text(MoneyFormat.usd(usdMicros: window.usdMicros))
                Text("\(window.sessions) sessions · \(Int(window.seconds)) seconds")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
