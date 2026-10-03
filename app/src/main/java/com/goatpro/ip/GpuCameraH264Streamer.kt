package com.goatpro.ip

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
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
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Hardware-only camera -> GPU -> H.264 path.
 *
 * The camera writes only a PUBLIC size it really advertises into a SurfaceTexture.
 * OpenGL then crops/scales/rotates that texture into a MediaCodec input Surface.
 * There is no NV21/JPEG/CPU copy in this path.
 *
 * This is used for two cases:
 * 1) Samsung front "4K compatibility": use the largest public front source and
 *    compose a 3840x2160 H.264 output instead of asking the HAL for an unsupported
 *    3840x2160 camera stream directly.
 * 2) background RTSP: preserve automatic rotation while keeping the heavy CPU
 *    ImageAnalysis path out of the loop.
 */
class GpuCameraH264Streamer(
    private val context: Context,
    private val listener: Listener
) {
    data class Config(
        val logicalCameraId: String,
        val physicalCameraId: String? = null,
        val targetWidth: Int,
        val targetHeight: Int,
        val targetFps: Int,
        val targetBitrate: Int,
        val zoomRatio: Float = 1f,
        val deviceRotationDegrees: Int = 0,
        val extraRotationDegrees: Int = 0,
        val preferLargestSource: Boolean = false,
        val minimumSourcePixels: Long = 0L
    )

    data class Probe(
        val sourceSize: Size,
        val qualityFps: Int,
        val maxAeFps: Int,
        val sensorOrientation: Int,
        val lensFacing: Int
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
            sourceHeight: Int
        )
        fun onError(message: String)
        fun onStopped()
    }

    private val starting = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var config: Config? = null

    @Volatile
    private var selectedSource: Size? = null

    @Volatile
    private var actualFps = 30

    @Volatile
    private var outputWidth = 1920

    @Volatile
    private var outputHeight = 1080

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var drainThread: Thread? = null
    private var deliveryThread: Thread? = null

    private data class EncodedUnit(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val keyFrame: Boolean,
        val codecConfig: Boolean
    )

    private val deliveryQueue = ArrayBlockingQueue<EncodedUnit>(10)

    @Volatile
    private var droppedDeliveryFrames = 0L

    private var codec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraSurface: Surface? = null
    private var cameraTexture: SurfaceTexture? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0
    private var oesTextureId = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var texMatrixHandle = -1
    private val textureTransform = FloatArray(16)

    private val vertexBuffer: FloatBuffer = floatBufferOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )
    private var textureBuffer: FloatBuffer = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    @Volatile
    private var firstEncodedFrame = false

    fun isRunning(): Boolean = running.get()
    fun isStarting(): Boolean = starting.get()

    fun start(request: Config): Boolean {
        if (running.get() || starting.get()) return true
        if (!starting.compareAndSet(false, true)) return false
        stopping.set(false)
        firstEncodedFrame = false
        droppedDeliveryFrames = 0L
        deliveryQueue.clear()
        config = request

        val thread = HandlerThread("goat-gpu-camera-h264").apply { start() }
        val handler = Handler(thread.looper)
        workerThread = thread
        workerHandler = handler

        handler.post {
            try {
                setupPipeline(request)
            } catch (ex: Exception) {
                fail(
                    "Falha no pipeline GPU: " +
                        (ex.message ?: ex.javaClass.simpleName)
                )
            }
        }

        handler.postDelayed({
            if ((starting.get() || running.get()) && !firstEncodedFrame) {
                fail("Pipeline GPU abriu, mas não entregou vídeo em 5 segundos.")
            }
        }, 5_000L)
        return true
    }

    fun requestKeyFrame() {
        val localCodec = codec ?: return
        if (!running.get() && !starting.get()) return
        runCatching {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            localCodec.setParameters(params)
        }
    }

    fun stop() {
        if (!running.get() && !starting.get() && workerHandler == null) return
        stopping.set(true)
        starting.set(false)
        running.set(false)
        val handler = workerHandler
        if (handler != null) {
            handler.post { releasePipeline(notify = true) }
        } else {
            releasePipeline(notify = true)
        }
    }

    private fun setupPipeline(request: Config) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(
            request.physicalCameraId ?: request.logicalCameraId
        )
        val probe = probeInternal(
            manager,
            request.physicalCameraId ?: request.logicalCameraId,
            request.targetFps,
            request.preferLargestSource,
            request.targetWidth,
            request.targetHeight
        ) ?: throw IllegalStateException("A câmera não expôs SurfaceTexture utilizável.")

        if (
            request.minimumSourcePixels > 0L &&
            probe.sourceSize.width.toLong() * probe.sourceSize.height.toLong() <
                request.minimumSourcePixels
        ) {
            throw IllegalStateException(
                "Fonte pública insuficiente: ${probe.sourceSize.width}x${probe.sourceSize.height}."
            )
        }

        selectedSource = probe.sourceSize
        actualFps = minOf(
            request.targetFps.coerceAtLeast(5),
            maxOf(5, maxFpsForSize(manager, request.physicalCameraId ?: request.logicalCameraId, probe.sourceSize))
        ).coerceAtMost(60)

        val relativeRotation = relativeRotationDegrees(
            probe.sensorOrientation,
            request.deviceRotationDegrees,
            probe.lensFacing
        )
        val totalRotation = normalizeDegrees(relativeRotation + request.extraRotationDegrees)
        val portraitOutput = totalRotation == 90 || totalRotation == 270
        outputWidth = if (portraitOutput) request.targetHeight else request.targetWidth
        outputHeight = if (portraitOutput) request.targetWidth else request.targetHeight

        setupEncoder(outputWidth, outputHeight, actualFps, request.targetBitrate)
        setupGl(probe.sourceSize, totalRotation)
        startDeliveryThread()
        startDrainThread()
        openCamera(manager, request, characteristics)
    }

    private fun setupEncoder(width: Int, height: Int, fps: Int, bitrate: Int) {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val videoCaps = caps.videoCapabilities
        if (!videoCaps.isSizeSupported(width, height)) {
            encoder.release()
            throw IllegalStateException("Encoder H.264 não aceita ${width}x${height}.")
        }

        val safeBitrate = bitrate.coerceIn(2_000_000, 60_000_000)
        val mode = if (
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
            setInteger(MediaFormat.KEY_BIT_RATE, safeBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
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
        val input = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        encoderSurface = input
    }

    private fun setupGl(sourceSize: Size, rotationDegrees: Int) {
        val inputSurface = encoderSurface
            ?: throw IllegalStateException("Surface do encoder indisponível.")

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("EGL display indisponível.")
        }
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            throw IllegalStateException("Falha ao inicializar EGL.")
        }

        val configAttribs = intArrayOf(
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
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)) {
            throw IllegalStateException("Nenhuma configuração EGL compatível.")
        }
        val eglConfig = configs[0]
            ?: throw IllegalStateException("Configuração EGL vazia.")

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        val glContext = EGL14.eglCreateContext(
            display,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )
        if (glContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("Falha ao criar contexto OpenGL ES 2.")
        }

        val windowSurface = EGL14.eglCreateWindowSurface(
            display,
            eglConfig,
            inputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0
        )
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Falha ao criar EGLSurface do encoder.")
        }
        if (!EGL14.eglMakeCurrent(display, windowSurface, windowSurface, glContext)) {
            throw IllegalStateException("Falha ao ativar contexto EGL.")
        }

        eglDisplay = display
        eglContext = glContext
        eglSurface = windowSurface

        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        oesTextureId = textureIds[0]
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
            sourceSize.width,
            sourceSize.height,
            outputWidth,
            outputHeight,
            rotationDegrees
        )

        val texture = SurfaceTexture(oesTextureId)
        texture.setDefaultBufferSize(sourceSize.width, sourceSize.height)
        val handler = workerHandler
            ?: throw IllegalStateException("Handler GPU indisponível.")
        texture.setOnFrameAvailableListener({ renderFrame() }, handler)
        cameraTexture = texture
        cameraSurface = Surface(texture)
    }

    private fun openCamera(
        manager: CameraManager,
        request: Config,
        characteristics: CameraCharacteristics
    ) {
        val handler = workerHandler
            ?: throw IllegalStateException("Handler de câmera indisponível.")
        manager.openCamera(
            request.logicalCameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopping.get()) {
                        runCatching { camera.close() }
                        return
                    }
                    cameraDevice = camera
                    createCaptureSession(camera, request, characteristics, handler)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    fail("Câmera desconectada durante transmissão GPU.")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    fail("Erro Camera2 no pipeline GPU: $error")
                }
            },
            handler
        )
    }

    private fun createCaptureSession(
        camera: CameraDevice,
        request: Config,
        characteristics: CameraCharacteristics,
        handler: Handler
    ) {
        val surface = cameraSurface
            ?: throw IllegalStateException("Surface da câmera indisponível.")

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (stopping.get()) {
                    runCatching { session.close() }
                    return
                }
                captureSession = session
                try {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(
                            CaptureRequest.CONTROL_CAPTURE_INTENT,
                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
                        )
                        val afModes = characteristics.get(
                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
                        ) ?: intArrayOf()
                        if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }

                        val ranges = characteristics.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                        ).orEmpty()
                        chooseFpsRange(ranges, actualFps)?.let {
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                        }

                        if (Build.VERSION.SDK_INT >= 30) {
                            characteristics.get(
                                CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                            )?.let { range ->
                                set(
                                    CaptureRequest.CONTROL_ZOOM_RATIO,
                                    request.zoomRatio.coerceIn(range.lower, range.upper)
                                )
                            }
                        }
                    }
                    session.setRepeatingRequest(builder.build(), null, handler)
                } catch (ex: Exception) {
                    fail(
                        "Falha ao iniciar captura Camera2 GPU: " +
                            (ex.message ?: ex.javaClass.simpleName)
                    )
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                fail("A câmera recusou a SurfaceTexture pública do pipeline GPU.")
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val output = OutputConfiguration(surface)
            request.physicalCameraId?.let { output.setPhysicalCameraId(it) }
            val executor = Executor { command -> handler.post(command) }
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    callback
                )
            )
        } else {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), callback, handler)
        }
    }

    private fun renderFrame() {
        if (stopping.get()) return
        val texture = cameraTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        try {
            texture.updateTexImage()
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
            GLES20.glUniformMatrix4fv(
                texMatrixHandle,
                1,
                false,
                textureTransform,
                0
            )

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
            fail(
                "Falha ao renderizar frame GPU: " +
                    (ex.message ?: ex.javaClass.simpleName)
            )
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
                                        enqueueAccessUnit(
                                            it,
                                            0L,
                                            false,
                                            true
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

                                if (!firstEncodedFrame) {
                                    firstEncodedFrame = true
                                    starting.set(false)
                                    running.set(true)
                                    val source = selectedSource ?: Size(0, 0)
                                    listener.onStarted(
                                        outputWidth,
                                        outputHeight,
                                        actualFps,
                                        config?.targetBitrate ?: 0,
                                        source.width,
                                        source.height
                                    )
                                }

                                enqueueAccessUnit(
                                    bytes,
                                    info.presentationTimeUs,
                                    (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
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
            name = "goat-gpu-h264-drain"
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
                        unit.presentationTimeUs,
                        unit.keyFrame,
                        unit.codecConfig
                    )
                } catch (_: Exception) {
                    // Network/client delivery must never stall the encoder drain.
                }
            }
        }.apply {
            name = "goat-gpu-h264-delivery"
            isDaemon = true
            start()
        }
    }

    private fun enqueueAccessUnit(
        data: ByteArray,
        presentationTimeUs: Long,
        keyFrame: Boolean,
        codecConfig: Boolean
    ) {
        val unit = EncodedUnit(
            data = data,
            presentationTimeUs = presentationTimeUs,
            keyFrame = keyFrame,
            codecConfig = codecConfig
        )

        if (deliveryQueue.offer(unit)) return

        // Keep latency bounded. If the RTSP consumer/network is slower than the
        // encoder, discard an old frame instead of blocking MediaCodec/GPU.
        deliveryQueue.poll()
        if (deliveryQueue.offer(unit)) {
            droppedDeliveryFrames++
            requestKeyFrame()
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
        releasePipeline(notify = false)
        if (active) listener.onError(message)
    }

    private fun releasePipeline(notify: Boolean) {
        val wasActive = starting.getAndSet(false) || running.getAndSet(false)
        stopping.set(true)

        runCatching { captureSession?.stopRepeating() }
        runCatching { captureSession?.abortCaptures() }
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null

        runCatching { cameraSurface?.release() }
        cameraSurface = null
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
        runCatching { workerThread?.quitSafely() }
        workerHandler = null
        workerThread = null
        drainThread = null
        selectedSource = null

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

        val rotated = corners.flatMap { point ->
            val x = point[0]
            val y = point[1]
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

        fun probe(
            context: Context,
            cameraId: String,
            requestedFps: Int = 30,
            preferLargestSource: Boolean = true
        ): Probe? {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                probeInternal(
                    manager,
                    cameraId,
                    requestedFps,
                    preferLargestSource,
                    1920,
                    1080
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun probeInternal(
            manager: CameraManager,
            cameraId: String,
            requestedFps: Int,
            preferLargestSource: Boolean,
            targetWidth: Int,
            targetHeight: Int
        ): Probe? {
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return null
            val sizes = map.getOutputSizes(SurfaceTexture::class.java)
                ?.filter { it.width > 0 && it.height > 0 }
                ?.distinctBy { it.width to it.height }
                .orEmpty()
            if (sizes.isEmpty()) return null

            val requested = requestedFps.coerceIn(5, 60)
            val capable = sizes.filter {
                maxFpsForSize(manager, cameraId, it) >= requested
            }
            val pool = if (capable.isNotEmpty()) capable else sizes

            val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
            val chosen = if (preferLargestSource) {
                pool.maxWithOrNull(
                    compareBy<Size> { it.width.toLong() * it.height.toLong() }
                        .thenBy { -abs(it.width.toFloat() / it.height.toFloat() - targetAspect) }
                )
            } else {
                pool.minByOrNull { size ->
                    val aspectPenalty =
                        abs(size.width.toFloat() / size.height.toFloat() - targetAspect) * 10_000_000.0
                    val areaPenalty = abs(
                        size.width.toLong() * size.height.toLong() -
                            targetWidth.toLong() * targetHeight.toLong()
                    ).toDouble()
                    aspectPenalty + areaPenalty
                }
            } ?: return null

            val ranges = chars.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
            ).orEmpty()
            val maxAe = ranges.maxOfOrNull { it.upper } ?: 30
            val qualityFps = minOf(
                maxAe,
                maxFpsForSize(manager, cameraId, chosen)
            ).coerceAtLeast(5)
            return Probe(
                sourceSize = chosen,
                qualityFps = qualityFps,
                maxAeFps = maxAe,
                sensorOrientation = chars.get(
                    CameraCharacteristics.SENSOR_ORIENTATION
                ) ?: 0,
                lensFacing = chars.get(
                    CameraCharacteristics.LENS_FACING
                ) ?: CameraCharacteristics.LENS_FACING_BACK
            )
        }

        private fun maxFpsForSize(
            manager: CameraManager,
            cameraId: String,
            size: Size
        ): Int {
            return try {
                val chars = manager.getCameraCharacteristics(cameraId)
                val map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                ) ?: return 30
                val duration = map.getOutputMinFrameDuration(
                    SurfaceTexture::class.java,
                    size
                )
                val durationFps = if (duration > 0L) {
                    (1_000_000_000L / duration).toInt().coerceAtLeast(1)
                } else {
                    // Zero means the HAL did not provide a useful timing value.
                    // Never interpret that as 120 FPS for a high-resolution stream.
                    30
                }
                val aeMax = chars.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                ).orEmpty().maxOfOrNull { it.upper } ?: 30
                minOf(durationFps, aeMax).coerceIn(1, 120)
            } catch (_: Exception) {
                30
            }
        }

        private fun chooseFpsRange(
            ranges: Array<out Range<Int>>,
            fps: Int
        ): Range<Int>? =
            ranges
                .filter { it.lower <= fps && it.upper >= fps }
                .minWithOrNull(
                    compareBy<Range<Int>> { it.upper - it.lower }
                        .thenBy { abs(it.upper - fps) }
                )

        private fun normalizeDegrees(value: Int): Int =
            ((value % 360) + 360) % 360

        private fun relativeRotationDegrees(
            sensorOrientation: Int,
            deviceRotationDegrees: Int,
            lensFacing: Int
        ): Int {
            val device = normalizeDegrees(deviceRotationDegrees)
            return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                normalizeDegrees(sensorOrientation + device)
            } else {
                normalizeDegrees(sensorOrientation - device)
            }
        }
    }
}
