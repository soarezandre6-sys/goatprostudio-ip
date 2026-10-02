package com.goatpro.ip

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX VideoCapture/Recorder diagnostic route for exact 8K negotiation.
 *
 * The goal is to let CameraX negotiate the recording session from the device's
 * camcorder profiles and then verify the actual bound resolution. Anything
 * below exact 7680x4320 is treated as a failed 8K attempt rather than silently
 * accepting a 4K fallback.
 */
class CameraX8kRecorderProbe(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onNegotiated(
            width: Int,
            height: Int,
            selectedQuality: String
        )

        fun onStarted(width: Int, height: Int)
        fun onError(message: String)
        fun onStopped()
    }

    private val starting = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private var recording: Recording? = null
    private var outputFile: File? = null
    private var boundVideoCapture: VideoCapture<Recorder>? = null
    private var provider: ProcessCameraProvider? = null

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        cameraSelector: CameraSelector,
        expectedSize: Size = Size(7680, 4320)
    ): Boolean {
        if (running.get() || starting.get()) return true

        starting.set(true)
        provider = cameraProvider

        return try {
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(Quality.HIGHEST)
                )
                .setVideoCapabilitiesSource(
                    Recorder.VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE
                )
                .build()

            val videoCapture = VideoCapture.Builder(recorder).build()
            boundVideoCapture = videoCapture

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                videoCapture
            )

            val info = videoCapture.resolutionInfo
                ?: throw IllegalStateException(
                    "CameraX não informou a resolução negociada."
                )

            val raw = info.resolution
            val width = maxOf(raw.width, raw.height)
            val height = minOf(raw.width, raw.height)
            val expectedWidth =
                maxOf(expectedSize.width, expectedSize.height)
            val expectedHeight =
                minOf(expectedSize.width, expectedSize.height)
            val quality =
                videoCapture.selectedQuality?.toString()
                    ?: "desconhecida"

            listener.onNegotiated(width, height, quality)

            if (
                width != expectedWidth ||
                height != expectedHeight
            ) {
                throw IllegalStateException(
                    "CameraX negociou " +
                        width + "x" + height +
                        " (" + quality + "), não " +
                        expectedWidth + "x" + expectedHeight + "."
                )
            }

            val file = File(
                context.cacheDir,
                "goat-camerax-8k-probe.mp4"
            )
            runCatching { file.delete() }
            outputFile = file

            val outputOptions = FileOutputOptions.Builder(file)
                .setFileSizeLimit(512L * 1024L * 1024L)
                .build()

            recording = recorder
                .prepareRecording(context, outputOptions)
                .start(
                    ContextCompat.getMainExecutor(context)
                ) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            starting.set(false)
                            running.set(true)
                            listener.onStarted(width, height)
                        }

                        is VideoRecordEvent.Finalize -> {
                            val wasActive =
                                running.get() || starting.get()
                            running.set(false)
                            starting.set(false)
                            recording = null

                            if (event.hasError()) {
                                if (wasActive) {
                                    listener.onError(
                                        "CameraX Recorder encerrou com erro " +
                                            event.error +
                                            (event.cause?.message?.let {
                                                ": " + it
                                            } ?: "")
                                    )
                                }
                            } else if (wasActive) {
                                listener.onStopped()
                            }

                            cleanupFile()
                        }
                    }

            true
        } catch (ex: Exception) {
            starting.set(false)
            running.set(false)
            recording = null
            boundVideoCapture?.let {
                runCatching { cameraProvider.unbind(it) }
            }
            boundVideoCapture = null
            cleanupFile()
            listener.onError(
                ex.message ?: ex.javaClass.simpleName
            )
            false
        }
    }

    fun stop() {
        val wasActive = running.get() || starting.get()
        running.set(false)
        starting.set(false)

        val localRecording = recording
        recording = null
        runCatching { localRecording?.stop() }

        boundVideoCapture?.let { capture ->
            runCatching { provider?.unbind(capture) }
        }
        boundVideoCapture = null
        provider = null

        cleanupFile()

        if (wasActive) {
            listener.onStopped()
        }
    }

    private fun cleanupFile() {
        val file = outputFile
        outputFile = null
        if (file != null) {
            runCatching { file.delete() }
        }
    }
}
