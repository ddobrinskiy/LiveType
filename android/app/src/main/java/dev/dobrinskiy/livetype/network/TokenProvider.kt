package dev.dobrinskiy.livetype.network

import android.util.Log
import dev.dobrinskiy.livetype.config.LiveTypeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The worker refused to mint a token because this device has spent its daily
 * allowance (HTTP 402).
 *
 * Its own type because it is the one token failure that is not a fault: nothing
 * is misconfigured and nothing is unreachable, so the keyboard should say what
 * happened in the user's language rather than surface an HTTP line. The figures
 * are the worker's, in integer micro-USD, and are only ever displayed.
 *
 * [usdMicros] is 0 when the worker sent no figures with the refusal, which is
 * why the UI must handle a capless message too.
 */
class SpendCapReachedException(
    val usdMicros: Long,
    val spentUsdMicros: Long,
) : Exception("Daily spend cap reached")

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
            // The cap is the one refusal with a specific thing to say, so it is
            // picked out before the generic HTTP path flattens it into a status
            // line. Missing or unreadable figures degrade to zeros rather than
            // failing: the refusal itself is the news.
            if (status == HTTP_PAYMENT_REQUIRED) {
                val cap = runCatching { JSONObject(body).optJSONObject("cap") }.getOrNull()
                throw SpendCapReachedException(
                    usdMicros = cap?.optLong("usd_micros") ?: 0L,
                    spentUsdMicros = cap?.optLong("spent_usd_micros") ?: 0L,
                )
            }
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

        /** `HttpURLConnection` has no constant for 402. */
        private const val HTTP_PAYMENT_REQUIRED = 402
    }
}

