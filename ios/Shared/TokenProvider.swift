import Foundation

/// Fetches an ephemeral client secret from the token Worker.
///
/// Port of `android/.../network/TokenProvider.kt`: same endpoint contract, same
/// `X-Device-Secret` header, same hint-only body. The HTTP stack changes
/// (URLSession instead of `HttpURLConnection`) and it is async instead of
/// blocking, but the wire format is byte-for-byte the same.
struct TokenProvider {
    enum TokenError: LocalizedError {
        case http(status: Int, detail: String)
        case missingSecret

        var errorDescription: String? {
            switch self {
            case .http(let status, let detail):
                return "Token endpoint returned HTTP \(status): \(detail)"
            case .missingSecret:
                return "Token endpoint response did not contain a client secret"
            }
        }
    }

    func fetch(settings: LiveTypeSettings) async throws -> String {
        guard let url = URL(string: settings.tokenEndpoint) else {
            throw TokenError.http(status: 0, detail: "Malformed endpoint URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(settings.deviceSecret, forHTTPHeaderField: "X-Device-Secret")
        request.httpBody = try JSONSerialization.data(withJSONObject: hints(settings))

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0

        guard (200..<300).contains(status) else {
            let detail = Self.errorDetail(from: data)
            throw TokenError.http(status: status, detail: String(detail.prefix(300)))
        }

        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let value = json?["value"] as? String, !value.isEmpty else {
            throw TokenError.missingSecret
        }
        return value
    }

    /// Hints only. The worker picks the transcription model and drops any hint
    /// the chosen model does not accept, so nothing here is authoritative.
    private func hints(_ settings: LiveTypeSettings) -> [String: Any] {
        [
            "languages": settings.languages,
            "prompt": settings.prompt,
            "keywords": settings.keywords,
        ]
    }

    private static func errorDetail(from data: Data) -> String {
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = json["error"] as? String, !error.isEmpty {
            return error
        }
        return String(data: data, encoding: .utf8) ?? ""
    }
}
