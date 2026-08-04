import Foundation

/// One spend window as the worker computed it: whole *local* calendar days,
/// including today. `last_7d` is today plus the six days before it, not the last
/// 168 hours.
///
/// `usdMicros` is integer micro-USD and is the money. It is never re-derived
/// from `seconds` on the device: every stored row is billed at the price frozen
/// at its session time, so recomputing would be wrong after any price change.
///
/// Port of `android/.../network/UsageReporter.kt`.
struct UsageWindow: Codable {
    let seconds: Double
    let usdMicros: Int64
    let sessions: Int
}

/// The worker could not use a published price for this model and fell back to
/// OpenAI's own estimate. The UI must say so rather than let the user read
/// fabricated precision as fact.
struct UsagePrice: Codable {
    let usdMicrosPerMinute: Int64
    let unit: String
    let estimated: Bool
}

struct UsageSummary: Codable {
    let model: String
    let price: UsagePrice
    let today: UsageWindow
    let last7d: UsageWindow
    let last30d: UsageWindow
    let source: String
    let asOf: String
}

/// Outcome of `GET /usage`. Every failure mode the settings screen has to tell
/// apart is its own case: "unknown" must never be rendered as "zero".
enum UsageOutcome {
    case loaded(UsageSummary)
    /// 401 — the device secret the worker holds is not the one we sent.
    case unauthorized
    /// Any other non-2xx, carrying the worker's own `error` text if it sent one.
    case workerError(status: Int, detail: String)
    /// The request never got an answer: worker down, no route, no network.
    case unreachable
    /// 2xx whose body is not the shape this app knows how to read.
    case malformed
}

/// The device half of the metering contract. It **reports** and it **renders** —
/// it does not price. The `usage` object OpenAI puts on the transcription event
/// is forwarded byte-for-byte and the worker owns the price table, the model and
/// the aggregation.
struct UsageReporter {
    private struct WindowPayload: Decodable {
        let seconds: Double
        let usd_micros: Int64
        let sessions: Int
    }

    private struct SummaryPayload: Decodable {
        struct Price: Decodable {
            let usd_micros_per_minute: Int64
            let unit: String
            let estimated: Bool
        }
        struct Windows: Decodable {
            let today: WindowPayload
            let last_7d: WindowPayload
            let last_30d: WindowPayload
        }
        let model: String
        let price: Price
        let windows: Windows
        let source: String?
        let as_of: String?
    }

    /// Posts one billable event. Fire-and-forget by design. `item_id` is the
    /// worker's idempotency key, so a retry would be free.
    func report(settings: LiveTypeSettings, itemId: String, usage: [String: Any]) {
        guard settings.isConfigured, !itemId.isEmpty else { return }

        let body: [String: Any] = ["item_id": itemId, "usage": usage]
        guard let url = URL(string: settings.usageEndpoint),
              let payload = try? JSONSerialization.data(withJSONObject: body) else {
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 8
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(settings.deviceSecret, forHTTPHeaderField: "X-Device-Secret")
        request.httpBody = payload

        // Metering must never disturb dictation, so it runs detached and its
        // failure never surfaces.
        Task.detached {
            _ = try? await URLSession.shared.data(for: request)
        }
    }

    /// Reads the spend summary. `tz_offset_minutes` is minutes to *add* to UTC,
    /// which is what decides where the worker puts the local day boundaries.
    func fetchSummary(settings: LiveTypeSettings) async -> UsageOutcome {
        guard let base = URL(string: settings.usageEndpoint) else {
            return .unreachable
        }
        let offsetMinutes = TimeZone.current.secondsFromGMT() / 60
        guard let url = URL(string: base.absoluteString + "?tz_offset_minutes=\(offsetMinutes)") else {
            return .unreachable
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(settings.deviceSecret, forHTTPHeaderField: "X-Device-Secret")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 401:
                return .unauthorized
            case 200..<300:
                return parse(data)
            default:
                return .workerError(status: status, detail: errorDetail(from: data))
            }
        } catch {
            return .unreachable
        }
    }

    private func parse(_ data: Data) -> UsageOutcome {
        guard let payload = try? JSONDecoder().decode(SummaryPayload.self, from: data) else {
            return .malformed
        }
        let summary = UsageSummary(
            model: payload.model,
            price: UsagePrice(
                usdMicrosPerMinute: payload.price.usd_micros_per_minute,
                unit: payload.price.unit,
                estimated: payload.price.estimated
            ),
            today: window(payload.windows.today),
            last7d: window(payload.windows.last_7d),
            last30d: window(payload.windows.last_30d),
            source: payload.source ?? "",
            asOf: payload.as_of ?? ""
        )
        return .loaded(summary)
    }

    private func window(_ payload: WindowPayload) -> UsageWindow {
        UsageWindow(seconds: payload.seconds, usdMicros: payload.usd_micros, sessions: payload.sessions)
    }

    private func errorDetail(from data: Data) -> String {
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let error = json["error"] as? String, !error.isEmpty {
            return error
        }
        return String(String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200) ?? "")
    }
}
