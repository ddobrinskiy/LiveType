package dev.dobrinskiy.livetype.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max

class PcmAudioRecorder(
    private val context: Context,
    private val onAudio: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var recordingThread: Thread? = null

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
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                audioRecord = null
                running.set(false)
            }
        }
    }.onFailure {
        running.set(false)
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { audioRecord?.stop() }
        recordingThread?.join(1_000)
        recordingThread = null
    }

    val isRecording: Boolean
        get() = running.get()

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val CHUNK_MILLISECONDS = 100
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES =
            SAMPLE_RATE * CHUNK_MILLISECONDS / 1_000 * BYTES_PER_SAMPLE
    }
}
