package dev.dobrinskiy.livetype.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * @param onSilencedChanged Fired on the main thread whenever the platform
 *   starts or stops feeding us silence because a higher-priority client — the
 *   screen recorder, a phone call, another recorder — took the microphone.
 *   Since Android 10 that never surfaces as a read error: [AudioRecord] keeps
 *   returning full buffers of zeroes, so the only honest signal is
 *   [AudioRecordingConfiguration.isClientSilenced]. It is a recoverable,
 *   temporary condition, never an [onError].
 */
class PcmAudioRecorder(
    private val context: Context,
    private val onAudio: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onSilencedChanged: (Boolean) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recordingThread: Thread? = null

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Callbacks are delivered here, so [onSilencedChanged] is always main-thread. */
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val silenced = AtomicBoolean(false)
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    fun start(): Result<Unit> = runCatching {
        check(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) {
            "Microphone permission is not granted"
        }
        check(running.compareAndSet(false, true)) { "Recorder is already running" }

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "24 kHz PCM recording is not supported on this device" }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minimum, CHUNK_BYTES * 2),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Could not initialize the microphone"
        }

        audioRecord = recorder
        recorder.startRecording()
        startSilenceWatch(recorder)
        recordingThread = thread(name = "LiveTypeAudio", isDaemon = true) {
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (running.get()) {
                    val count = recorder.read(
                        buffer,
                        0,
                        buffer.size,
                        AudioRecord.READ_BLOCKING,
                    )
                    when {
                        count > 0 -> onAudio(buffer.copyOf(count))
                        count < 0 -> error("AudioRecord read failed with code $count")
                    }
                }
            } catch (error: Throwable) {
                if (running.get()) {
                    onError(error.message ?: "Microphone recording failed")
                }
            } finally {
                stopSilenceWatch()
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                audioRecord = null
                running.set(false)
            }
        }
    }.onFailure {
        running.set(false)
        stopSilenceWatch()
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { audioRecord?.stop() }
        recordingThread?.join(1_000)
        recordingThread = null
        // Belt and braces: the recording thread's `finally` normally does this,
        // but the join above can time out and the callback must not outlive us.
        stopSilenceWatch()
    }

    val isRecording: Boolean
        get() = running.get()

    /**
     * Watches the platform's recording configurations for the lifetime of this
     * recording only, so nothing is leaked across sessions.
     */
    @Synchronized
    private fun startSilenceWatch(recorder: AudioRecord) {
        val manager = audioManager ?: return
        // The one reliable link back to *our* AudioRecord: the list also holds
        // every other app that is recording, and their silencing is not ours.
        val sessionId = recorder.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>?) {
                publishSilenced(isSilenced(configs, sessionId))
            }
        }
        recordingCallback = callback
        manager.registerAudioRecordingCallback(callback, callbackHandler)
        // Registration does not replay the current state, so seed from it —
        // otherwise a microphone that was already taken before we started would
        // go unreported until something else changed.
        publishSilenced(isSilenced(manager.activeRecordingConfigurations, sessionId))
    }

    @Synchronized
    private fun stopSilenceWatch() {
        recordingCallback?.let { audioManager?.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        // Never leave the warning latched on after the recorder is gone.
        publishSilenced(false)
    }

    /**
     * True when the entry the platform keeps for our own audio session says the
     * client is being fed silence.
     *
     * `isClientSilenced()` arrived in API 29 and minSdk is 28, so on API 28
     * this is always false: the platform has no way to tell us, and inventing a
     * guess would be worse than staying quiet.
     *
     * An absent entry counts as *not* silenced rather than leaving the last
     * value latched — a stale warning that never clears would break the
     * automatic recovery this whole mechanism exists for.
     */
    private fun isSilenced(
        configs: List<AudioRecordingConfiguration>?,
        sessionId: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (configs == null) return false
        return configs.any { it.clientAudioSessionId == sessionId && it.isClientSilenced }
    }

    /** Reports only edges, so the status line is not rewritten on every change. */
    private fun publishSilenced(value: Boolean) {
        if (silenced.getAndSet(value) == value) return
        callbackHandler.post { onSilencedChanged(value) }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val CHUNK_MILLISECONDS = 100
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES =
            SAMPLE_RATE * CHUNK_MILLISECONDS / 1_000 * BYTES_PER_SAMPLE
    }
}
