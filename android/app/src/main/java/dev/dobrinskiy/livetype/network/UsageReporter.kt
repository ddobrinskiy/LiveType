package dev.dobrinskiy.livetype.network

import android.util.Log
import dev.dobrinskiy.livetype.config.LiveTypeSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

/**
 * One spend window as the worker computed it: whole *local* calendar days,
 * including today. `last_7d` is today plus the six days before it, not the last
 * 168 hours.
 *
 * [usdMicros] is integer micro-USD and is the money. It is never re-derived
 * from [seconds] on the device: every stored row is billed at the price frozen
 * at its session time, so recomputing would be wrong after any price change.
 */
data class UsageWindow(
    val seconds: Double,
    val usdMicros: Long,
    val sessions: Int,
)

/**
 * @param estimated the worker could not use a published price for this model
 *   and fell back to OpenAI's own estimate. The UI must say so rather than
 *   let the user read fabricated precision as fact.
 */
data class UsagePrice(
    val usdMicrosPerMinute: Long,
    val unit: String,
    val estimated: Boolean,
)

data class UsageSummary(
    val model: String,
    val price: UsagePrice,
    val today: UsageWindow,
    val last7d: UsageWindow,
    val last30d: UsageWindow,
    val source: String,
    val asOf: String,
)

/**
 * Outcome of `GET /usage`. Every failure mode the settings screen has to tell
 * apart is its own case: "unknown" must never be rendered as "zero".
 */
sealed class UsageOutcome {
    data class Loaded(val summary: UsageSummary) : UsageOutcome()

    /** 401 — the device secret the worker holds is not the one we sent. */
    object Unauthorized : UsageOutcome()

    /** Any other non-2xx, carrying the worker's own `error` text if it sent one. */
    data class WorkerError(val status: Int, val detail: String) : UsageOutcome()

    /** The request never got an answer: worker down, no route, no network. */
    object Unreachable : UsageOutcome()

    /** 2xx whose body is not the shape this app knows how to read. */
    object Malformed : UsageOutcome()
}

/**
 * The device half of the metering contract. It **reports** and it **renders** —
 * it does not price. The `usage` object OpenAI puts on the transcription event
 * is forwarded byte-for-byte and the worker owns the price table, the model and
 * the aggregation.
 *
 * Follows [TokenProvider]'s HTTP style deliberately: same `HttpURLConnection`,
 * same `X-Device-Secret` header, no second HTTP stack.
 */
class UsageReporter {
    /**
     * Posts one billable event. Blocking — call it off the main thread.
     *
     * Fire-and-forget by design: the return value is [Unit] and every failure
     * ends in the log. `item_id` is the worker's idempotency key, so a retry
     * would be free, but losing a usage row is still cheaper than letting a
     * metering call disturb dictation.
     */
    fun report(settings: LiveTypeSettings, itemId: String, usage: JSONObject) {
        if (!settings.isConfigured || itemId.isBlank()) return
        runCatching {
            // Verbatim: the usage object goes on the wire exactly as OpenAI
            // sent it. Reshaping or re-keying it here would put a billing
            // decision on the phone.
            val body = JSONObject()
                .put("item_id", itemId)
                .put("usage", usage)
                .toString()

            val connection = open(settings.usageEndpoint, settings).apply {
                requestMethod = "POST"
                // Short: this shares the service's single-thread executor with
                // the token fetch, and metering must never make the next
                // dictation wait.
                connectTimeout = 8_000
                readTimeout = 8_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val text = readBody(connection, status)
                if (status in 200..299) {
                    Log.d(TAG, "Usage reported for $itemId: HTTP $status $text")
                } else {
                    Log.w(TAG, "Usage report rejected for $itemId: HTTP $status $text")
                }
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.w(TAG, "Usage report failed for $itemId: ${it.message}") }
    }

    /**
     * Reads the spend summary. Blocking — call it off the main thread.
     *
     * `tz_offset_minutes` is minutes to *add* to UTC, which is what decides
     * where the worker puts the local day boundaries.
     */
    fun fetchSummary(settings: LiveTypeSettings): UsageOutcome {
        val offsetMinutes =
            TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val url = "${settings.usageEndpoint}?tz_offset_minutes=$offsetMinutes"

        return runCatching {
            val connection = open(url, settings).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                val status = connection.responseCode
                val body = readBody(connection, status)
                when {
                    status == 401 -> UsageOutcome.Unauthorized
                    status in 200..299 -> parse(body)
                    else -> UsageOutcome.WorkerError(status, errorDetail(body))
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            Log.w(TAG, "Usage fetch failed: ${it.message}")
            UsageOutcome.Unreachable
        }
    }

    private fun open(url: String, settings: LiveTypeSettings): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device-Secret", settings.deviceSecret)
        }

    private fun readBody(connection: HttpURLConnection, status: Int): String {
        val stream =
            if (status in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    /** The worker's own wording, e.g. `Worker is misconfigured` when D1 is absent. */
    private fun errorDetail(body: String): String =
        runCatching { JSONObject(body).optString("error") }
            .getOrDefault("")
            .ifBlank { body.trim().take(200) }

    private fun parse(body: String): UsageOutcome {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return UsageOutcome.Malformed
        val price = json.optJSONObject("price") ?: return UsageOutcome.Malformed
        val windows = json.optJSONObject("windows") ?: return UsageOutcome.Malformed
        val model = json.optString("model").ifBlank { return UsageOutcome.Malformed }

        val today = window(windows.optJSONObject("today")) ?: return UsageOutcome.Malformed
        val last7d = window(windows.optJSONObject("last_7d")) ?: return UsageOutcome.Malformed
        val last30d = window(windows.optJSONObject("last_30d")) ?: return UsageOutcome.Malformed
        if (!price.has("usd_micros_per_minute")) return UsageOutcome.Malformed

        return UsageOutcome.Loaded(
            UsageSummary(
                // model and source are displayed, never assumed: the worker owns
                // both and either may change without the app being rebuilt.
                model = model,
                price = UsagePrice(
                    usdMicrosPerMinute = price.optLong("usd_micros_per_minute"),
                    unit = price.optString("unit"),
                    estimated = price.optBoolean("estimated", false),
                ),
                today = today,
                last7d = last7d,
                last30d = last30d,
                source = json.optString("source"),
                asOf = json.optString("as_of"),
            ),
        )
    }

    /**
     * A window without `usd_micros` is not a zero window — it is a response we
     * do not understand, and saying "$0.00" to that would be a lie.
     */
    private fun window(json: JSONObject?): UsageWindow? {
        if (json == null || !json.has("usd_micros")) return null
        return UsageWindow(
            seconds = json.optDouble("seconds", 0.0),
            usdMicros = json.optLong("usd_micros"),
            sessions = json.optInt("sessions", 0),
        )
    }

    companion object {
        private const val TAG = "LiveTypeUsage"
    }
}
