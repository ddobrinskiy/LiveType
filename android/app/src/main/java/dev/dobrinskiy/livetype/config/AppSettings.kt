package dev.dobrinskiy.livetype.config

import android.content.Context
import dev.dobrinskiy.livetype.BuildConfig
import dev.dobrinskiy.livetype.R

data class LiveTypeSettings(
    val tokenEndpoint: String,
    val deviceSecret: String,
    val languages: List<String>,
    val prompt: String,
    val keywords: List<String>,
    val returnToPreviousKeyboard: Boolean,
) {
    val isConfigured: Boolean
        get() = (tokenEndpoint.startsWith("https://") || tokenEndpoint.startsWith("http://")) && deviceSecret.isNotBlank()
}

object AppSettings {
    private const val PREFERENCES = "livetype_settings"
    private const val TOKEN_ENDPOINT = "token_endpoint"
    private const val DEVICE_SECRET = "device_secret"
    private const val LANGUAGES = "languages"
    private const val PROMPT = "prompt"
    private const val KEYWORDS = "keywords"
    private const val RETURN_TO_PREVIOUS = "return_to_previous"

    // Seeded from resources so the first run matches the device locale.
    fun load(context: Context): LiveTypeSettings {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val defaultLanguages = context.getString(R.string.default_languages)
        val defaultPrompt = context.getString(R.string.default_prompt)
        val defaultKeywords = context.getString(R.string.default_keywords)
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
            .apply()
    }
}

