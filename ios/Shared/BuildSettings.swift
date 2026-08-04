import Foundation

/// Values the Android build bakes in from `worker/.dev.vars` and
/// `data/keywords.txt` at Gradle configuration time. On iOS there is no build
/// step that reads those files, so the same split is expressed with
/// `#if DEBUG` constants here.
///
/// Keep the same rules as `android/app/build.gradle.kts`:
/// - `OPENAI_API_KEY` is never read into the app.
/// - Release gets literal empty strings, not "whatever was parsed".
/// - These are `AppSettings` **defaults only**; a value the user saved always
///   wins.
enum BuildSettings {
    static var defaultTokenEndpoint: String {
        #if DEBUG
        return bundleString("LiveTypeTokenEndpoint") ?? "http://127.0.0.1:8787/token"
        #else
        return ""
        #endif
    }

    /// Alias for [defaultTokenEndpoint], named as the prod endpoint would be.
    /// The deployed worker URL, when one exists — blank keeps the prod row in
    /// the settings dropdown disabled.
    static var prodTokenEndpoint: String { "" }

    /// Alias for [defaultTokenEndpoint] in the `dev` mode sense: the local
    /// `wrangler dev` endpoint, debug builds only.
    static var devTokenEndpoint: String {
        #if DEBUG
        return "http://127.0.0.1:8787/token"
        #else
        return ""
        #endif
    }

    static var defaultDeviceSecret: String {
        #if DEBUG
        return bundleString("LiveTypeDeviceSecret")
            ?? ProcessInfo.processInfo.environment["LIVETYPE_DEVICE_SECRET"]
            ?? ""
        #else
        return ""
        #endif
    }

    /// The production socket is intentionally the bare Realtime URL. Debug E2E
    /// builds may substitute a local protocol double through the generated
    /// Info.plist; normal debug builds still use OpenAI exactly as production
    /// does.
    static var realtimeWebSocketURL: URL {
        #if DEBUG
        if let value = bundleString("LiveTypeRealtimeURL"),
           let url = URL(string: value),
           let scheme = url.scheme,
           ["ws", "wss"].contains(scheme) {
            return url
        }
        #endif
        return URL(string: "wss://api.openai.com/v1/realtime")!
    }

    private static func bundleString(_ key: String) -> String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: key) as? String,
              !value.isEmpty,
              !value.hasPrefix("$(") else {
            return nil
        }
        return value
    }

    /// Mirrors `data/keywords.txt`. On Android the file is parsed at build time
    /// (debug only); here the list is a literal so it stays out of git as
    /// personal vocabulary, exactly like the plaintext file.
    static var defaultKeywords: String {
        #if DEBUG
        return ""
        #else
        return ""
        #endif
    }

    /// Mirrors `res/values/strings.xml` `default_languages` (locale-resolved on
    /// Android; hard-coded here).
    static var defaultLanguages: String { "ru,en" }

    /// Mirrors `res/values/strings.xml` `default_prompt`.
    static var defaultPrompt: String {
        "Dictation of messages and technical notes. Keep punctuation, " +
        "English product names and the speaker's original language."
    }
}
