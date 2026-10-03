from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GPU = ROOT / "app/src/main/java/com/goatpro/ip/GpuCameraH264Streamer.kt"
FRONT = ROOT / "app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt"
MAIN = ROOT / "app/src/main/java/com/goatpro/ip/MainActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_main.xml"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Build40 patch: trecho nao encontrado: {label}")
    return text.replace(old, new, 1)


# ---- Layout: TextureView dedicated to the direct/GPU 4K preview. ----
layout = LAYOUT.read_text(encoding="utf-8")
layout = replace_once(
    layout,
    '''            <androidx.camera.view.PreviewView
                android:id="@+id/previewView"
                android:layout_width="match_parent"
                android:layout_height="250dp"
                android:background="#000000"
                app:implementationMode="performance"
                app:scaleType="fitCenter" />''',
    '''            <androidx.camera.view.PreviewView
                android:id="@+id/previewView"
                android:layout_width="match_parent"
                android:layout_height="250dp"
                android:background="#000000"
                app:implementationMode="performance"
                app:scaleType="fitCenter" />

            <TextureView
                android:id="@+id/directPreviewTexture"
                android:layout_width="match_parent"
                android:layout_height="250dp"
                android:background="#000000"
                android:visibility="gone" />''',
    "layout direct preview texture",
)
LAYOUT.write_text(layout, encoding="utf-8")

# ---- MainActivity: own the TextureView Surface, but never open a second camera. ----
main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    "import android.graphics.ImageFormat\n",
    "import android.graphics.ImageFormat\nimport android.graphics.SurfaceTexture\n",
    "main SurfaceTexture import",
)
main = replace_once(
    main,
    "import android.view.Surface\nimport android.view.View\n",
    "import android.view.Surface\nimport android.view.TextureView\nimport android.view.View\n",
    "main TextureView import",
)
main = replace_once(
    main,
    "    private lateinit var previewView: PreviewView\n",
    "    private lateinit var previewView: PreviewView\n    private lateinit var directPreviewTexture: TextureView\n    private var directPreviewSurface: Surface? = null\n",
    "main preview fields",
)
main = replace_once(
    main,
    "        previewView = findViewById(R.id.previewView)\n",
    "        previewView = findViewById(R.id.previewView)\n        directPreviewTexture = findViewById(R.id.directPreviewTexture)\n",
    "main find direct preview",
)
main = replace_once(
    main,
    "        cameraExecutor = Executors.newSingleThreadExecutor()\n        setupOrientationTracking()\n",
    "        cameraExecutor = Executors.newSingleThreadExecutor()\n        setupDirectPreviewSurface()\n        setupOrientationTracking()\n",
    "main setup direct preview",
)

helper = r'''
    private fun setupDirectPreviewSurface() {
        directPreviewTexture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    directPreviewSurface?.release()
                    directPreviewSurface = Surface(surfaceTexture)
                    if (
                        selectedResolution.directFront4k &&
                        (front4kDirectStreamer.isRunning() ||
                            front4kDirectStreamer.isStarting())
                    ) {
                        front4kDirectStreamer.setLocalPreviewSurface(
                            directPreviewSurface,
                            width,
                            height
                        )
                    }
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    if (
                        selectedResolution.directFront4k &&
                        (front4kDirectStreamer.isRunning() ||
                            front4kDirectStreamer.isStarting())
                    ) {
                        front4kDirectStreamer.setLocalPreviewSurface(
                            directPreviewSurface,
                            width,
                            height
                        )
                    }
                }

                override fun onSurfaceTextureDestroyed(
                    surfaceTexture: SurfaceTexture
                ): Boolean {
                    front4kDirectStreamer.setLocalPreviewSurface(null, 0, 0)
                    directPreviewSurface?.release()
                    directPreviewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
    }

    private fun setDirectPreviewVisible(visible: Boolean) {
        if (!::directPreviewTexture.isInitialized || !::previewView.isInitialized) return
        if (visible) {
            previewView.visibility = View.INVISIBLE
            directPreviewTexture.visibility = View.VISIBLE

            if (directPreviewSurface == null && directPreviewTexture.isAvailable) {
                directPreviewTexture.surfaceTexture?.let { texture ->
                    directPreviewSurface = Surface(texture)
                }
            }
            directPreviewSurface?.takeIf { it.isValid }?.let { surface ->
                front4kDirectStreamer.setLocalPreviewSurface(
                    surface,
                    directPreviewTexture.width.coerceAtLeast(1),
                    directPreviewTexture.height.coerceAtLeast(1)
                )
            }
        } else {
            front4kDirectStreamer.setLocalPreviewSurface(null, 0, 0)
            directPreviewSurface?.release()
            directPreviewSurface = null
            directPreviewTexture.visibility = View.GONE
            previewView.visibility = View.VISIBLE
        }
    }

'''
main = replace_once(
    main,
    "    private fun applyPreviewAspectRatio() {\n",
    helper + "    private fun applyPreviewAspectRatio() {\n",
    "main preview helpers",
)

# When direct 4K is idle, CameraX owns the local preview again.
main = replace_once(
    main,
    "            if (selectedResolution.directFront4k) {\n                if (\n                    front4kDirectStreamer.isRunning() ||\n",
    "            if (selectedResolution.directFront4k) {\n                if (\n                    front4kDirectStreamer.isRunning() ||\n",
    "main direct branch anchor",
)
# Insert after the active-stream early return block.
anchor = '''                if (
                    front4kDirectStreamer.isRunning() ||
                    front4kDirectStreamer.isStarting()
                ) {
                    return@addListener
                }

                val previewResolutionSelector ='''
main = replace_once(
    main,
    anchor,
    '''                if (
                    front4kDirectStreamer.isRunning() ||
                    front4kDirectStreamer.isStarting()
                ) {
                    return@addListener
                }

                setDirectPreviewVisible(false)

                val previewResolutionSelector =''',
    "main idle CameraX preview",
)

# Show direct GPU preview before CameraX is released for the direct streamer.
main = replace_once(
    main,
    '''        if (selectedResolution.directFront4k) {
            front4kH264FrameCount = 0L''',
    '''        if (selectedResolution.directFront4k) {
            setDirectPreviewVisible(true)
            front4kH264FrameCount = 0L''',
    "main show direct preview on stream",
)

# On a direct 4K failure, restore the normal CameraX view before rebinding.
main = replace_once(
    main,
    '''                    runOnUiThread {
                        front4kDirectStreamer.stop()
                        rtspServer.stop()''',
    '''                    runOnUiThread {
                        front4kDirectStreamer.stop()
                        setDirectPreviewVisible(false)
                        rtspServer.stop()''',
    "main hide direct preview on error",
)

# Stop path detaches local display surface before the direct streamer is released.
main = replace_once(
    main,
    '''        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.setMjpegOutput(false, streamJpegQuality, 10)
        front4kDirectStreamer.stop()''',
    '''        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.setMjpegOutput(false, streamJpegQuality, 10)
        setDirectPreviewVisible(false)
        front4kDirectStreamer.stop()''',
    "main stop local preview",
)

# Release the external display Surface on Activity teardown as well.
main = replace_once(
    main,
    '''        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()''',
    '''        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        setDirectPreviewVisible(false)
        front4kDirectStreamer.stop()
        rtspServer.stop()''',
    "main destroy local preview",
)
MAIN.write_text(main, encoding="utf-8")

# ---- Front4kDirectStreamer: pass one UI Surface to the active GPU pipeline. ----
front = FRONT.read_text(encoding="utf-8")
front = replace_once(
    front,
    "import android.os.Build\n",
    "import android.os.Build\nimport android.view.Surface\n",
    "front Surface import",
)
front = replace_once(
    front,
    '''    @Volatile
    private var mjpegFps = 10

    private var camera: Camera? = null''',
    '''    @Volatile
    private var mjpegFps = 10

    @Volatile
    private var localPreviewSurface: Surface? = null

    @Volatile
    private var localPreviewWidth = 0

    @Volatile
    private var localPreviewHeight = 0

    private var camera: Camera? = null''',
    "front local preview fields",
)
front = replace_once(
    front,
    '''    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 10) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(20, 90)
        mjpegFps = fps.coerceIn(1, 15)
        camera1GpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)
        gpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)
    }

    fun start(profile: Profile): Boolean {''',
    '''    fun setMjpegOutput(enabled: Boolean, quality: Int = 50, fps: Int = 10) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(20, 90)
        mjpegFps = fps.coerceIn(1, 15)
        camera1GpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)
        gpuDelegate?.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)
    }

    fun setLocalPreviewSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        localPreviewSurface = surface
        localPreviewWidth = width.coerceAtLeast(0)
        localPreviewHeight = height.coerceAtLeast(0)
        gpuDelegate?.setLocalPreviewSurface(
            surface,
            localPreviewWidth,
            localPreviewHeight
        )
    }

    fun start(profile: Profile): Boolean {''',
    "front local preview setter",
)

# Preserve more H.264 texture/detail. The previous hard 12 Mbps ceiling was low for 4K30.
front = front.replace(
    "profile.bitrate.coerceIn(8_000_000, 12_000_000)",
    "profile.bitrate.coerceIn(8_000_000, 28_000_000)",
)
front = replace_once(
    front,
    '''        gpuDelegate = local
        local.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)

        val safeBitrate = if (profile.bitrate > 0) {''',
    '''        gpuDelegate = local
        local.setMjpegOutput(mjpegEnabled, mjpegQuality, mjpegFps)
        local.setLocalPreviewSurface(
            localPreviewSurface,
            localPreviewWidth,
            localPreviewHeight
        )

        val safeBitrate = if (profile.bitrate > 0) {''',
    "front attach GPU local preview",
)
# If the native MediaRecorder route ever succeeds, allow it to show the same UI Surface too.
front = replace_once(
    front,
    '''                setVideoEncodingBitRate(safeBitrate)
                setOrientationHint(''',
    '''                setVideoEncodingBitRate(safeBitrate)
                localPreviewSurface?.takeIf { it.isValid }?.let { setPreviewDisplay(it) }
                setOrientationHint(''',
    "front mediarecorder preview surface",
)
FRONT.write_text(front, encoding="utf-8")

# ---- GPU streamer: draw the same OES camera texture to encoder + local UI Surface. ----
gpu = GPU.read_text(encoding="utf-8")
gpu = replace_once(
    gpu,
    '''    private var cameraSurface: Surface? = null
    private var cameraTexture: SurfaceTexture? = null

    private var mjpegReader: ImageReader? = null''',
    '''    private var cameraSurface: Surface? = null
    private var cameraTexture: SurfaceTexture? = null

    @Volatile
    private var localPreviewSurface: Surface? = null

    @Volatile
    private var localPreviewWidth = 0

    @Volatile
    private var localPreviewHeight = 0

    @Volatile
    private var lastLocalPreviewNs = 0L

    private var mjpegReader: ImageReader? = null''',
    "gpu local preview fields",
)
gpu = replace_once(
    gpu,
    '''    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glProgram = 0''',
    '''    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglPreviewSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null
    private var glProgram = 0''',
    "gpu egl preview fields",
)
gpu = replace_once(
    gpu,
    '''    fun setMjpegOutput(enabled: Boolean, quality: Int = 88, fps: Int = 10) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(MIN_GPU_MJPEG_QUALITY, MAX_GPU_MJPEG_QUALITY)
        mjpegFps = fps.coerceIn(1, MAX_GPU_MJPEG_FPS)
        workerHandler?.post { updateMjpegCaptureLoop() }
    }

    fun requestKeyFrame() {''',
    '''    fun setMjpegOutput(enabled: Boolean, quality: Int = 88, fps: Int = 10) {
        mjpegEnabled = enabled
        mjpegQuality = quality.coerceIn(MIN_GPU_MJPEG_QUALITY, MAX_GPU_MJPEG_QUALITY)
        mjpegFps = fps.coerceIn(1, MAX_GPU_MJPEG_FPS)
        workerHandler?.post { updateMjpegCaptureLoop() }
    }

    fun setLocalPreviewSurface(surface: Surface?, width: Int = 0, height: Int = 0) {
        localPreviewSurface = surface
        localPreviewWidth = width.coerceAtLeast(0)
        localPreviewHeight = height.coerceAtLeast(0)
        workerHandler?.post { rebuildLocalPreviewEglSurface() }
    }

    fun requestKeyFrame() {''',
    "gpu preview setter",
)
# Lift only the H.264 4K ceiling; requested/user-selected bitrate remains respected.
gpu = replace_once(
    gpu,
    "        val stableBitrate = request.targetBitrate.coerceAtMost(12_000_000)\n",
    "        val stableBitrate = request.targetBitrate.coerceIn(8_000_000, 28_000_000)\n",
    "gpu bitrate ceiling",
)
# Save the EGLConfig so a second window surface can be created later.
gpu = replace_once(
    gpu,
    '''        eglDisplay = display
        eglContext = glContext
        eglSurface = windowSurface

        val textureIds = IntArray(1)''',
    '''        eglDisplay = display
        eglContext = glContext
        eglSurface = windowSurface
        this.eglConfig = eglConfig

        val textureIds = IntArray(1)''',
    "gpu save EGL config",
)
gpu = replace_once(
    gpu,
    '''        cameraTexture = texture
        cameraSurface = Surface(texture)
    }

    private fun openCamera(''',
    '''        cameraTexture = texture
        cameraSurface = Surface(texture)
        rebuildLocalPreviewEglSurface()
    }

    private fun openCamera(''',
    "gpu initial local EGL surface",
)

start = gpu.find("    private fun renderFrame() {")
end = gpu.find("    private fun startDrainThread() {", start)
if start < 0 or end < 0:
    raise SystemExit("Build40 patch: renderFrame boundaries not found")
new_render = r'''    private fun rebuildLocalPreviewEglSurface() {
        destroyLocalPreviewEglSurface()
        val display = eglDisplay
        val config = eglConfig
        val surface = localPreviewSurface
        if (
            display == EGL14.EGL_NO_DISPLAY ||
            config == null ||
            surface == null ||
            !surface.isValid
        ) return

        val created = runCatching {
            EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )
        }.getOrNull() ?: EGL14.EGL_NO_SURFACE
        if (created != EGL14.EGL_NO_SURFACE) {
            eglPreviewSurface = created
            lastLocalPreviewNs = 0L
        }
    }

    private fun destroyLocalPreviewEglSurface() {
        val preview = eglPreviewSurface
        eglPreviewSurface = EGL14.EGL_NO_SURFACE
        if (preview == EGL14.EGL_NO_SURFACE || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        if (
            eglSurface != EGL14.EGL_NO_SURFACE &&
            eglContext != EGL14.EGL_NO_CONTEXT
        ) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    eglSurface,
                    eglSurface,
                    eglContext
                )
            }
        }
        runCatching { EGL14.eglDestroySurface(eglDisplay, preview) }
    }

    private fun drawCurrentTexture(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
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
    }

    private fun renderFrame() {
        if (stopping.get()) return
        val texture = cameraTexture ?: return
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        try {
            texture.updateTexImage()
            texture.getTransformMatrix(textureTransform)

            val rawTimestamp = texture.timestamp
            val frameStepNs = 1_000_000_000L / actualFps.coerceAtLeast(1)
            val presentationNs = when {
                rawTimestamp <= 0L -> lastPresentationNs + frameStepNs
                rawTimestamp <= lastPresentationNs -> lastPresentationNs + frameStepNs
                else -> rawTimestamp
            }
            lastPresentationNs = presentationNs

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("Falha ao ativar EGLSurface do encoder.")
            }
            drawCurrentTexture(outputWidth, outputHeight)
            EGLExt.eglPresentationTimeANDROID(
                eglDisplay,
                eglSurface,
                presentationNs
            )
            if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                throw IllegalStateException("eglSwapBuffers do encoder falhou.")
            }

            // Local Live View is a secondary consumer of the SAME OES camera texture.
            // Limit it to 15 FPS so the phone display can never back-pressure 4K H.264.
            val preview = eglPreviewSurface
            if (
                preview != EGL14.EGL_NO_SURFACE &&
                presentationNs - lastLocalPreviewNs >= LOCAL_PREVIEW_INTERVAL_NS
            ) {
                try {
                    if (EGL14.eglMakeCurrent(eglDisplay, preview, preview, eglContext)) {
                        val w = localPreviewWidth.takeIf { it > 0 } ?: outputWidth
                        val h = localPreviewHeight.takeIf { it > 0 } ?: outputHeight
                        drawCurrentTexture(w, h)
                        if (EGL14.eglSwapBuffers(eglDisplay, preview)) {
                            lastLocalPreviewNs = presentationNs
                        }
                    }
                } catch (_: Exception) {
                    // The phone UI preview is optional. Never stop network H.264 for it.
                    destroyLocalPreviewEglSurface()
                }
            }
        } catch (ex: Exception) {
            fail(
                "Falha ao renderizar frame GPU: " +
                    (ex.message ?: ex.javaClass.simpleName)
            )
        }
    }

'''
gpu = gpu[:start] + new_render + gpu[end:]

# Destroy secondary EGL surface before terminating the shared display/context.
gpu = replace_once(
    gpu,
    '''        runCatching { cameraTexture?.release() }
        cameraTexture = null

        runCatching { mjpegReader?.setOnImageAvailableListener(null, null) }''',
    '''        runCatching { cameraTexture?.release() }
        cameraTexture = null

        destroyLocalPreviewEglSurface()
        localPreviewSurface = null
        localPreviewWidth = 0
        localPreviewHeight = 0
        lastLocalPreviewNs = 0L

        runCatching { mjpegReader?.setOnImageAvailableListener(null, null) }''',
    "gpu release local EGL surface",
)
gpu = replace_once(
    gpu,
    '''        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        glProgram = 0''',
    '''        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        eglPreviewSurface = EGL14.EGL_NO_SURFACE
        eglConfig = null
        glProgram = 0''',
    "gpu reset EGL fields",
)
gpu = replace_once(
    gpu,
    '''        private const val MJPEG_CAPTURE_TIMEOUT_MS = 1_500L

        private const val VERTEX_SHADER''',
    '''        private const val MJPEG_CAPTURE_TIMEOUT_MS = 1_500L
        private const val LOCAL_PREVIEW_INTERVAL_NS = 66_666_667L

        private const val VERTEX_SHADER''',
    "gpu local preview fps constant",
)
GPU.write_text(gpu, encoding="utf-8")

print("Build 40 dual-preview + H264 quality patch applied")
