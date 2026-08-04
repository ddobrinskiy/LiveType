import Foundation

/// A transcription WebSocket session against the OpenAI Realtime API.
///
/// Port of `android/.../network/RealtimeTranscriber.kt`: identical wire
/// protocol, identical `session.update` (which deliberately omits the
/// `transcription` block — see the KDoc below), and the same `Listener`
/// surface. OkHttp's `WebSocket` becomes `URLSessionWebSocketTask`; the event
/// handling is a straight port.
final class RealtimeTranscriber: NSObject, URLSessionWebSocketDelegate {
    protocol Listener: AnyObject {
        func onReady()
        func onTranscriptDelta(itemId: String, delta: String)

        /// `usage` is the event's usage object exactly as OpenAI sent it —
        /// `{"type":"duration","seconds":3}`, or the `"tokens"` shape — or nil
        /// if the event carried none. Nothing reads inside it: it is the
        /// billable quantity, and the worker is what prices it.
        func onTranscriptCompleted(itemId: String, transcript: String, usage: [String: Any]?)
        func onError(message: String)
        func onClosed()
    }

    private weak var listener: Listener?
    private var task: URLSessionWebSocketTask?
    private var closing = false
    private var keepAliveTimer: DispatchSourceTimer?
    private let queue = DispatchQueue(label: "dev.dobrinskiy.livetype.transcriber")

    /// Persisted session that keeps the ping alive; the task is recreated per
    /// connection.
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 15
        config.timeoutIntervalForResource = TimeInterval.greatestFiniteMagnitude
        config.waitsForConnectivity = false
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    init(listener: Listener?) {
        self.listener = listener
        super.init()
    }

    func connect(clientSecret: String) {
        closing = false
        keepAliveTimer?.cancel()
        keepAliveTimer = nil

        var request = URLRequest(url: BuildSettings.realtimeWebSocketURL)
        request.setValue("Bearer \(clientSecret)", forHTTPHeaderField: "Authorization")

        let task = session.webSocketTask(with: request)
        self.task = task
        task.resume()
        _ = send(sessionUpdate())
        startKeepAlive(for: task)
        receiveLoop(task)
    }

    @discardableResult
    func appendAudio(_ bytes: Data) -> Bool {
        let encoded = bytes.base64EncodedString()
        return send(["type": "input_audio_buffer.append", "audio": encoded])
    }

    @discardableResult
    func commit() -> Bool {
        send(["type": "input_audio_buffer.commit"])
    }

    @discardableResult
    func clear() -> Bool {
        send(["type": "input_audio_buffer.clear"])
    }

    func close() {
        guard !closing else { return }
        closing = true
        keepAliveTimer?.cancel()
        keepAliveTimer = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func startKeepAlive(for task: URLSessionWebSocketTask) {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 20, repeating: 20)
        timer.setEventHandler { [weak self, weak task] in
            guard let self, let task, !self.closing else { return }
            task.sendPing { error in
                if let error, !self.closing {
                    self.listener?.onError(message: error.localizedDescription)
                }
            }
        }
        keepAliveTimer = timer
        timer.resume()
    }

    private func send(_ event: [String: Any]) -> Bool {
        guard let task, let json = try? JSONSerialization.data(withJSONObject: event) else {
            return false
        }
        task.send(.string(String(data: json, encoding: .utf8)!)) { _ in }
        return true
    }

    private func receiveLoop(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                if !self.closing {
                    self.listener?.onError(message: error.localizedDescription)
                }
            case .success(let message):
                switch message {
                case .string(let text):
                    self.handleEvent(text)
                case .data(let data):
                    self.handleEvent(String(data: data, encoding: .utf8) ?? "")
                @unknown default:
                    break
                }
                if !self.closing { self.receiveLoop(task) }
            }
        }
    }

    private func handleEvent(_ text: String) {
        guard let data = text.data(using: .utf8),
              let event = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            listener?.onError(message: "OpenAI returned malformed JSON")
            return
        }

        switch event["type"] as? String {
        case "session.updated":
            listener?.onReady()

        case "conversation.item.input_audio_transcription.delta":
            if let delta = event["delta"] as? String, !delta.isEmpty {
                listener?.onTranscriptDelta(
                    itemId: (event["item_id"] as? String) ?? "",
                    delta: delta
                )
            }

        case "conversation.item.input_audio_transcription.completed":
            // The same event carries what OpenAI bills for. Pass it up
            // untouched; dropping it would make the spend unknowable.
            listener?.onTranscriptCompleted(
                itemId: (event["item_id"] as? String) ?? "",
                transcript: (event["transcript"] as? String) ?? "",
                usage: event["usage"] as? [String: Any]
            )

        case "error":
            let error = event["error"] as? [String: Any]
            listener?.onError(
                message: (error?["message"] as? String).flatMap { !$0.isEmpty ? $0 : nil }
                    ?? "OpenAI Realtime returned an error"
            )

        default:
            break
        }
    }

    /// Deliberately omits the `transcription` block. The model, languages,
    /// prompt and keywords all come from the ephemeral token minted by the
    /// worker; OpenAI makes `model` mandatory whenever `transcription` is
    /// present, so sending it here would let the device override the worker's
    /// choice. Omitting the block leaves the token's config intact — this
    /// update only re-asserts the audio format and yields `session.updated`,
    /// which the service uses as its ready signal.
    private func sessionUpdate() -> [String: Any] {
        [
            "type": "session.update",
            "session": [
                "type": "transcription",
                "audio": [
                    "input": [
                        "format": [
                            "type": "audio/pcm",
                            "rate": PcmAudioRecorder.sampleRate,
                        ],
                        "noise_reduction": ["type": "near_field"],
                        "turn_detection": NSNull(),
                    ]
                ],
            ],
        ]
    }

    // MARK: - URLSessionWebSocketDelegate

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        listener?.onClosed()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        if let error, !closing {
            listener?.onError(message: error.localizedDescription)
        }
    }
}
