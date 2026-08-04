import Foundation

/// Whether [endpoint] may be used for the token request.
///
/// Release builds require HTTPS: the request carries DEVICE_SECRET in the
/// `X-Device-Secret` header, and cleartext would put it on the wire. Debug
/// builds additionally accept `http://` so the app can talk to a local
/// `wrangler dev` (`http://127.0.0.1:8787/token`).
///
/// iOS enforces the same split at the platform level via ATS (App Transport
/// Security). The debug fallback below is the app-side mirror of the Android
/// `network_security_config` debug override; release builds must never allow
/// cleartext, exactly like Android.
func isAllowedTokenEndpoint(_ endpoint: String) -> Bool {
    if endpoint.hasPrefix("https://") { return true }
    #if DEBUG
    if endpoint.hasPrefix("http://") { return true }
    #endif
    return false
}

struct LiveTypeSettings {
    var tokenEndpoint: String
    var deviceSecret: String
    var languages: [String]
    var prompt: String
    var keywords: [String]
    var returnToPreviousKeyboard: Bool
    /// Which worker this endpoint came from. Debug-only affordance; see EndpointMode.
    var endpointMode: EndpointMode
    /// The last URL the user supplied by hand, remembered even while a derived
    /// mode owns [tokenEndpoint]. See the Android KDoc for the full rationale.
    var customEndpoint: String
    /// Recording stops itself after this many minutes. See RecordingLimit.
    var maxRecordingMinutes: Int

    var isConfigured: Bool {
        isAllowedTokenEndpoint(tokenEndpoint) && !deviceSecret.isEmpty
    }

    /// [maxRecordingMinutes] as a `asyncAfter` delay.
    var maxRecordingMillis: TimeInterval {
        RecordingLimit.millis(for: maxRecordingMinutes)
    }

    /// `/usage` on the same worker as [tokenEndpoint]. Only one URL is ever
    /// configured on the device, and both routes take the same
    /// `X-Device-Secret`, so the metering endpoint is derived rather than typed
    /// a second time and left to drift.
    var usageEndpoint: String {
        let tokenPath = "/token"
        let usagePath = "/usage"
        if tokenEndpoint.hasSuffix(tokenPath) {
            return String(tokenEndpoint.dropLast(tokenPath.count)) + usagePath
        }
        return tokenEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + usagePath
    }
}

/// AppSettings over `UserDefaults`, the iOS twin of `SharedPreferences`.
///
/// One deliberate difference from Android: the keyboard extension runs in its
/// own sandboxed container. For the keyboard and the host app to see the same
/// settings you must configure an **App Group** and share the `UserDefaults`
/// suite through it (see `PORTING.md`). Until then, `AppSettings` in the
/// extension reads the extension's own (empty) store.
enum AppSettings {
    private enum Key {
        static let suite = "group.dev.dobrinskiy.livetype"
        static let tokenEndpoint = "token_endpoint"
        static let deviceSecret = "device_secret"
        static let languages = "languages"
        static let prompt = "prompt"
        static let keywords = "keywords"
        static let returnToPrevious = "return_to_previous"
        static let endpointMode = "endpoint_mode"
        static let customEndpoint = "custom_endpoint"
        static let maxRecordingMinutes = "max_recording_minutes"
    }

    private static var defaults: UserDefaults {
        UserDefaults(suiteName: Key.suite) ?? .standard
    }

    /// Seeded so the first run matches the device locale and the baked-in
    /// vocabulary. Only defaults: a value the user saved always wins.
    static func load() -> LiveTypeSettings {
        let stored = defaults
        return LiveTypeSettings(
            tokenEndpoint: (stored.string(forKey: Key.tokenEndpoint)
                ?? BuildSettings.defaultTokenEndpoint).trimmed,
            deviceSecret: stored.string(forKey: Key.deviceSecret)
                ?? BuildSettings.defaultDeviceSecret,
            languages: (stored.string(forKey: Key.languages) ?? BuildSettings.defaultLanguages)
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty },
            prompt: (stored.string(forKey: Key.prompt) ?? BuildSettings.defaultPrompt).trimmed,
            keywords: (stored.string(forKey: Key.keywords) ?? BuildSettings.defaultKeywords)
                .split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty },
            returnToPreviousKeyboard: stored.object(forKey: Key.returnToPrevious) as? Bool ?? true,
            endpointMode: EndpointMode.from(stored.string(forKey: Key.endpointMode)),
            customEndpoint: (stored.string(forKey: Key.customEndpoint) ?? "").trimmed,
            maxRecordingMinutes: RecordingLimit.from(
                stored.object(forKey: Key.maxRecordingMinutes) as? Int ?? RecordingLimit.defaultMinutes
            )
        )
    }

    static func save(_ settings: LiveTypeSettings) {
        defaults.set(settings.tokenEndpoint.trimmed, forKey: Key.tokenEndpoint)
        defaults.set(settings.deviceSecret, forKey: Key.deviceSecret)
        defaults.set(settings.languages.joined(separator: ","), forKey: Key.languages)
        defaults.set(settings.prompt.trimmed, forKey: Key.prompt)
        defaults.set(settings.keywords.joined(separator: "\n"), forKey: Key.keywords)
        defaults.set(settings.returnToPreviousKeyboard, forKey: Key.returnToPrevious)
        defaults.set(settings.endpointMode.rawValue, forKey: Key.endpointMode)
        defaults.set(settings.customEndpoint.trimmed, forKey: Key.customEndpoint)
        defaults.set(RecordingLimit.from(settings.maxRecordingMinutes), forKey: Key.maxRecordingMinutes)
    }

    /// Stores the endpoint mode the moment it is picked, together with the URL
    /// that mode implies. See the Android KDoc for why the Save button is not
    /// involved.
    static func saveEndpointSelection(mode: EndpointMode, endpoint: String, customEndpoint: String) {
        defaults.set(mode.rawValue, forKey: Key.endpointMode)
        defaults.set(endpoint.trimmed, forKey: Key.tokenEndpoint)
        defaults.set(customEndpoint.trimmed, forKey: Key.customEndpoint)
    }
}

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
