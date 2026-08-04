import Foundation

/// Which worker the app talks to, chosen from a dropdown in debug builds.
///
/// Release builds have no such control: they always behave like [custom], with
/// the user typing their deployed worker's URL by hand. The dropdown exists so
/// that during development you can flip between the laptop and a deployed
/// worker without retyping a URL.
///
/// Port of `android/.../config/EndpointMode.kt`, 1:1 except for the build
/// injection: Android reads `worker/.dev.vars` at Gradle configuration time;
/// on iOS the equivalent goes into `BuildSettings` / `#if DEBUG` constants so
/// nothing is baked by a build step.
enum EndpointMode: String, CaseIterable, Hashable {
    /// A deployed Cloudflare Worker. No URL exists yet — see [isAvailable].
    case prod

    /// `wrangler dev` on the laptop, reached over a local network or the
    /// Simulator. Android reaches it over `adb reverse`; iOS has no USB
    /// reverse tunnel, so the simulator uses `127.0.0.1` directly.
    case dev

    /// Anything else; the URL is typed by hand.
    case custom

    /// Whether this mode can currently be selected. `prod` is listed but not
    /// selectable until the worker is actually deployed and [prodEndpoint] is
    /// filled in — showing it greyed out is more honest than hiding it.
    var isAvailable: Bool {
        if self != .prod { return true }
        return Self.prodEndpoint.isEmpty == false
    }

    /// Deployed worker URL. Blank unless configured; blank is what keeps the
    /// prod row in the settings dropdown disabled.
    static var prodEndpoint: String {
        #if DEBUG
        return BuildSettings.prodTokenEndpoint
        #else
        return ""
        #endif
    }

    /// Only debug builds bake in a local endpoint; release leaves it blank.
    static var devEndpoint: String {
        #if DEBUG
        return BuildSettings.devTokenEndpoint
        #else
        return ""
        #endif
    }

    /// The dropdown is a debug-only affordance.
    static var isSelectable: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// A stored mode that is no longer usable falls back to [defaultMode].
    static func from(_ name: String?) -> EndpointMode {
        guard let name else { return defaultMode }
        return allCases.first { $0.rawValue == name && $0.isAvailable } ?? defaultMode
    }

    /// Debug builds start on DEV because that is what their baked endpoint
    /// points at; release builds have nothing baked in, so CUSTOM is the only
    /// mode that makes sense.
    static var defaultMode: EndpointMode {
        #if DEBUG
        return .dev
        #else
        return .custom
        #endif
    }

    /// The URL a mode implies, or nil when the user supplies it.
    static func endpoint(for mode: EndpointMode) -> String? {
        switch mode {
        case .prod: return prodEndpoint.isEmpty ? nil : prodEndpoint
        case .dev: return devEndpoint.isEmpty ? nil : devEndpoint
        case .custom: return nil
        }
    }
}
