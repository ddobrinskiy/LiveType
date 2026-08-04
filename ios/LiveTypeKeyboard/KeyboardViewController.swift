import UIKit

/// The keyboard. iOS twin of `LiveTypeImeService.kt`, but the platform model is
/// different enough that this is a structural port, not a line-by-line one.
///
/// Key differences from the Android IME service:
/// - Subclasses `UIInputViewController`, not `InputMethodService`.
/// - There is no composing text / `commitText` with a cursor range: everything
///   lands via `textDocumentProxy.insertText`, which behaves like a paste into
///   the focused field. This is the biggest functional regression vs. Android
///   and is tracked in `IOS_BUILD.md`.
/// - The keyboard is an extension, so it is its own process/container and needs
///   **full access** (`hasFullAccess`) for networking and the microphone.
final class KeyboardViewController: UIInputViewController, RealtimeTranscriber.Listener {
    private var settings = AppSettings.load()
    private var sessionActive = false
    private var maxRecordingWorkItem: DispatchWorkItem?
    private var pendingTranscript = ""

    private let micButton = UIButton(type: .system)
    private let statusLabel = UILabel()

    private lazy var transcriber: RealtimeTranscriber = RealtimeTranscriber(listener: self)

    private lazy var recorder: PcmAudioRecorder = PcmAudioRecorder(
        onAudio: { [weak self] data in
            self?.transcriber.appendAudio(data)
        },
        onError: { [weak self] message in
            self?.failSession(message)
        }
    )

    override func viewDidLoad() {
        super.viewDidLoad()
        settings = AppSettings.load()
        buildLayout()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshStatus()
    }

    // MARK: - Dictation lifecycle (structural port of LiveTypeImeService)

    private func startDictation() {
        guard !sessionActive else { return }
        guard hasFullAccess else {
            statusLabel.text = "Enable Full Access in Settings"
            return
        }
        guard settings.isConfigured else {
            statusLabel.text = "Not configured — open the LiveType app"
            return
        }

        do {
            try recorder.start()
        } catch {
            statusLabel.text = error.localizedDescription
            return
        }
        sessionActive = true
        pendingTranscript = ""
        statusLabel.text = "Connecting…"

        let workItem = DispatchWorkItem { [weak self] in
            // The recording-limit is a completion, never a cancellation: commit
            // the buffer so nothing the user said is lost (Android semantics).
            self?.commitAndFinish()
        }
        maxRecordingWorkItem = workItem
        DispatchQueue.main.asyncAfter(
            deadline: .now() + settings.maxRecordingMillis,
            execute: workItem
        )

        Task {
            do {
                let secret = try await TokenProvider().fetch(settings: settings)
                transcriber.connect(clientSecret: secret)
            } catch {
                await MainActor.run { self.failSession(error.localizedDescription) }
            }
        }
    }

    private func stopDictation() {
        guard sessionActive else { return }
        commitAndFinish()
    }

    /// Commits the audio buffer, then tears the session down after the final
    /// transcript has had a chance to arrive.
    private func commitAndFinish() {
        transcriber.commit()
        maxRecordingWorkItem?.cancel()
        maxRecordingWorkItem = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            self?.endSession()
        }
    }

    private func endSession() {
        guard sessionActive else { return }
        recorder.stop()
        transcriber.close()
        sessionActive = false
        statusLabel.text = "Done"
    }

    private func failSession(_ message: String) {
        recorder.stop()
        transcriber.close()
        sessionActive = false
        maxRecordingWorkItem?.cancel()
        maxRecordingWorkItem = nil
        statusLabel.text = message
    }

    // MARK: - RealtimeTranscriber.Listener

    func onReady() {
        DispatchQueue.main.async { [weak self] in
            self?.statusLabel.text = "Listening"
        }
    }

    func onTranscriptDelta(itemId: String, delta: String) {
        // iOS has no composing-text API. Buffer provisional fragments so the
        // host field receives one final paste instead of a visible sequence of
        // revisions while the recogniser is still speaking.
        pendingTranscript += delta
    }

    func onTranscriptCompleted(itemId: String, transcript: String, usage: [String: Any]?) {
        let finalText = transcript.isEmpty ? pendingTranscript : transcript
        if !finalText.isEmpty {
            DispatchQueue.main.async { [weak self] in
                self?.textDocumentProxy.insertText(finalText)
            }
        }
        pendingTranscript = ""
        if let usage {
            // Forward verbatim; the worker owns pricing. Fire-and-forget.
            UsageReporter().report(settings: settings, itemId: itemId, usage: usage)
        }
    }

    func onError(message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.failSession(message)
        }
    }

    func onClosed() {}

    // MARK: - Layout

    private func buildLayout() {
        view.backgroundColor = .systemBackground

        let stack = UIStackView(arrangedSubviews: [statusLabel, micButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        micButton.setTitle("🎙", for: .normal)
        micButton.titleLabel?.font = .systemFont(ofSize: 32)
        micButton.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
    }

    private func refreshStatus() {
        if !hasFullAccess {
            statusLabel.text = "Enable Full Access in Settings"
        } else if !settings.isConfigured {
            statusLabel.text = "Not configured — open the LiveType app"
        } else {
            statusLabel.text = sessionActive ? "Listening" : "Ready"
        }
    }

    @objc private func micTapped() {
        if sessionActive {
            stopDictation()
        } else {
            startDictation()
        }
    }
}
