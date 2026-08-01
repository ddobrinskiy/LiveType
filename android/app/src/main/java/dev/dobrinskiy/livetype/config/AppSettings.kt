package dev.dobrinskiy.livetype.config

import android.content.Context
import dev.dobrinskiy.livetype.BuildConfig
import dev.dobrinskiy.livetype.R

/**
 * Whether [endpoint] may be used for the token request.
 *
 * Release builds require HTTPS: the request carries `DEVICE_SECRET` in the
 * `X-Device-Secret` header, and cleartext would put it on the wire. Debug
 * builds additionally accept `http://` so the app can talk to a local
 * `wrangler dev` through `adb reverse` (`http://127.0.0.1:8787/token`).
 *
 * The network security config enforces the same split at the platform level —
 * see `res/xml/network_security_config.xml` and its `src/debug` override, which
 * permits cleartext for loopback addresses only.
 */
fun isAllowedTokenEndpoint(endpoint: String): Boolean =
    endpoint.startsWith("https://") || (BuildConfig.DEBUG && endpoint.startsWith("http://"))

data class LiveTypeSettings(
    val tokenEndpoint: String,
    val deviceSecret: String,
    val languages: List<String>,
    val prompt: String,
    val keywords: List<String>,
    val returnToPreviousKeyboard: Boolean,
    /** Which worker this endpoint came from. Debug-only affordance; see [EndpointMode]. */
    val endpointMode: EndpointMode = EndpointMode.default(),
    /**
     * The last URL the user supplied by hand, remembered even while a derived
     * mode owns [tokenEndpoint].
     *
     * Not used for anything: the keyboard reads [tokenEndpoint] and nothing
     * else. It exists because picking `DEV` now overwrites the stored endpoint
     * immediately, and without somewhere else to keep it the hand-typed URL
     * would be destroyed by a round trip through another mode. Debug-only in
     * practice, like [endpointMode].
     */
    val customEndpoint: String = "",
    /**
     * Recording stops itself after this many minutes. Unlike [endpointMode]
     * this is not a development affordance — every build shows the dropdown,
     * because every user can forget to tap stop. See [RecordingLimit].
     */
    val maxRecordingMinutes: Int = RecordingLimit.default(),
) {
    val isConfigured: Boolean
        get() = isAllowedTokenEndpoint(tokenEndpoint) && deviceSecret.isNotBlank()

    /** [maxRecordingMinutes] as a `postDelayed` delay. */
    val maxRecordingMillis: Long
        get() = RecordingLimit.millisFor(maxRecordingMinutes)

    /**
     * `/usage` on the same worker as [tokenEndpoint]. Only one URL is ever
     * configured on the device, and both routes take the same
     * `X-Device-Secret`, so the metering endpoint is derived rather than typed
     * a second time and left to drift.
     */
    val usageEndpoint: String
        get() = if (tokenEndpoint.endsWith(TOKEN_PATH)) {
            tokenEndpoint.dropLast(TOKEN_PATH.length) + USAGE_PATH
        } else {
            tokenEndpoint.trimEnd('/') + USAGE_PATH
        }

    private companion object {
        const val TOKEN_PATH = "/token"
        const val USAGE_PATH = "/usage"
    }
}

object AppSettings {
    private const val PREFERENCES = "livetype_settings"
    private const val TOKEN_ENDPOINT = "token_endpoint"
    private const val DEVICE_SECRET = "device_secret"
    private const val LANGUAGES = "languages"
    private const val PROMPT = "prompt"
    private const val KEYWORDS = "keywords"
    private const val RETURN_TO_PREVIOUS = "return_to_previous"
    private const val ENDPOINT_MODE = "endpoint_mode"
    private const val CUSTOM_ENDPOINT = "custom_endpoint"
    private const val MAX_RECORDING_MINUTES = "max_recording_minutes"

    // Seeded from resources so the first run matches the device locale.
    fun load(context: Context): LiveTypeSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val defaultLanguages = context.getString(R.string.default_languages)
        val defaultPrompt = context.getString(R.string.default_prompt)
        // Debug builds bake in data/keywords.txt (the version-controlled
        // vocabulary list); release builds get "" and fall back to resources.
        val defaultKeywords = BuildConfig.DEFAULT_KEYWORDS.ifBlank {
            context.getString(R.string.default_keywords)
        }
        return LiveTypeSettings(
            // BuildConfig defaults are the debug build's baked-in dev credentials
            // (empty in release). Only a default: anything the user saved wins,
            // including an explicitly cleared value.
            tokenEndpoint = preferences
                .getString(TOKEN_ENDPOINT, BuildConfig.DEFAULT_TOKEN_ENDPOINT)
                .orEmpty()
                .trim(),
            deviceSecret = preferences
                .getString(DEVICE_SECRET, BuildConfig.DEFAULT_DEVICE_SECRET)
                .orEmpty(),
            languages = preferences
                .getString(LANGUAGES, defaultLanguages)
                .orEmpty()
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank),
            prompt = preferences.getString(PROMPT, defaultPrompt).orEmpty().trim(),
            keywords = preferences
                .getString(KEYWORDS, defaultKeywords)
                .orEmpty()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList(),
            returnToPreviousKeyboard = preferences.getBoolean(RETURN_TO_PREVIOUS, true),
            endpointMode = EndpointMode.from(preferences.getString(ENDPOINT_MODE, null)),
            customEndpoint = preferences.getString(CUSTOM_ENDPOINT, "").orEmpty().trim(),
            // Same rule as every other setting: the constant is only the
            // default, and anything the user saved wins. `from` additionally
            // clamps a value that is no longer offered.
            maxRecordingMinutes = RecordingLimit.from(
                preferences.getInt(MAX_RECORDING_MINUTES, RecordingLimit.default()),
            ),
        )
    }

    fun save(context: Context, settings: LiveTypeSettings) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(TOKEN_ENDPOINT, settings.tokenEndpoint.trim())
            .putString(DEVICE_SECRET, settings.deviceSecret)
            .putString(LANGUAGES, settings.languages.joinToString(","))
            .putString(PROMPT, settings.prompt.trim())
            .putString(KEYWORDS, settings.keywords.joinToString("\n"))
            .putBoolean(RETURN_TO_PREVIOUS, settings.returnToPreviousKeyboard)
            .putString(ENDPOINT_MODE, settings.endpointMode.name)
            .putString(CUSTOM_ENDPOINT, settings.customEndpoint.trim())
            .putInt(MAX_RECORDING_MINUTES, RecordingLimit.from(settings.maxRecordingMinutes))
            .apply()
    }

    /**
     * Stores the endpoint mode the moment it is picked, together with the URL
     * that mode implies.
     *
     * The Save button is not involved. A mode used to live in the Activity
     * until Save wrote it, so leaving the screen threw the choice away and the
     * next visit re-applied the stored mode over the stored URL — the screen
     * came back on a worker the user had already moved off.
     *
     * All three keys go into one `edit()`, so the mode and the URL are never
     * stored apart. [customEndpoint] rides along because a derived mode
     * overwrites [TOKEN_ENDPOINT]: without a home of its own, a hand-typed URL
     * would not survive a trip through `DEV` and back.
     *
     * Deliberately partial: it touches the endpoint keys and nothing else, so
     * a mode switch never commits half-edited text from the other fields.
     */
    fun saveEndpointSelection(
        context: Context,
        mode: EndpointMode,
        endpoint: String,
        customEndpoint: String,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENDPOINT_MODE, mode.name)
            .putString(TOKEN_ENDPOINT, endpoint.trim())
            .putString(CUSTOM_ENDPOINT, customEndpoint.trim())
            .apply()
    }
}

