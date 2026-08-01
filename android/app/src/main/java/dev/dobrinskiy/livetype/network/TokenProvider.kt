package dev.dobrinskiy.livetype.network

import android.util.Log
import dev.dobrinskiy.livetype.config.LiveTypeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TokenProvider {
    fun fetch(settings: LiveTypeSettings): Result<String> = runCatching {
        Log.d(TAG, "Fetching token from ${settings.tokenEndpoint}")
        val connection = (URL(settings.tokenEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device-Secret", settings.deviceSecret)
        }

        try {
            connection.outputStream.use { output ->
                output.write(hints(settings).toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching {
                    JSONObject(body).optString("error").ifBlank { body }
                }.getOrDefault(body)
                error("Token endpoint returned HTTP $status: ${detail.take(300)}")
            }

            JSONObject(body).optString("value")
                .takeIf(String::isNotBlank)
                ?: error("Token endpoint response did not contain a client secret")
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.e(TAG, "Token fetch failed: ${it.message}") }

    /**
     * Hints only. The worker picks the transcription model and drops any hint
     * the chosen model does not accept, so nothing here is authoritative.
     */
    private fun hints(settings: LiveTypeSettings): JSONObject =
        JSONObject()
            .put("languages", JSONArray(settings.languages))
            .put("prompt", settings.prompt)
            .put("keywords", JSONArray(settings.keywords))

    companion object {
        private const val TAG = "LiveTypeToken"
    }
}

