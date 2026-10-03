package com.goatpro.ip

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Build 31 front camera live path.
 *
 * Camera1 -> SurfaceTexture -> OpenGL -> MediaCodec H.264 -> RTSP listener.
 *
 * This avoids MediaRecorder/MPEG-TS buffering and checks BOTH Camera1
 * supportedPreviewSizes and supportedVideoSizes. If exact 3840x2160 preview is
 * available it is used directly; otherwise the largest useful front source is
 * rendered to the requested encoder size by the GPU.
 */
@Suppress("DEPRECATION")
class Camera1GpuH264Streamer(
    private val context: Context,
    private val listener: Listener
) {
    data class Config(
        val legacyCameraId: Int,
        val targetWidth: Int = 3840,
        val targetHeight: Int = 2160,
        val targetFps: Int = 30,
        val targetBitrate: Int = 18_000_000,
        val deviceRotationDegrees: Int = 0,
        val extraRotationDegrees: Int = 0
    )

    data class Probe(
        val sourceSize: Size,
        val exactPreview4k: Boolean,
        val exactVideo4k: Boolean,
        val maxFps: Int
    )

    interface Listener {
        fun onAccessUnit(
            data: ByteArray,
            presentationTimeUs: Long,
            keyFrame: Boolean,
            codecConfig: Boolean
        )

        fun onStarted(
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int,
            sourceWidth: Int,
            sourceHeight: Int,
            exactPreview4k: Boolean,
            exactVideo4k: Boolean
        )

        fun onError(message: String)
        fun onStopped()
    }

    private val starting = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var camera: Camera? = null
    private var cameraTexture: SurfaceTexture? = null

    private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var drainThread: Thread? = null
    private var deliveryThread: Thread? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0
    private var oesTextureId = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var texMatrixHandle = -1
    private val textureTransform = FloatArray(16)

    private var outputWidth = 3840
    private var outputHeight = 2160
    private var actualFps = 30
    private var actualBitrate = 18_000_000
    private var selectedSource = Size(0, 0)
    private var exactPreview4k = false
    private var exactVideo4k = false

    @Volatile
    private var firstEncodedFrame = false

    @Volatile
    private var lastCameraFrameNs = 0L

    @Volatile
    private var lastEncodedFrameNs = 0L

    @Volatile
    private var lastDeliveryCompletedNs = 0L

    @Volatile
    private var droppedDeliveryFrames = 0L

    private data class EncodedUnit(
        val data: ByteArray,
        val ptsUs: Long,
        val keyFrame: Boolean,
        val codecConfig: Boolean
    )

    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(8)

    private val vertexBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f
    )

    private var textureBuffer: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(config: Config): Boolean {
        if (running.get() || starting.get()) return true
        if (!starting.compareAndSet(false, true)) return false

        stopping.set(false)
        firstEncodedFrame = false
        lastCameraFrameNs = 0L
        lastEncodedFrameNs = 0L
        lastDeliveryCompletedNs = 0L
        droppedDeliveryFrames = 0L
        deliveryQueue.clear()

        val thread = HandlerThread("goat-camera1-gpu-h264").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler

        handler.post {
            try {
                setup(config)
            } catch (ex: Exception) {
                fail("Camera1 GPU: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }

        handler.postDelayed({
            if ((starting.get() || running.get()) && !firstEncodedFrame) {
                fail("DIAG INICIAL: Camera1/GPU abriu, mas não entregou H.264 em 5 segundos.")
            }
        }, 5_000L)

        Thread {
            while (!stopping.get() && (starting.get() || running.get())) {
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (!firstEncodedFrame) continue
                val now = System.nanoTime()
                val cameraAgeMs = if (lastCameraFrameNs > 0L)
                    (now - lastCameraFrameNs) / 1_000_000L else Long.MAX_VALUE
                val encoderAgeMs = if (lastEncodedFrameNs > 0L)
                    (now - lastEncodedFrameNs) / 1_000_000L else Long.MAX_VALUE
                val deliveryAgeMs = if (lastDeliveryCompletedNs > 0L)
                    (now - lastDeliveryCompletedNs) / 1_000_000L else Long.MAX_VALUE

                when {
                    cameraAgeMs > 3_000L -> {
                        fail("DIAG CAMERA: a frontal parou de entregar frames por ${cameraAgeMs} ms.")
                        break
                    }
                    encoderAgeMs > 3_000L -> {
                        fail("DIAG ENCODER: a câmera continua ativa, mas o H.264 parou por ${encoderAgeMs} ms.")
                        break
                    }
                    deliveryQueue.isNotEmpty() && deliveryAgeMs > 3_000L -> {
                        fail("DIAG RTSP/REDE: o H.264 continua sendo gerado, mas a entrega ao Studio travou por ${deliveryAgeMs} ms.")
                        break
                    }
                }
            }
        }.apply {
            name = "goat-camera1-gpu-health"
            isDaemon = true
            start()
        }
        return true
    }

    fun requestKeyFrame() {
        val local = codec ?: return
        if (!running.get() && !starting.get()) return
        runCatching {
            local.setParameters(
                Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        }
    }

    fun stop() {
        if (!running.get() && !starting.get() && workerHandler == null) return
        stopping.set(true)
        starting.set(false)
        running.set(false)
        val handler = workerHandler
        if (handler != null) {
            handler.post { release(notify = true) }
        } else {
            release(notify = true)
        }
    }

    private fun setup(config: Config) {
        val opened = Camera.open(config.legacyCameraId)
        camera = opened

        val params = opened.parameters
        val probe = probeParameters(params, config.targetWidth, config.targetHeight)
            ?: throw IllegalStateException("Camera1 frontal não publicou tamanhos de preview/vídeo.")

        selectedSource = probe.sourceSize
        exactPreview4k = probe.exactPreview4k
        exactVideo4k = probe.exactVideo4k
        actualFps = minOf(config.targetFps.coerceIn(5, 60), probe.maxFps.coerceAtLeast(5))
        actualBitrate = config.targetBitrate.coerceIn(8_000_000, 28_000_000)

        val info = Camera.CameraInfo()
        Camera.getCameraInfo(config.legacyCameraId, info)
        val relativeRotation = relativeRotationDegrees(
            sensorOrientation = info.orientation,
            deviceRotationDegrees = config.deviceRotationDegrees,
            frontFacing = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
        )
        val totalRotation = normalize(relativeRotation + config.extraRotationDegrees)
        val portrait = totalRotation == 90 || totalRotation == 270
        outputWidth = if (portrait) config.targetHeight else config.targetWidth
        outputHeight = if (portrait) config.targetWidth else config.targetHeight

        setupEncoder(outputWidth, outputHeight, actualFps, actualBitrate)
        setupGl(selectedSource, totalRotation)
        startDeliveryThread()
        startDrainThread()

        val applyParams = opened.parameters
        applyParams.setRecordingHint(true)

        if (
            applyParams.supportedFocusModes?.contains(
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
            ) == true
        ) {
            applyParams.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }

        chooseFpsRange(applyParams, actualFps)?.let { range ->
            runCatching { applyParams.setPreviewFpsRange(range[0], range[1]) }
        }

        val previews = applyParams.supportedPreviewSizes.orEmpty()
        val exactPreview = previews.firstOrNull {
            it.width == selectedSource.width && it.height == selectedSource.height
        }
        if (exactPreview != null) {
            applyParams.setPreviewSize(exactPreview.width, exactPreview.height)
        }

        // Some Samsung camera1 stacks carry a separate recording-size key.
        if (config.targetWidth == 3840 && config.targetHeight == 2160) {
            runCatching { applyParams.set("video-size", "3840x2160") }
        }

        opened.parameters = applyParams

        val texture = cameraTexture
            ?: throw IllegalStateException("SurfaceTexture Camera1 indisponível.")
        texture.setDefaultBufferSize(selectedSource.width, selectedSource.height)
        opened.setPreviewTexture(texture)
        opened.startPreview()
    }

    private fun setupEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val videoCaps = caps.videoCapabilities
        if (!videoCaps.isSizeSupported(width, height)) {
            encoder.release()
            throw IllegalStateException("Encoder H.264 não aceita ${width}x${height}.")
        }

        val bitrateMode = if (
            caps.encoderCapabilities.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )
        ) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        } else {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            runCatching { setInteger(MediaFormat.KEY_PRIORITY, 0) }
            runCatching { setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat()) }
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0) }
                runCatching { setInteger("prepend-sps-pps-to-idr-frames", 1) }
            }
            if (
                Build.VERSION.SDK_INT >= 30 &&
                caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            ) {
                runCatching { setInteger("low-latency", 1) }
            }
        }

        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
    }

    private fun setupGl(source: Size, rotationDegrees: Int) {
        val targetSurface = encoderSurface
            ?: throw IllegalStateException("Surface do encoder indisponível.")

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL display indisponível.")
        }
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            throw IllegalStateException("Falha ao inicializar EGL.")
        }

        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0)) {
            throw IllegalStateException("Configuração EGL não encontrada.")
        }
        val eglConfig = configs[0]
            ?: throw IllegalStateException("Configuração EGL vazia.")

        val glContext = EGL14.eglCreateContext(
            display,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        if (glContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("Falha ao criar contexto OpenGL.")
        }

        val window = EGL14.eglCreateWindowSurface(
            display,
            eglConfig,
            targetSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (window == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Falha ao criar EGLSurface.")
        }
        if (!EGL14.eglMakeCurrent(display, window, window, glContext)) {
            throw IllegalStateException("Falha ao ativar EGL.")
        }

        eglDisplay = display
        eglContext = glContext
        eglSurface = window

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTextureId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        glProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(glProgram, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(glProgram, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(glProgram, "uTexMatrix")

        textureBuffer = textureCoordinates(
            source.width,
            source.height,
            outputWidth,
            outputHeight,
            rotationDegrees
        )

        val texture = SurfaceTexture(oesTextureId)
        texture.setDefaultBufferSize(source.width, source.height)
        val handler = workerHandler
            ?: throw IllegalStateException("Handler Camera1 GPU indisponível.")
        texture.setOnFrameAvailableListener({ renderFrame() }, handler)
        cameraTexture = texture
    }

    private fun renderFrame() {
        if (stopping.get()) return
        val texture = cameraTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        try {
            texture.updateTexImage()
            lastCameraFrameNs = System.nanoTime()
            texture.getTransformMatrix(textureTransform)

            GLES20.glViewport(0, 0, outputWidth, outputHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(glProgram)

            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(
                positionHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                vertexBuffer
            )

            textureBuffer.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(
                texCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                0,
                textureBuffer
            )

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, textureTransform, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                texture.timestamp
            )
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw IllegalStateException("eglSwapBuffers falhou.")
            }
        } catch (ex: Exception) {
            fail("Falha render GPU Camera1: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    private fun startDrainThread() {
        val localCodec = codec ?: return
        drainThread = Thread {
            val info = MediaCodec.BufferInfo()
            while (!stopping.get() && (starting.get() || running.get()) && codec === localCodec) {
                try {
                    when (val index = localCodec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = localCodec.outputFormat
                            listOf("csd-0", "csd-1").forEach { key ->
                                format.getByteBuffer(key)?.let(::bufferBytes)
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        enqueue(
                                            EncodedUnit(
                                                it,
                                                0L,
                                                false,
                                                true
                                            )
                                        )
                                    }
                            }
                        }
                        else -> if (index >= 0) {
                            val out = localCodec.getOutputBuffer(index)
                            if (out != null && info.size > 0) {
                                out.position(info.offset)
                                out.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                out.get(bytes)
                                lastEncodedFrameNs = System.nanoTime()

                                if (!firstEncodedFrame) {
                                    lastDeliveryCompletedNs = lastEncodedFrameNs
                                    firstEncodedFrame = true
                                    starting.set(false)
                                    running.set(true)
                                    listener.onStarted(
                                        outputWidth,
                                        outputHeight,
                                        actualFps,
                                        actualBitrate,
                                        selectedSource.width,
                                        selectedSource.height,
                                        exactPreview4k,
                                        exactVideo4k
                                    )
                                }

                                enqueue(
                                    EncodedUnit(
                                        bytes,
                                        info.presentationTimeUs,
                                        (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                    )
                                )
                            }
                            localCodec.releaseOutputBuffer(index, false)
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            name = "goat-camera1-gpu-drain"
            isDaemon = true
            start()
        }
    }

    private fun startDeliveryThread() {
        deliveryThread = Thread {
            while (
                !stopping.get() &&
                (starting.get() || running.get() || deliveryQueue.isNotEmpty())
            ) {
                val unit = try {
                    deliveryQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                try {
                    listener.onAccessUnit(
                        unit.data,
                        unit.ptsUs,
                        unit.keyFrame,
                        unit.codecConfig
                    )
                    lastDeliveryCompletedNs = System.nanoTime()
                } catch (_: Exception) {
                    // RTSP/network must never block the encoder drain thread.
                }
            }
        }.apply {
            name = "goat-camera1-gpu-delivery"
            isDaemon = true
            start()
        }
    }

    private fun enqueue(unit: EncodedUnit) {
        if (deliveryQueue.offer(unit)) return
        deliveryQueue.poll()
        droppedDeliveryFrames++
        if (deliveryQueue.offer(unit)) {
            requestKeyFrame()
        }

        // If the network is briefly slower than 4K, reduce bitrate without
        // touching resolution or FPS. This keeps latency bounded instead of
        // allowing a backlog to freeze the visible stream.
        if (droppedDeliveryFrames > 0L && droppedDeliveryFrames % 12L == 0L) {
            val newBitrate = (actualBitrate * 0.82).toInt().coerceAtLeast(10_000_000)
            if (newBitrate < actualBitrate) {
                actualBitrate = newBitrate
                runCatching {
                    codec?.setParameters(
                        Bundle().apply {
                            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate)
                        }
                    )
                }
                requestKeyFrame()
            }
        }
    }

    private fun fail(message: String) {
        val handler = workerHandler
        if (handler != null && android.os.Looper.myLooper() != handler.looper) {
            handler.post { fail(message) }
            return
        }
        if (stopping.getAndSet(true)) return
        val active = starting.getAndSet(false) || running.getAndSet(false)
        release(notify = false)
        if (active) listener.onError(message)
    }

    private fun release(notify: Boolean) {
        val wasActive = starting.getAndSet(false) || running.getAndSet(false)
        stopping.set(true)

        val localCamera = camera
        camera = null
        runCatching { localCamera?.setPreviewCallback(null) }
        runCatching { localCamera?.stopPreview() }
        runCatching { localCamera?.release() }

        runCatching { cameraTexture?.release() }
        cameraTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
            if (glProgram != 0) runCatching { GLES20.glDeleteProgram(glProgram) }
            if (oesTextureId != 0) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0) }
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            }
            runCatching { EGL14.eglTerminate(eglDisplay) }
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        glProgram = 0
        oesTextureId = 0

        val localCodec = codec
        codec = null
        runCatching { localCodec?.signalEndOfInputStream() }
        runCatching { localCodec?.stop() }
        runCatching { localCodec?.release() }
        runCatching { encoderSurface?.release() }
        encoderSurface = null

        runCatching { deliveryThread?.interrupt() }
        deliveryThread = null
        deliveryQueue.clear()
        drainThread = null

        val thread = workerThread
        workerHandler = null
        workerThread = null
        runCatching { thread?.quitSafely() }

        if (notify && wasActive) listener.onStopped()
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Falha ao linkar shader: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Falha ao compilar shader: $log")
        }
        return shader
    }

    private fun textureCoordinates(
        sourceWidth: Int,
        sourceHeight: Int,
        outWidth: Int,
        outHeight: Int,
        rotationDegrees: Int
    ): FloatBuffer {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val desiredSourceAspect = if (rotationDegrees == 90 || rotationDegrees == 270) {
            outHeight.toFloat() / outWidth.toFloat()
        } else {
            outWidth.toFloat() / outHeight.toFloat()
        }

        var left = 0f
        var right = 1f
        var bottom = 0f
        var top = 1f
        if (sourceAspect > desiredSourceAspect) {
            val visible = desiredSourceAspect / sourceAspect
            val pad = (1f - visible) / 2f
            left = pad
            right = 1f - pad
        } else if (sourceAspect < desiredSourceAspect) {
            val visible = sourceAspect / desiredSourceAspect
            val pad = (1f - visible) / 2f
            bottom = pad
            top = 1f - pad
        }

        val corners = arrayOf(
            floatArrayOf(left, bottom),
            floatArrayOf(right, bottom),
            floatArrayOf(left, top),
            floatArrayOf(right, top)
        )

        val rotated = corners.flatMap { p ->
            val x = p[0]
            val y = p[1]
            val mapped = when (rotationDegrees) {
                90 -> floatArrayOf(1f - y, x)
                180 -> floatArrayOf(1f - x, 1f - y)
                270 -> floatArrayOf(y, 1f - x)
                else -> floatArrayOf(x, y)
            }
            listOf(mapped[0], mapped[1])
        }.toFloatArray()
        return floatBufferOf(*rotated)
    }

    private fun bufferBytes(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun floatBufferOf(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        fun probe(legacyCameraId: Int, targetWidth: Int = 3840, targetHeight: Int = 2160): Probe? {
            return try {
                val opened = Camera.open(legacyCameraId)
                try {
                    probeParameters(opened.parameters, targetWidth, targetHeight)
                } finally {
                    opened.release()
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun probeParameters(
            params: Camera.Parameters,
            targetWidth: Int,
            targetHeight: Int
        ): Probe? {
            val previews = params.supportedPreviewSizes.orEmpty()
                .map { Size(it.width, it.height) }
                .filter { it.width > 0 && it.height > 0 }
            val videos = params.supportedVideoSizes.orEmpty()
                .map { Size(it.width, it.height) }
                .filter { it.width > 0 && it.height > 0 }

            if (previews.isEmpty() && videos.isEmpty()) return null

            val exactPreview = previews.firstOrNull {
                it.width == targetWidth && it.height == targetHeight
            }
            val exactVideo = videos.firstOrNull {
                it.width == targetWidth && it.height == targetHeight
            }

            val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
            val source = exactPreview
                ?: previews.minByOrNull { size ->
                    val aspectPenalty = abs(
                        size.width.toFloat() / size.height.toFloat() - targetAspect
                    ) * 10_000_000.0
                    val areaReward = size.width.toLong() * size.height.toLong()
                    aspectPenalty - areaReward.toDouble()
                }
                ?: exactVideo
                ?: videos.maxByOrNull { it.width.toLong() * it.height.toLong() }
                ?: return null

            val maxFps = params.supportedPreviewFpsRange.orEmpty()
                .maxOfOrNull { range ->
                    if (range.size > 1) range[1] / 1000 else 30
                }
                ?.coerceAtLeast(5)
                ?: 30

            return Probe(
                sourceSize = source,
                exactPreview4k = exactPreview != null,
                exactVideo4k = exactVideo != null,
                maxFps = maxFps
            )
        }

        private fun chooseFpsRange(params: Camera.Parameters, fps: Int): IntArray? {
            val target = fps.coerceIn(5, 60) * 1000
            return params.supportedPreviewFpsRange.orEmpty()
                .filter { it.size > 1 && it[0] <= target && it[1] >= target }
                .minWithOrNull(
                    compareBy<IntArray> { it[1] - it[0] }
                        .thenBy { abs(it[1] - target) }
                )
        }

        private fun relativeRotationDegrees(
            sensorOrientation: Int,
            deviceRotationDegrees: Int,
            frontFacing: Boolean
        ): Int {
            val device = normalize(deviceRotationDegrees)
            return if (frontFacing) {
                normalize(sensorOrientation + device)
            } else {
                normalize(sensorOrientation - device)
            }
        }

        private fun normalize(value: Int): Int = ((value % 360) + 360) % 360

        private fun floatBufferOf(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
