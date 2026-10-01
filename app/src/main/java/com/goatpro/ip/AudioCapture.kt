package com.goatpro.ip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(
    private val context: Context,
    private val onAudioChunk: (ByteArray) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    fun start(): Boolean {
        if (running.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        audioRecord = recorder
        running.set(true)
        recorder.startRecording()
        audioThread = Thread {
            val buffer = ByteArray(minBuffer)
            while (running.get()) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onAudioChunk(buffer.copyOf(read))
                }
            }
        }.apply {
            name = "goat-ip-audio"
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        audioThread = null
    }

    fun isRunning(): Boolean = running.get()

    companion object {
        const val SAMPLE_RATE = 44_100
    }
}
