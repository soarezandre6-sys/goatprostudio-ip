from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"pattern not found: {label}")
    return text.replace(old, new, 1)

root = Path('.')

# --- MjpegServer: clear stale frame and enlarge TCP send buffer for UHD JPEGs.
p = root / 'app/src/main/java/com/goatpro/ip/MjpegServer.kt'
s = p.read_text()
s = replace_once(
    s,
    '''    fun offerFrame(jpeg: ByteArray) {\n        latestFrame.set(jpeg)\n    }\n\n    fun offerAudio''',
    '''    fun offerFrame(jpeg: ByteArray) {\n        latestFrame.set(jpeg)\n    }\n\n    fun clearFrame() {\n        latestFrame.set(null)\n    }\n\n    fun offerAudio''',
    'MjpegServer.clearFrame'
)
s = replace_once(
    s,
    '            socket.sendBufferSize = 128 * 1024',
    '            socket.sendBufferSize = 1024 * 1024',
    'MjpegServer send buffer'
)
p.write_text(s)

# --- Camera1GpuH264Streamer: same Camera1 source now optionally produces MJPEG.
p = root / 'app/src/main/java/com/goatpro/ip/Camera1GpuH264Streamer.kt'
s = p.read_text()
s = replace_once(
    s,
    'import android.graphics.SurfaceTexture\n',
    'import android.graphics.ImageFormat\nimport android.graphics.SurfaceTexture\n',
    'Camera1 import ImageFormat'
)
s = replace_once(
    s,
    '''        fun onError(message: String)\n        fun onStopped()\n    }''',
    '''        fun onJpegFrame(data: ByteArray, width: Int, height: Int) = Unit\n        fun onError(message: String)\n        fun onStopped()\n    }''',
    'Camera1 listener jpeg'
)
s = replace_once(
    s,
    '''    private var camera: Camera? = null\n    private var cameraTexture: SurfaceTexture? = null\n\n    private var codec: MediaCodec? = null''',
    '''    private var camera: Camera? = null\n    private var cameraTexture: SurfaceTexture? = null\n\n    @Volatile\n    private var mjpegEnabled = false\n\n    @Volatile\n    private var mjpegQuality = 50\n\n    @Volatile\n    private var mjpegFps = 10\n\n    @Volatile\n    private var mjpegCallbackInstalled = false\n\n    @Volatile\n    private var nextMjpegDueNs = 0L\n\n    private data class MjpegFrame(\n        val data: ByteArray,\n        val width: Int,\n        val height: Int\n    )\n\n    private val mjpegQueue = ArrayBlockingQueue<MjpegFrame>(1)\n    private var mjpegThread: Thread? = null\n\n    private var codec: MediaCodec? = null''',
    'Camera1 mjpeg fields'
)
s = replace_once(
    s,
    '''        lastPresentationNs = 0L\n        deliveryQueue.clear()''',
    '''        lastPresentationNs = 0L\n        nextMjpegDueNs = 0L\n        deliveryQueue.clear()\n        mjpegQueue.clear()''',
    'Camera1 reset mjpeg'
)
s = replace_once(
    s,
    '''    fun requestKeyFrame() {\n        val local = codec ?: return''',
    '''    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 10) {\n        mjpegEnabled = enabled\n        mjpegQuality = quality.coerceIn(20, 90)\n        mjpegFps = fps.coerceIn(1, 15)\n        if (!enabled) nextMjpegDueNs = 0L\n        workerHandler?.post { updateMjpegPreviewCallback() }\n    }\n\n    fun requestKeyFrame() {\n        val local = codec ?: return''',
    'Camera1 setMjpegOutput'
)
s = replace_once(
    s,
    '''        // Some Samsung camera1 stacks carry a separate recording-size key.\n        if (config.targetWidth == 3840 && config.targetHeight == 2160) {\n            runCatching { applyParams.set("video-size", "3840x2160") }\n        }\n\n        opened.parameters = applyParams\n\n        val texture = cameraTexture''',
    '''        // Some Samsung camera1 stacks carry a separate recording-size key.\n        if (config.targetWidth == 3840 && config.targetHeight == 2160) {\n            runCatching { applyParams.set("video-size", "3840x2160") }\n        }\n\n        if (applyParams.supportedPreviewFormats?.contains(ImageFormat.NV21) == true) {\n            applyParams.previewFormat = ImageFormat.NV21\n        }\n\n        opened.parameters = applyParams\n\n        val texture = cameraTexture''',
    'Camera1 preview format'
)
s = replace_once(
    s,
    '''        opened.setPreviewTexture(texture)\n        opened.startPreview()\n    }\n\n    private fun setupEncoder''',
    '''        opened.setPreviewTexture(texture)\n        opened.startPreview()\n        updateMjpegPreviewCallback()\n    }\n\n    private fun ensureMjpegThread() {\n        if (mjpegThread?.isAlive == true) return\n        mjpegThread = Thread {\n            while (!stopping.get() && (starting.get() || running.get() || mjpegQueue.isNotEmpty())) {\n                val frame = try {\n                    mjpegQueue.poll(100, TimeUnit.MILLISECONDS)\n                } catch (_: InterruptedException) {\n                    null\n                } ?: continue\n\n                try {\n                    if (mjpegEnabled) {\n                        val jpeg = ImageUtils.nv21ToJpeg(\n                            ImageUtils.Nv21Frame(frame.data, frame.width, frame.height),\n                            mjpegQuality\n                        )\n                        if (jpeg != null && mjpegEnabled) {\n                            listener.onJpegFrame(jpeg.bytes, jpeg.width, jpeg.height)\n                        }\n                    }\n                } catch (_: Exception) {\n                    // MJPEG is compatibility output; never stop H.264 because JPEG failed.\n                } finally {\n                    runCatching { camera?.addCallbackBuffer(frame.data) }\n                }\n            }\n        }.apply {\n            name = "goat-camera1-mjpeg"\n            isDaemon = true\n            start()\n        }\n    }\n\n    private fun updateMjpegPreviewCallback() {\n        val opened = camera ?: return\n        if (!mjpegEnabled) {\n            if (mjpegCallbackInstalled) {\n                runCatching { opened.setPreviewCallbackWithBuffer(null) }\n                mjpegCallbackInstalled = false\n            }\n            return\n        }\n        if (mjpegCallbackInstalled) return\n\n        val params = runCatching { opened.parameters }.getOrNull() ?: return\n        if (params.previewFormat != ImageFormat.NV21) return\n        val size = params.previewSize ?: return\n        val bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21)\n        val bufferSize = (size.width * size.height * bitsPerPixel / 8).coerceAtLeast(1)\n\n        ensureMjpegThread()\n        repeat(4) {\n            runCatching { opened.addCallbackBuffer(ByteArray(bufferSize)) }\n        }\n        opened.setPreviewCallbackWithBuffer callback@ { data, cam ->\n            if (!mjpegEnabled || stopping.get()) {\n                runCatching { cam.addCallbackBuffer(data) }\n                return@callback\n            }\n\n            val now = System.nanoTime()\n            val intervalNs = 1_000_000_000L / mjpegFps.coerceAtLeast(1)\n            if (nextMjpegDueNs != 0L && now < nextMjpegDueNs) {\n                runCatching { cam.addCallbackBuffer(data) }\n                return@callback\n            }\n            nextMjpegDueNs = now + intervalNs\n\n            if (!mjpegQueue.offer(MjpegFrame(data, size.width, size.height))) {\n                runCatching { cam.addCallbackBuffer(data) }\n            }\n        }\n        mjpegCallbackInstalled = true\n    }\n\n    private fun setupEncoder''',
    'Camera1 MJPEG worker'
)
s = replace_once(
    s,
    '''        val localCamera = camera\n        camera = null\n        runCatching { localCamera?.setPreviewCallback(null) }\n        runCatching { localCamera?.stopPreview() }''',
    '''        mjpegEnabled = false\n        val localCamera = camera\n        camera = null\n        runCatching { localCamera?.setPreviewCallbackWithBuffer(null) }\n        mjpegCallbackInstalled = false\n        runCatching { mjpegThread?.interrupt() }\n        mjpegThread = null\n        mjpegQueue.clear()\n        runCatching { localCamera?.setPreviewCallback(null) }\n        runCatching { localCamera?.stopPreview() }''',
    'Camera1 release MJPEG'
)
p.write_text(s)

# --- Front4kDirectStreamer: forward MJPEG produced by the same Camera1 source.
p = root / 'app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt'
s = p.read_text()
s = replace_once(
    s,
    '''        fun onStarted(width: Int, height: Int, fps: Int, bitrate: Int)\n        fun onError(message: String)''',
    '''        fun onJpegFrame(data: ByteArray, width: Int, height: Int) = Unit\n        fun onStarted(width: Int, height: Int, fps: Int, bitrate: Int)\n        fun onError(message: String)''',
    'Front4k listener jpeg'
)
s = replace_once(
    s,
    '''    @Volatile\n    var lastSourceDescription: String = ""\n        private set\n\n    private var camera: Camera? = null''',
    '''    @Volatile\n    var lastSourceDescription: String = ""\n        private set\n\n    @Volatile\n    private var mjpegEnabled = false\n\n    @Volatile\n    private var mjpegQuality = 50\n\n    @Volatile\n    private var mjpegFps = 10\n\n    private var camera: Camera? = null''',
    'Front4k mjpeg fields'
)
s = replace_once(
    s,
    '''    fun requestKeyFrame() {\n        camera1GpuDelegate?.requestKeyFrame()\n        gpuDelegate?.requestKeyFrame()\n        // MediaRecorder fallback has no public force-IDR API.\n    }\n\n    fun start(profile: Profile): Boolean {''',
    '''    fun requestKeyFrame() {\n        camera1GpuDelegate?.requestKeyFrame()\n        gpuDelegate?.requestKeyFrame()\n        // MediaRecorder fallback has no public force-IDR API.\n    }\n\n    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 10) {\n        mjpegEnabled = enabled\n        mjpegQuality = quality.coerceIn(20, 90)\n        mjpegFps = fps.coerceIn(1, 15)\n        camera1GpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n    }\n\n    fun start(profile: Profile): Boolean {''',
    'Front4k setMjpegOutput'
)
s = replace_once(
    s,
    '''                override fun onStarted(\n                    width: Int,''',
    '''                override fun onJpegFrame(data: ByteArray, width: Int, height: Int) {\n                    listener.onJpegFrame(data, width, height)\n                }\n\n                override fun onStarted(\n                    width: Int,''',
    'Front4k forward jpeg'
)
s = replace_once(
    s,
    '''        camera1GpuDelegate = local\n\n        val started = local.start(''',
    '''        camera1GpuDelegate = local\n        local.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)\n\n        val started = local.start(''',
    'Front4k configure jpeg delegate'
)
p.write_text(s)

# --- MainActivity: enable front 4K MJPEG when HTTP viewer connects and publish frames.
p = root / 'app/src/main/java/com/goatpro/ip/MainActivity.kt'
s = p.read_text()
s = replace_once(
    s,
    '''            override fun onVideoClientCountChanged(count: Int) {\n                runOnUiThread { updateConnectionStatus(count) }\n            }''',
    '''            override fun onVideoClientCountChanged(count: Int) {\n                if (selectedResolution.directFront4k) {\n                    front4kDirectStreamer.setMjpegOutput(\n                        enabled = count > 0,\n                        quality = streamJpegQuality,\n                        fps = (if (streamTargetFps > 0) streamTargetFps else 15)\n                            .coerceIn(5, 15)\n                    )\n                }\n                runOnUiThread { updateConnectionStatus(count) }\n            }''',
    'MainActivity client enables MJPEG'
)
s = replace_once(
    s,
    '''                override fun onStarted(\n                    width: Int,\n                    height: Int,\n                    fps: Int,\n                    bitrate: Int\n                ) {\n                    runOnUiThread {\n                        actualStreamWidth = width\n                        actualStreamHeight = height\n                        statusText.text =\n                            "TRANSMITINDO 4K FRONTAL PARA O GOAT PRO STUDIO"''',
    '''                override fun onJpegFrame(data: ByteArray, width: Int, height: Int) {\n                    if (server.videoClientCount() > 0) {\n                        server.offerFrame(data)\n                    }\n                }\n\n                override fun onStarted(\n                    width: Int,\n                    height: Int,\n                    fps: Int,\n                    bitrate: Int\n                ) {\n                    runOnUiThread {\n                        actualStreamWidth = width\n                        actualStreamHeight = height\n                        statusText.text =\n                            "TRANSMITINDO 4K FRONTAL PARA O GOAT PRO STUDIO"''',
    'MainActivity receive front jpeg'
)
s = replace_once(
    s,
    '''        resetPerformanceStats()\n        server.start()\n        server.setAudioEnabled(audioEnabled)''',
    '''        resetPerformanceStats()\n        server.clearFrame()\n        server.start()\n        server.setAudioEnabled(audioEnabled)''',
    'MainActivity clear stale MJPEG'
)
s = replace_once(
    s,
    '''        front4kDirectStreamer.stop()\n        rtspServer.stop()\n        server.stop()''',
    '''        front4kDirectStreamer.setMjpegOutput(false, streamJpegQuality, 10)\n        front4kDirectStreamer.stop()\n        rtspServer.stop()\n        server.stop()''',
    'MainActivity disable front MJPEG'
)
s = replace_once(
    s,
    '''        addressText.text = if (ip != null) {\n            if (selectedResolution.directFront4k) {\n                "4K frontal H.264 RTSP: rtsp://" +\n                    ip + ":8554/h264"\n            } else {\n                "MJPEG: http://" + ip +\n                    ":8080/video\\nH.264 RTSP: rtsp://" +\n                    ip + ":8554/h264"\n            }\n        } else {''',
    '''        addressText.text = if (ip != null) {\n            "MJPEG: http://" + ip +\n                ":8080/video\\nH.264 RTSP: rtsp://" +\n                ip + ":8554/h264"\n        } else {''',
    'MainActivity address both transports'
)
s = replace_once(
    s,
    '''        val urls =\n            if (selectedResolution.directFront4k) {\n                "4K frontal H.264 RTSP: rtsp://" +\n                    ip + ":8554/h264"\n            } else {\n                "MJPEG: http://" + ip +\n                    ":8080/video\\nH.264 RTSP: rtsp://" +\n                    ip + ":8554/h264"\n            }''',
    '''        val urls =\n            "MJPEG: http://" + ip +\n                ":8080/video\\nH.264 RTSP: rtsp://" +\n                ip + ":8554/h264"''',
    'MainActivity copy both transports'
)
s = replace_once(
    s,
    '''            if (selectedResolution.directFront4k) {\n                "Endereço 4K frontal H.264 copiado"\n            } else {\n                "Endereços MJPEG e H.264 copiados"\n            },''',
    '''            "Endereços MJPEG e H.264 copiados",''',
    'MainActivity copy toast'
)
s = replace_once(
    s,
    '''                dimensions + " • " + fpsLabel +\n                    " • H.264 hardware • 4K frontal"''',
    '''                dimensions + " • " + fpsLabel +\n                    " • H.264 hardware + MJPEG HTTP • 4K frontal"''',
    'MainActivity stream info'
)
p.write_text(s)

print('Build 34 patch applied')
