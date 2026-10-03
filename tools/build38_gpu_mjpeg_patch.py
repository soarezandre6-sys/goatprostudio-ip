from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GPU = ROOT / "app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build38 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)


gpu = GPU.read_text(encoding="utf-8")

# Imports for hardware JPEG ImageReader snapshots.
gpu = replace_once(
    gpu,
    "import android.graphics.SurfaceTexture\n",
    "import android.graphics.SurfaceTexture\nimport android.graphics.ImageFormat\n",
    "gpu import ImageFormat",
)
gpu = replace_once(
    gpu,
    "import android.media.MediaCodec\n",
    "import android.media.ImageReader\nimport android.media.MediaCodec\n",
    "gpu import ImageReader",
)

# JPEG callback from the GPU fallback path.
gpu = replace_once(
    gpu,
    """        fun onStarted(\n            width: Int,\n            height: Int,\n            fps: Int,\n            bitrate: Int,\n            sourceWidth: Int,\n            sourceHeight: Int\n        )\n        fun onError(message: String)\n""",
    """        fun onStarted(\n            width: Int,\n            height: Int,\n            fps: Int,\n            bitrate: Int,\n            sourceWidth: Int,\n            sourceHeight: Int\n        )\n        fun onJpegFrame(data: ByteArray, width: Int, height: Int) = Unit\n        fun onError(message: String)\n""",
    "gpu listener jpeg",
)

# State for a second output surface in the SAME Camera2 session. The JPEG reader is
# only asked to capture while an HTTP/MJPEG client is actually connected.
gpu = replace_once(
    gpu,
    """    private var cameraSurface: Surface? = null\n    private var cameraTexture: SurfaceTexture? = null\n\n    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY\n""",
    """    private var cameraSurface: Surface? = null\n    private var cameraTexture: SurfaceTexture? = null\n\n    private var mjpegReader: ImageReader? = null\n    private var mjpegThread: HandlerThread? = null\n    private var mjpegHandler: Handler? = null\n\n    @Volatile\n    private var mjpegEnabled = false\n\n    @Volatile\n    private var mjpegQuality = 50\n\n    @Volatile\n    private var mjpegFps = 5\n\n    private var mjpegSize = Size(0, 0)\n    private var mjpegOrientationDegrees = 0\n\n    private val mjpegCaptureRunnable = object : Runnable {\n        override fun run() {\n            if (!mjpegEnabled || stopping.get()) return\n            captureMjpegFrame()\n            workerHandler?.postDelayed(\n                this,\n                1000L / mjpegFps.coerceIn(1, MAX_GPU_MJPEG_FPS)\n            )\n        }\n    }\n\n    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY\n""",
    "gpu mjpeg fields",
)

# Reset capture loop when starting a new pipeline.
gpu = replace_once(
    gpu,
    """        droppedDeliveryFrames = 0L\n        lastPresentationNs = 0L\n        deliveryQueue.clear()\n        config = request\n""",
    """        droppedDeliveryFrames = 0L\n        lastPresentationNs = 0L\n        deliveryQueue.clear()\n        config = request\n        workerHandler?.removeCallbacks(mjpegCaptureRunnable)\n""",
    "gpu start reset",
)

# Public MJPEG switch. MainActivity already calls this through Front4kDirectStreamer
# whenever /video gains or loses a client.
gpu = replace_once(
    gpu,
    """    fun requestKeyFrame() {\n""",
    """    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 5) {\n        mjpegEnabled = enabled\n        mjpegQuality = quality.coerceIn(20, 90)\n        mjpegFps = fps.coerceIn(1, MAX_GPU_MJPEG_FPS)\n        workerHandler?.post { updateMjpegCaptureLoop() }\n    }\n\n    fun requestKeyFrame() {\n""",
    "gpu setMjpegOutput",
)

# Create a hardware JPEG output sized as close as possible to the 3840x2160 wire
# format, and keep the exact same rotation metadata used by the GPU H.264 path.
gpu = replace_once(
    gpu,
    """        outputWidth = request.targetWidth\n        outputHeight = request.targetHeight\n\n        val stableBitrate = request.targetBitrate.coerceAtMost(12_000_000)\n        setupEncoder(outputWidth, outputHeight, actualFps, stableBitrate)\n""",
    """        outputWidth = request.targetWidth\n        outputHeight = request.targetHeight\n        mjpegOrientationDegrees = totalRotation\n        setupMjpegReader(characteristics, outputWidth, outputHeight)\n\n        val stableBitrate = request.targetBitrate.coerceAtMost(12_000_000)\n        setupEncoder(outputWidth, outputHeight, actualFps, stableBitrate)\n""",
    "gpu setup jpeg reader",
)

# Do not put the JPEG surface in the repeating 30 fps request. It is a stalling
# surface, so it is included in the session but captured separately at <=5 fps.
old_configured = """            override fun onConfigured(session: CameraCaptureSession) {\n                if (stopping.get()) {\n                    runCatching { session.close() }\n                    return\n                }\n                captureSession = session\n                try {\n                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {\n                        addTarget(surface)\n                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)\n                        set(\n                            CaptureRequest.CONTROL_CAPTURE_INTENT,\n                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD\n                        )\n                        val afModes = characteristics.get(\n                            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES\n                        ) ?: intArrayOf()\n                        if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {\n                            set(\n                                CaptureRequest.CONTROL_AF_MODE,\n                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO\n                            )\n                        }\n\n                        val ranges = characteristics.get(\n                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES\n                        ).orEmpty()\n                        chooseFpsRange(ranges, actualFps)?.let {\n                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)\n                        }\n\n                        if (Build.VERSION.SDK_INT >= 30) {\n                            characteristics.get(\n                                CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE\n                            )?.let { range ->\n                                set(\n                                    CaptureRequest.CONTROL_ZOOM_RATIO,\n                                    request.zoomRatio.coerceIn(range.lower, range.upper)\n                                )\n                            }\n                        }\n                    }\n                    session.setRepeatingRequest(builder.build(), null, handler)\n                } catch (ex: Exception) {\n                    fail(\n                        \"Falha ao iniciar captura Camera2 GPU: \" +\n                            (ex.message ?: ex.javaClass.simpleName)\n                    )\n                }\n            }\n"""
new_configured = """            override fun onConfigured(session: CameraCaptureSession) {\n                if (stopping.get()) {\n                    runCatching { session.close() }\n                    return\n                }\n                captureSession = session\n                try {\n                    applyRepeatingRequest(\n                        session,\n                        camera,\n                        request,\n                        characteristics,\n                        handler\n                    )\n                    updateMjpegCaptureLoop()\n                } catch (ex: Exception) {\n                    fail(\n                        \"Falha ao iniciar captura Camera2 GPU: \" +\n                            (ex.message ?: ex.javaClass.simpleName)\n                    )\n                }\n            }\n"""
gpu = replace_once(gpu, old_configured, new_configured, "gpu onConfigured")

# Session outputs: H.264 SurfaceTexture + hardware JPEG ImageReader, same camera.
old_session = """        if (Build.VERSION.SDK_INT >= 28) {\n            val output = OutputConfiguration(surface)\n            request.physicalCameraId?.let { output.setPhysicalCameraId(it) }\n            val executor = Executor { command -> handler.post(command) }\n            camera.createCaptureSession(\n                SessionConfiguration(\n                    SessionConfiguration.SESSION_REGULAR,\n                    listOf(output),\n                    executor,\n                    callback\n                )\n            )\n        } else {\n            @Suppress(\"DEPRECATION\")\n            camera.createCaptureSession(listOf(surface), callback, handler)\n        }\n    }\n\n    private fun renderFrame() {\n"""
new_session = """        val jpegSurface = mjpegReader?.surface\n        if (Build.VERSION.SDK_INT >= 28) {\n            val outputs = mutableListOf(OutputConfiguration(surface))\n            if (jpegSurface != null) outputs.add(OutputConfiguration(jpegSurface))\n            request.physicalCameraId?.let { physicalId ->\n                outputs.forEach { output ->\n                    runCatching { output.setPhysicalCameraId(physicalId) }\n                }\n            }\n            val executor = Executor { command -> handler.post(command) }\n            camera.createCaptureSession(\n                SessionConfiguration(\n                    SessionConfiguration.SESSION_REGULAR,\n                    outputs,\n                    executor,\n                    callback\n                )\n            )\n        } else {\n            val surfaces = mutableListOf(surface)\n            if (jpegSurface != null) surfaces.add(jpegSurface)\n            @Suppress(\"DEPRECATION\")\n            camera.createCaptureSession(surfaces, callback, handler)\n        }\n    }\n\n    private fun applyRepeatingRequest(\n        session: CameraCaptureSession,\n        camera: CameraDevice,\n        request: Config,\n        characteristics: CameraCharacteristics,\n        handler: Handler\n    ) {\n        val surface = cameraSurface\n            ?: throw IllegalStateException(\"Surface da câmera indisponível.\")\n        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {\n            addTarget(surface)\n            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)\n            set(\n                CaptureRequest.CONTROL_CAPTURE_INTENT,\n                CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD\n            )\n            val afModes = characteristics.get(\n                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES\n            ) ?: intArrayOf()\n            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {\n                set(\n                    CaptureRequest.CONTROL_AF_MODE,\n                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO\n                )\n            }\n\n            val ranges = characteristics.get(\n                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES\n            ).orEmpty()\n            chooseFpsRange(ranges, actualFps)?.let {\n                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)\n            }\n\n            if (Build.VERSION.SDK_INT >= 30) {\n                characteristics.get(\n                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE\n                )?.let { range ->\n                    set(\n                        CaptureRequest.CONTROL_ZOOM_RATIO,\n                        request.zoomRatio.coerceIn(range.lower, range.upper)\n                    )\n                }\n            }\n        }\n        session.setRepeatingRequest(builder.build(), null, handler)\n    }\n\n    private fun setupMjpegReader(\n        characteristics: CameraCharacteristics,\n        targetWidth: Int,\n        targetHeight: Int\n    ) {\n        val size = chooseMjpegJpegSize(characteristics, targetWidth, targetHeight) ?: return\n        mjpegSize = size\n\n        val thread = HandlerThread(\"goat-gpu-mjpeg-jpeg\").apply { start() }\n        val handler = Handler(thread.looper)\n        val reader = ImageReader.newInstance(\n            size.width,\n            size.height,\n            ImageFormat.JPEG,\n            2\n        )\n        reader.setOnImageAvailableListener({ source ->\n            val image = runCatching { source.acquireLatestImage() }.getOrNull()\n            if (image != null) {\n                try {\n                    if (mjpegEnabled && !stopping.get()) {\n                        val plane = image.planes.firstOrNull()\n                        val buffer = plane?.buffer\n                        if (buffer != null && buffer.remaining() > 0) {\n                            val copy = buffer.duplicate()\n                            val bytes = ByteArray(copy.remaining())\n                            copy.get(bytes)\n                            if (bytes.isNotEmpty()) {\n                                listener.onJpegFrame(\n                                    bytes,\n                                    size.width,\n                                    size.height\n                                )\n                            }\n                        }\n                    }\n                } catch (_: Exception) {\n                    // MJPEG is a compatibility output. Never stop H.264 for it.\n                } finally {\n                    runCatching { image.close() }\n                }\n            }\n        }, handler)\n\n        mjpegThread = thread\n        mjpegHandler = handler\n        mjpegReader = reader\n    }\n\n    private fun chooseMjpegJpegSize(\n        characteristics: CameraCharacteristics,\n        targetWidth: Int,\n        targetHeight: Int\n    ): Size? {\n        val map = characteristics.get(\n            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP\n        ) ?: return null\n        val sizes = map.getOutputSizes(ImageFormat.JPEG)\n            ?.filter { it.width > 0 && it.height > 0 }\n            .orEmpty()\n        if (sizes.isEmpty()) return null\n\n        sizes.firstOrNull {\n            it.width == targetWidth && it.height == targetHeight\n        }?.let { return it }\n\n        val targetAspect = targetWidth.toDouble() / targetHeight.toDouble()\n        val targetPixels = targetWidth.toLong() * targetHeight.toLong()\n        val nearAspect = sizes.filter { size ->\n            kotlin.math.abs(\n                size.width.toDouble() / size.height.toDouble() - targetAspect\n            ) <= 0.035\n        }\n        val notLarger = nearAspect.filter {\n            it.width.toLong() * it.height.toLong() <= targetPixels\n        }\n        return notLarger.maxByOrNull {\n            it.width.toLong() * it.height.toLong()\n        } ?: nearAspect.minByOrNull { size ->\n            kotlin.math.abs(\n                size.width.toLong() * size.height.toLong() - targetPixels\n            )\n        } ?: sizes.minByOrNull { size ->\n            val aspectPenalty = kotlin.math.abs(\n                size.width.toDouble() / size.height.toDouble() - targetAspect\n            ) * 1_000_000_000.0\n            val areaPenalty = kotlin.math.abs(\n                size.width.toLong() * size.height.toLong() - targetPixels\n            ).toDouble()\n            aspectPenalty + areaPenalty\n        }\n    }\n\n    private fun updateMjpegCaptureLoop() {\n        val handler = workerHandler ?: return\n        handler.removeCallbacks(mjpegCaptureRunnable)\n        if (\n            mjpegEnabled &&\n            !stopping.get() &&\n            captureSession != null &&\n            cameraDevice != null &&\n            mjpegReader != null\n        ) {\n            handler.post(mjpegCaptureRunnable)\n        }\n    }\n\n    private fun captureMjpegFrame() {\n        val session = captureSession ?: return\n        val camera = cameraDevice ?: return\n        val reader = mjpegReader ?: return\n        val request = config ?: return\n        val handler = workerHandler ?: return\n\n        try {\n            val builder = camera.createCaptureRequest(\n                CameraDevice.TEMPLATE_VIDEO_SNAPSHOT\n            ).apply {\n                addTarget(reader.surface)\n                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)\n                set(\n                    CaptureRequest.CONTROL_CAPTURE_INTENT,\n                    CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT\n                )\n                set(CaptureRequest.JPEG_QUALITY, mjpegQuality.toByte())\n                set(CaptureRequest.JPEG_ORIENTATION, mjpegOrientationDegrees)\n\n                if (Build.VERSION.SDK_INT >= 30) {\n                    val characteristics = context\n                        .getSystemService(Context.CAMERA_SERVICE) as CameraManager\n                    characteristics.getCameraCharacteristics(\n                        request.physicalCameraId ?: request.logicalCameraId\n                    ).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { range ->\n                        set(\n                            CaptureRequest.CONTROL_ZOOM_RATIO,\n                            request.zoomRatio.coerceIn(range.lower, range.upper)\n                        )\n                    }\n                }\n            }\n            session.capture(builder.build(), null, handler)\n        } catch (_: Exception) {\n            // Keep H.264 alive even if this device refuses a video snapshot.\n        }\n    }\n\n    private fun renderFrame() {\n"""
gpu = replace_once(gpu, old_session, new_session, "gpu session + jpeg methods")

# Release JPEG resources independently from the H.264 pipeline.
gpu = replace_once(
    gpu,
    """        stopping.set(true)\n\n        runCatching { captureSession?.stopRepeating() }\n""",
    """        stopping.set(true)\n        mjpegEnabled = false\n        workerHandler?.removeCallbacks(mjpegCaptureRunnable)\n\n        runCatching { captureSession?.stopRepeating() }\n""",
    "gpu release loop",
)
gpu = replace_once(
    gpu,
    """        runCatching { cameraSurface?.release() }\n        cameraSurface = null\n        runCatching { cameraTexture?.release() }\n        cameraTexture = null\n\n        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {\n""",
    """        runCatching { cameraSurface?.release() }\n        cameraSurface = null\n        runCatching { cameraTexture?.release() }\n        cameraTexture = null\n\n        runCatching { mjpegReader?.setOnImageAvailableListener(null, null) }\n        runCatching { mjpegReader?.close() }\n        mjpegReader = null\n        mjpegHandler = null\n        runCatching { mjpegThread?.quitSafely() }\n        mjpegThread = null\n        mjpegSize = Size(0, 0)\n\n        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {\n""",
    "gpu release jpeg",
)

# Constant for a deliberately modest hardware JPEG cadence. H.264 remains 30 fps;
# browser/HTTP gets moving 4K-class JPEG without turning JPEG into the bottleneck.
gpu = replace_once(
    gpu,
    """    companion object {\n        private const val VERTEX_SHADER = \"\"\"\n""",
    """    companion object {\n        private const val MAX_GPU_MJPEG_FPS = 5\n\n        private const val VERTEX_SHADER = \"\"\"\n""",
    "gpu mjpeg constant",
)

GPU.write_text(gpu, encoding="utf-8")

front = FRONT.read_text(encoding="utf-8")

# Route HTTP client state into both Camera1 GPU and Camera2 GPU fallback.
front = replace_once(
    front,
    """        camera1GpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n    }\n""",
    """        camera1GpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n        gpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n    }\n""",
    "front forward setMjpegOutput",
)

# Forward JPEG snapshots from the Camera2/GPU fallback into MjpegServer.
front = replace_once(
    front,
    """                override fun onStarted(\n                    width: Int,\n                    height: Int,\n                    fps: Int,\n                    bitrate: Int,\n                    sourceWidth: Int,\n                    sourceHeight: Int\n                ) {\n""",
    """                override fun onJpegFrame(data: ByteArray, width: Int, height: Int) {\n                    listener.onJpegFrame(data, width, height)\n                }\n\n                override fun onStarted(\n                    width: Int,\n                    height: Int,\n                    fps: Int,\n                    bitrate: Int,\n                    sourceWidth: Int,\n                    sourceHeight: Int\n                ) {\n""",
    "front gpu jpeg callback",
)

# Apply already-known client state before starting the fallback pipeline.
front = replace_once(
    front,
    """        gpuDelegate = local\n\n        val safeBitrate = if (profile.bitrate > 0) {\n""",
    """        gpuDelegate = local\n        local.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n\n        val safeBitrate = if (profile.bitrate > 0) {\n""",
    "front gpu initial mjpeg state",
)

FRONT.write_text(front, encoding="utf-8")
print("Build 38 GPU MJPEG patch applied")
