package dev.dobrinskiy.livetype.network

import android.util.Base64
import dev.dobrinskiy.livetype.audio.PcmAudioRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RealtimeTranscriber(
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onTranscriptDelta(itemId: String, delta: String)
        fun onTranscriptCompleted(itemId: String, transcript: String)
        fun onError(message: String)
        fun onClosed()
    }

    private val closing = AtomicBoolean(false)
    @Volatile
    private var socket: WebSocket? = null

    fun connect(clientSecret: String) {
        val request = Request.Builder()
            // Transcription sessions carry their model in the ephemeral token
            // (audio.input.transcription.model). Passing ?model= is rejected.
            .url(REALTIME_URL)
            .header("Authorization", "Bearer $clientSecret")
            .build()

        socket = httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(sessionUpdate().toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleEvent(text)
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    throwable: Throwable,
                    response: Response?,
                ) {
                    if (!closing.get()) {
                        val suffix = response?.let { " (HTTP ${it.code})" }.orEmpty()
                        listener.onError(
                            (throwable.message ?: "Realtime connection failed") + suffix,
                        )
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed()
                }
            },
        )
    }

    fun appendAudio(bytes: ByteArray): Boolean {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return send(
            JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("audio", encoded),
        )
    }

    fun commit(): Boolean =
        send(JSONObject().put("type", "input_audio_buffer.commit"))

    fun clear(): Boolean =
        send(JSONObject().put("type", "input_audio_buffer.clear"))

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        socket?.close(1000, "Done")
        socket = null
    }

    private fun send(event: JSONObject): Boolean =
        socket?.send(event.toString()) == true

    private fun handleEvent(text: String) {
        val event = runCatching { JSONObject(text) }.getOrElse {
            listener.onError("OpenAI returned malformed JSON")
            return
        }

        when (event.optString("type")) {
            "session.updated" -> listener.onReady()

            "conversation.item.input_audio_transcription.delta" -> {
                val delta = event.optString("delta")
                if (delta.isNotEmpty()) {
                    listener.onTranscriptDelta(event.optString("item_id"), delta)
                }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                listener.onTranscriptCompleted(
                    event.optString("item_id"),
                    event.optString("transcript"),
                )
            }

            "error" -> {
                val error = event.optJSONObject("error")
                listener.onError(
                    error?.optString("message")?.ifBlank { null }
                        ?: "OpenAI Realtime returned an error",
                )
            }
        }
    }

    /**
     * Deliberately omits the `transcription` block. The model, languages,
     * prompt and keywords all come from the ephemeral token minted by the
     * worker; OpenAI makes `model` mandatory whenever `transcription` is
     * present, so sending it here would let the device override the worker's
     * choice. Omitting the block leaves the token's config intact — this
     * update only re-asserts the audio format and yields `session.updated`,
     * which the service uses as its ready signal.
     */
    private fun sessionUpdate(): JSONObject {
        val input = JSONObject()
            .put(
                "format",
                JSONObject()
                    .put("type", "audio/pcm")
                    .put("rate", PcmAudioRecorder.SAMPLE_RATE),
            )
            .put("noise_reduction", JSONObject().put("type", "near_field"))
            .put("turn_detection", JSONObject.NULL)

        return JSONObject()
            .put("type", "session.update")
            .put(
                "session",
                JSONObject()
                    .put("type", "transcription")
                    .put(
                        "audio",
                        JSONObject().put("input", input),
                    ),
            )
    }

    companion object {
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

