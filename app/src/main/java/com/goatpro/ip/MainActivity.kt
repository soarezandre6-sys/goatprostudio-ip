package com.goatpro.ip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var addressText: TextView
    private lateinit var streamInfoText: TextView
    private lateinit var performanceText: TextView
    private lateinit var streamButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var torchButton: Button
    private lateinit var audioButton: Button
    private lateinit var copyAddressButton: Button
    private lateinit var websiteButton: Button
    private lateinit var cameraLensSpinner: Spinner
    private lateinit var resolutionSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var rotationSpinner: Spinner
    private lateinit var autoDiscoverySwitch: SwitchCompat
    private lateinit var backgroundStreamingSwitch: SwitchCompat

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var orientationListener: OrientationEventListener
    private var currentCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraLensOptions: List<CameraLensOption> = emptyList()
    private var selectedCameraOption: CameraLensOption? = null
    private var autoSurfaceRotation = Surface.ROTATION_0

    @Volatile
    private var selectedResolution = ResolutionOption(
        key = "1920x1080",
        label = "Full HD · 1920×1080",
        size = Size(1920, 1080),
        highResolution = false,
        directFront4k = false
    )

    private var availableResolutionOptions: List<ResolutionOption> =
        listOf(selectedResolution)

    @Volatile
    private var selectedQualityProfile = QualityProfile.BALANCED

    @Volatile
    private var streamJpegQuality = QualityProfile.BALANCED.jpegQuality

    @Volatile
    private var streamTargetFps = QualityProfile.BALANCED.targetFps

    @Volatile
    private var selectedRotationMode = RotationMode.AUTO

    private var nextEncodeDueNs = 0L
    private var torchEnabled = false
    private var audioEnabled = false
    private var autoDiscoveryEnabled = true
    private var backgroundStreamingEnabled = true
    private var backgroundHeadless = false
    private var backgroundDirectActive = false
    private var actualStreamWidth = 0
    private var actualStreamHeight = 0
    private var fallbackResolutionAfterFront4kFailure: ResolutionOption? = null
    private var autoRestartStreamAfterCameraBind = false
    private var streamBitrateBps = 0
    private var watermarkEnabled = true
    private var preferredCameraKey: String? = null
    private var preferredResolutionKey: String? = null

    private var metricsWindowStartedNs = 0L
    private var analysisFrameCount = 0
    private var encodedFrameCount = 0
    private var encodedBytes = 0L
    private var encodeTimeTotalNs = 0L

    @Volatile
    private var manualExposureEnabled = false

    @Volatile
    private var manualIso = 100

    @Volatile
    private var manualExposureTimeNs = 10_000_000L

    @Volatile
    private var manualRequestApplied = false

    @Volatile
    private var manualControlStatus = "Exposição automática"

    @Volatile
    private var manualFocusEnabled = false

    @Volatile
    private var manualFocusDiopters = 0f

    @Volatile
    private var selectedWhiteBalanceMode = CaptureRequest.CONTROL_AWB_MODE_AUTO

    @Volatile
    private var selectedAntibandingMode = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO

    @Volatile
    private var selectedSceneMode = CaptureRequest.CONTROL_SCENE_MODE_DISABLED

    @Volatile
    private var manualFrameDurationNs = 0L

    @Volatile
    private var selectedAperture: Float? = null

    @Volatile
    private var selectedFilterDensity: Float? = null

    private val server by lazy {
        MjpegServer(8080, object : MjpegServer.Listener {
            override fun onVideoClientCountChanged(count: Int) {
                runOnUiThread { updateConnectionStatus(count) }
            }

            override fun onCameraControl(action: String, value: String?): String {
                runOnUiThread { applyRemoteCameraControl(action, value) }
                return "Comando enviado: $action"
            }

            override fun cameraControlStateJson(): String = buildCameraControlStateJson()
        })
    }

    private val rtspServer: RtspH264Server by lazy {
        RtspH264Server(8554, object : RtspH264Server.Listener {
            override fun onActiveClientCountChanged(count: Int) {
                if (count > 0) {
                    when {
                        backgroundH264Streamer.isRunning() ||
                            backgroundH264Streamer.isStarting() ->
                            backgroundH264Streamer.requestKeyFrame()
                        front4kDirectStreamer.isRunning() ||
                            front4kDirectStreamer.isStarting() ->
                            front4kDirectStreamer.requestKeyFrame()
                        else -> h264Encoder.requestKeyFrame()
                    }
                } else if (
                    !front4kDirectStreamer.isRunning() &&
                    !backgroundH264Streamer.isRunning()
                ) {
                    h264Encoder.stop()
                }
                runOnUiThread { updateConnectionStatus(server.videoClientCount()) }
            }
        })
    }

    private val h264Encoder: H264Encoder by lazy {
        H264Encoder(object : H264Encoder.Listener {
            override fun onAccessUnit(
                data: ByteArray,
                presentationTimeUs: Long,
                keyFrame: Boolean,
                codecConfig: Boolean
            ) {
                rtspServer.onAccessUnit(
                    data,
                    presentationTimeUs,
                    keyFrame,
                    codecConfig
                )
            }
        })
    }

    private val backgroundH264Streamer:
        BackgroundH264Streamer by lazy {
        BackgroundH264Streamer(
            this,
            object : BackgroundH264Streamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    rtspServer.onAccessUnit(
                        data,
                        presentationTimeUs,
                        keyFrame,
                        codecConfig
                    )
                }

                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int
                ) {
                    backgroundDirectActive = true
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        backgroundDirectActive = false
                        if (
                            backgroundHeadless &&
                            isStreamingActive() &&
                            !selectedResolution.directFront4k
                        ) {
                            // Safe fallback: keep streaming with ImageAnalysis only.
                            startCamera()
                        }
                    }
                }

                override fun onStopped() {
                    backgroundDirectActive = false
                }
            }
        )
    }

    private val front4kDirectStreamer:
        Front4kDirectStreamer by lazy {
        Front4kDirectStreamer(
            this,
            object : Front4kDirectStreamer.Listener {
                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    rtspServer.onAccessUnit(
                        data,
                        presentationTimeUs,
                        keyFrame,
                        codecConfig
                    )
                }

                override fun onStarted(
                    width: Int,
                    height: Int,
                    fps: Int,
                    bitrate: Int
                ) {
                    runOnUiThread {
                        actualStreamWidth = width
                        actualStreamHeight = height
                        statusText.text =
                            "TRANSMITINDO 4K FRONTAL PARA O GOAT PRO STUDIO"
                        statusText.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.green
                            )
                        )
                        performanceText.text =
                            "4K frontal Camera1/MediaRecorder • H.264 • " +
                                fps + " FPS • " +
                                String.format(
                                    java.util.Locale.US,
                                    "%.1f Mbps",
                                    bitrate / 1_000_000.0
                                )
                        updateStreamInfo()
                        refreshAddress()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        front4kDirectStreamer.stop()
                        rtspServer.stop()
                        server.stop()
                        audioCapture.stop()
                        server.setAudioEnabled(false)
                        updateConnectionStatus(0)

                        val fallback =
                            fallbackResolutionAfterFront4kFailure
                                ?: availableResolutionOptions.firstOrNull {
                                    !it.directFront4k &&
                                        it.key == "1920x1080"
                                }
                                ?: availableResolutionOptions.firstOrNull {
                                    !it.directFront4k
                                }

                        if (fallback != null) {
                            fallbackResolutionAfterFront4kFailure = null
                            selectedResolution = fallback
                            applyHighResolutionDefaults(fallback)

                            if (::resolutionSpinner.isInitialized) {
                                val index =
                                    availableResolutionOptions.indexOfFirst {
                                        it.key == fallback.key
                                    }
                                if (index >= 0) {
                                    resolutionSpinner.setSelection(index)
                                }
                            }

                            actualStreamWidth = 0
                            actualStreamHeight = 0
                            nextEncodeDueNs = 0L
                            resetPerformanceStats()
                            updateStreamInfo()
                            refreshAddress()

                            setError(
                                "4K frontal recusado • " + message +
                                    " • voltando para " + fallback.label
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "4K frontal recusado. Voltando para " +
                                    fallback.label + ".",
                                Toast.LENGTH_LONG
                            ).show()

                            autoRestartStreamAfterCameraBind = true
                            startCamera()
                            return@runOnUiThread
                        }

                        setError("4K frontal: " + message)
                        streamButton.text = "Iniciar transmissão"
                        streamButton.setBackgroundResource(
                            R.drawable.bg_button_primary
                        )
                        streamButton.backgroundTintList = null
                        streamButton.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                R.color.black
                            )
                        )
                        startCamera()
                    }
                }

                override fun onStopped() = Unit
            }
        )
    }

    private val audioCapture by lazy {
        AudioCapture(this, server::offerAudio)
    }

    private val discoveryResponder by lazy {
        DiscoveryResponder(
            httpPort = 8080,
            isStreaming = { server.isRunning() },
            isAudioEnabled = { audioEnabled && server.isAudioEnabled() },
            rtspCodec = { "H264" }
        )
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setError("PERMISSÃO DE CÂMERA NECESSÁRIA")
        }
    }

    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioEnabled = granted
        server.setAudioEnabled(granted)
        if (granted && server.isRunning()) {
            if (!audioCapture.start()) {
                audioEnabled = false
                server.setAudioEnabled(false)
                Toast.makeText(
                    this,
                    "Não foi possível iniciar o microfone.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        if (!granted) {
            Toast.makeText(
                this,
                "Permissão de microfone não concedida.",
                Toast.LENGTH_SHORT
            ).show()
        }
        updateAudioButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        addressText = findViewById(R.id.addressText)
        streamInfoText = findViewById(R.id.streamInfoText)
        performanceText = findViewById(R.id.performanceText)
        streamButton = findViewById(R.id.streamButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        torchButton = findViewById(R.id.torchButton)
        audioButton = findViewById(R.id.audioButton)
        copyAddressButton = findViewById(R.id.copyAddressButton)
        websiteButton = findViewById(R.id.websiteButton)
        cameraLensSpinner = findViewById(R.id.cameraLensSpinner)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        rotationSpinner = findViewById(R.id.rotationSpinner)
        autoDiscoverySwitch = findViewById(R.id.autoDiscoverySwitch)
        backgroundStreamingSwitch = findViewById(R.id.backgroundStreamingSwitch)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()
        restoreSmartLinkState()
        StreamingCameraLifecycle.setActive(true)

        applyPreviewAspectRatio()
        setupCameraLensSelector()
        setupResolutionSelector()
        setupQualitySelector()
        setupRotationSelector()
        setupAutomaticDiscovery()
        setupBackgroundStreaming()
        refreshAddress()
        updateStreamInfo()
        updateConnectionStatus(0)
        updateAudioButton()
        updateTorchButton()

        streamButton.setOnClickListener {
            if (
                server.isRunning() ||
                front4kDirectStreamer.isRunning() ||
                front4kDirectStreamer.isStarting() ||
                backgroundH264Streamer.isRunning() ||
                backgroundH264Streamer.isStarting()
            ) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        switchCameraButton.setOnClickListener {
            cycleCameraLens()
        }

        torchButton.setOnClickListener { toggleTorch() }
        audioButton.setOnClickListener { toggleAudio() }
        copyAddressButton.setOnClickListener { copyAddressToClipboard() }
        websiteButton.setOnClickListener { openGoatProStudioWebsite() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun smartLinkPrefs() =
        getSharedPreferences("goat_cam_smart_link", Context.MODE_PRIVATE)

    private fun proPresetPrefs() =
        getSharedPreferences("goat_cam_pro_presets", Context.MODE_PRIVATE)

    private fun restoreSmartLinkState() {
        val prefs = smartLinkPrefs()
        preferredCameraKey = prefs.getString("camera_key", null)
        preferredResolutionKey = prefs.getString("resolution_key", null)
        selectedQualityProfile = runCatching {
            QualityProfile.valueOf(
                prefs.getString(
                    "quality",
                    selectedQualityProfile.name
                ).orEmpty()
            )
        }.getOrDefault(QualityProfile.BALANCED)
        streamJpegQuality = prefs.getInt(
            "jpeg_quality",
            selectedQualityProfile.jpegQuality
        ).coerceIn(1, 100)
        streamTargetFps = prefs.getInt(
            "target_fps",
            selectedQualityProfile.targetFps
        ).let { if (it <= 0) 0 else it.coerceIn(5, 60) }
        streamBitrateBps = prefs.getInt("bitrate_bps", 0)
            .coerceIn(0, 60_000_000)
        watermarkEnabled = prefs.getBoolean("watermark_enabled", true)
        selectedRotationMode = runCatching {
            RotationMode.valueOf(
                prefs.getString(
                    "rotation",
                    selectedRotationMode.name
                ).orEmpty()
            )
        }.getOrDefault(RotationMode.AUTO)
    }

    private fun saveSmartLinkState() {
        smartLinkPrefs().edit()
            .putString(
                "camera_key",
                selectedCameraOption?.key ?: preferredCameraKey
            )
            .putString(
                "resolution_key",
                selectedResolution.key
            )
            .putString("quality", selectedQualityProfile.name)
            .putInt("jpeg_quality", streamJpegQuality)
            .putInt("target_fps", streamTargetFps)
            .putInt("bitrate_bps", streamBitrateBps)
            .putBoolean("watermark_enabled", watermarkEnabled)
            .putString("rotation", selectedRotationMode.name)
            .apply()
    }

    private fun saveProPreset(slot: Int) {
        val safeSlot = slot.coerceIn(1, 3)
        val prefix = "preset_${safeSlot}_"
        val camera = currentCamera
        val zoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val ev = camera?.cameraInfo?.exposureState?.exposureCompensationIndex ?: 0
        proPresetPrefs().edit()
            .putBoolean(prefix + "saved", true)
            .putString(prefix + "camera_key", selectedCameraOption?.key)
            .putString(prefix + "resolution_key", selectedResolution.key)
            .putString(prefix + "quality", selectedQualityProfile.name)
            .putInt(prefix + "jpeg_quality", streamJpegQuality)
            .putInt(prefix + "target_fps", streamTargetFps)
            .putInt(prefix + "bitrate_bps", streamBitrateBps)
            .putBoolean(prefix + "watermark_enabled", watermarkEnabled)
            .putString(prefix + "rotation", selectedRotationMode.name)
            .putFloat(prefix + "zoom", zoom)
            .putInt(prefix + "ev", ev)
            .putBoolean(prefix + "manual_exposure", manualExposureEnabled)
            .putInt(prefix + "iso", manualIso)
            .putLong(prefix + "shutter_ns", manualExposureTimeNs)
            .putLong(prefix + "frame_duration_ns", manualFrameDurationNs)
            .putBoolean(prefix + "manual_focus", manualFocusEnabled)
            .putFloat(prefix + "focus_diopters", manualFocusDiopters)
            .putInt(prefix + "white_balance", selectedWhiteBalanceMode)
            .putInt(prefix + "antibanding", selectedAntibandingMode)
            .putInt(prefix + "scene_mode", selectedSceneMode)
            .apply()
        Toast.makeText(
            this,
            "Preset $safeSlot salvo com câmera e controles.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadProPreset(slot: Int) {
        val safeSlot = slot.coerceIn(1, 3)
        val prefix = "preset_${safeSlot}_"
        val prefs = proPresetPrefs()
        if (!prefs.getBoolean(prefix + "saved", false)) {
            Toast.makeText(
                this,
                "Preset Pro $safeSlot ainda não foi salvo.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        selectedQualityProfile = runCatching {
            QualityProfile.valueOf(
                prefs.getString(
                    prefix + "quality",
                    QualityProfile.BALANCED.name
                ).orEmpty()
            )
        }.getOrDefault(QualityProfile.BALANCED)
        streamJpegQuality = prefs.getInt(
            prefix + "jpeg_quality",
            selectedQualityProfile.jpegQuality
        ).coerceIn(1, 100)
        streamTargetFps = prefs.getInt(
            prefix + "target_fps",
            selectedQualityProfile.targetFps
        ).let { if (it <= 0) 0 else it.coerceIn(5, 60) }
        streamBitrateBps = prefs.getInt(
            prefix + "bitrate_bps",
            0
        ).coerceIn(0, 60_000_000)
        watermarkEnabled = prefs.getBoolean(
            prefix + "watermark_enabled",
            true
        )
        val presetZoom = prefs.getFloat(prefix + "zoom", 1f)
        val presetEv = prefs.getInt(prefix + "ev", 0)
        manualExposureEnabled = prefs.getBoolean(prefix + "manual_exposure", false)
        manualIso = prefs.getInt(prefix + "iso", manualIso)
        manualExposureTimeNs = prefs.getLong(prefix + "shutter_ns", manualExposureTimeNs)
        manualFrameDurationNs = prefs.getLong(prefix + "frame_duration_ns", 0L)
        manualFocusEnabled = prefs.getBoolean(prefix + "manual_focus", false)
        manualFocusDiopters = prefs.getFloat(prefix + "focus_diopters", 0f)
        selectedWhiteBalanceMode = prefs.getInt(prefix + "white_balance", selectedWhiteBalanceMode)
        selectedAntibandingMode = prefs.getInt(prefix + "antibanding", selectedAntibandingMode)
        selectedSceneMode = prefs.getInt(prefix + "scene_mode", selectedSceneMode)
        selectedRotationMode = runCatching {
            RotationMode.valueOf(
                prefs.getString(
                    prefix + "rotation",
                    RotationMode.AUTO.name
                ).orEmpty()
            )
        }.getOrDefault(RotationMode.AUTO)
        preferredCameraKey = prefs.getString(prefix + "camera_key", null)
        preferredResolutionKey = prefs.getString(
            prefix + "resolution_key",
            null
        )

        preferredCameraKey?.let { key ->
            cameraLensOptions.firstOrNull { it.key == key }
                ?.takeIf { it.key != selectedCameraOption?.key }
                ?.let { selectCameraLens(it) }
        }

        previewView.postDelayed({
            refreshResolutionOptions()
            preferredResolutionKey?.let { key ->
                availableResolutionOptions.firstOrNull { it.key == key }
                    ?.takeIf { it.key != selectedResolution.key }
                    ?.let { selectResolutionOption(it) }
            }
            if (::qualitySpinner.isInitialized) {
                qualitySpinner.setSelection(
                    QualityProfile.entries.indexOf(selectedQualityProfile)
                )
            }
            if (::rotationSpinner.isInitialized) {
                rotationSpinner.setSelection(
                    RotationMode.entries.indexOf(selectedRotationMode)
                )
            }
            h264Encoder.stop()
            updateStreamInfo()
            saveSmartLinkState()

            previewView.postDelayed({
                val camera = currentCamera
                if (camera != null) {
                    camera.cameraInfo.zoomState.value?.let { state ->
                        runCatching {
                            camera.cameraControl.setZoomRatio(
                                presetZoom.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                            )
                        }
                    }
                    if (!manualExposureEnabled) {
                        val state = camera.cameraInfo.exposureState
                        val range = state.exposureCompensationRange
                        runCatching {
                            camera.cameraControl.setExposureCompensationIndex(
                                presetEv.coerceIn(range.lower, range.upper)
                            )
                        }
                    }
                    applyAutomaticSensorControls()
                    applyLensControls()
                    if (manualFocusEnabled) applyManualFocus()
                    if (manualExposureEnabled) applyManualExposure()
                }
            }, 450L)

            Toast.makeText(
                this,
                "Preset $safeSlot carregado e aplicado.",
                Toast.LENGTH_SHORT
            ).show()
        }, 450L)
    }

    private fun openGoatProStudioWebsite() {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://goatprostudio.com.br")
            ).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Não foi possível abrir o site neste aparelho.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun applyPreviewAspectRatio() {
        previewView.post {
            val width = previewView.width
            if (width <= 0) return@post

            val targetHeight = (width * PREVIEW_HEIGHT_RATIO).toInt()
            if (previewView.layoutParams.height != targetHeight) {
                previewView.layoutParams = previewView.layoutParams.apply {
                    height = targetHeight
                }
                previewView.requestLayout()
            }
        }
    }

    private data class ResolutionOption(
        val key: String,
        val label: String,
        val size: Size,
        val highResolution: Boolean,
        val directFront4k: Boolean,
        val directCameraId: String? = null,
        val directFps: Int? = null,
        val directBitrate: Int? = null
    )

    private fun resolutionKey(size: Size): String =
        size.width.toString() + "x" + size.height.toString()

    private fun resolutionLabel(size: Size): String {
        val prefix = when {
            size.width == 3840 && size.height == 2160 -> "4K UHD · "
            size.width == 2560 && size.height == 1440 -> "2K QHD · "
            size.width == 1920 && size.height == 1080 -> "Full HD · "
            size.width == 1280 && size.height == 720 -> "HD · "
            size.width == 960 && size.height == 540 -> "qHD · "
            else -> ""
        }
        return prefix + size.width + "×" + size.height
    }

    private fun canonicalLandscapeSize(size: Size): Size =
        if (size.width >= size.height) {
            Size(size.width, size.height)
        } else {
            Size(size.height, size.width)
        }

    private fun isUsableVideoResolution(size: Size): Boolean {
        val landscape = canonicalLandscapeSize(size)
        if (landscape.width < 640 || landscape.height < 360) return false
        if (landscape.width > 3840 || landscape.height > 2160) return false

        val aspect = landscape.width.toFloat() / landscape.height.toFloat()
        val target = 16f / 9f
        return kotlin.math.abs(aspect - target) <= 0.03f
    }

    private data class CameraLensOption(
        val key: String,
        val label: String,
        val logicalCameraId: String,
        val physicalCameraId: String?,
        val facing: Int,
        val focalMetric: Float,
        val focalLengthMm: Float?,
        val zoomRatio: Float = 1f
    )

    private fun cameraFocalMetric(chars: CameraCharacteristics): Float {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?: return Float.NaN
        val sensorWidth = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?.width
            ?: return focal
        if (sensorWidth <= 0f) return focal
        return focal / sensorWidth
    }

    private fun cameraFocalLength(chars: CameraCharacteristics): Float? =
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()

    private fun lensLabel(
        role: String,
        focalLengthMm: Float?,
        suffix: String? = null
    ): String {
        val focal = focalLengthMm?.let {
            " • " + String.format(java.util.Locale.US, "%.1f mm", it)
        } ?: ""
        val extra = suffix?.let { " • " + it } ?: ""
        return role + focal + extra
    }

    private fun classifyRearRole(
        metric: Float,
        mainMetric: Float,
        fallbackIndex: Int,
        count: Int
    ): String {
        // On devices such as the Galaxy S21 the tele module can have a focal
        // metric close to the main camera. If three real rear sensors are
        // exposed, preserve them by ordered field of view instead of requiring
        // a large focal-ratio gap.
        if (count >= 3) {
            return when (fallbackIndex) {
                0 -> "Ultra-wide"
                count - 1 -> "Tele"
                else -> "Traseira principal"
            }
        }

        if (!metric.isFinite() || !mainMetric.isFinite() || mainMetric <= 0f) {
            return "Traseira principal"
        }

        val ratio = metric / mainMetric
        return when {
            ratio < 0.82f -> "Ultra-wide"
            ratio > 1.22f -> "Tele"
            else -> "Traseira principal"
        }
    }

    private fun buildCameraLensOptions(): List<CameraLensOption> {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList.toList()

        data class RawCamera(
            val logicalId: String,
            val physicalId: String?,
            val facing: Int,
            val metric: Float,
            val focal: Float?
        )

        val backLogicalIds = ids.filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }.getOrDefault(false)
        }

        val frontIds = ids.filter { id ->
            runCatching {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_FRONT
            }.getOrDefault(false)
        }

        val primaryBackId = backLogicalIds.maxByOrNull { id ->
            if (Build.VERSION.SDK_INT >= 28) {
                runCatching {
                    manager.getCameraCharacteristics(id).physicalCameraIds.size
                }.getOrDefault(0)
            } else {
                0
            }
        }

        /*
         * Some OEMs expose rear lenses using a mixture of standalone logical
         * cameras and physical children of a logical multi-camera. Merge both
         * representations instead of discarding one of them.
         */
        val rearCandidates = mutableListOf<RawCamera>()

        backLogicalIds.forEach { logicalId ->
            runCatching {
                val chars = manager.getCameraCharacteristics(logicalId)
                rearCandidates.add(
                    RawCamera(
                        logicalId = logicalId,
                        physicalId = null,
                        facing = CameraCharacteristics.LENS_FACING_BACK,
                        metric = cameraFocalMetric(chars),
                        focal = cameraFocalLength(chars)
                    )
                )
            }

            if (Build.VERSION.SDK_INT >= 28) {
                val physicalIds = runCatching {
                    manager.getCameraCharacteristics(logicalId).physicalCameraIds
                }.getOrDefault(emptySet())

                physicalIds.forEach { physicalId ->
                    runCatching {
                        val chars = manager.getCameraCharacteristics(physicalId)
                        rearCandidates.add(
                            RawCamera(
                                logicalId = logicalId,
                                physicalId = physicalId,
                                facing = CameraCharacteristics.LENS_FACING_BACK,
                                metric = cameraFocalMetric(chars),
                                focal = cameraFocalLength(chars)
                            )
                        )
                    }
                }
            }
        }

        val sortedRearCandidates = rearCandidates.sortedWith(compareBy<RawCamera> {
            if (it.metric.isFinite()) it.metric else Float.MAX_VALUE
        })

        val rear = mutableListOf<RawCamera>()
        sortedRearCandidates.forEach { row ->
            // A physical child can also appear as its own standalone logical
            // camera. Those two entries are the same sensor. Cameras with
            // merely similar focal lengths are NOT duplicates (important for
            // the S21 main + tele pair).
            val sensorId = row.physicalId ?: row.logicalId
            val duplicateIndex = rear.indexOfFirst { existing ->
                (existing.physicalId ?: existing.logicalId) == sensorId
            }

            if (duplicateIndex < 0) {
                rear.add(row)
            } else {
                val existing = rear[duplicateIndex]
                // Prefer the standalone logical ID when Samsung exposes both:
                // it often advertises richer stream combinations.
                if (existing.physicalId != null && row.physicalId == null) {
                    rear[duplicateIndex] = row
                }
            }
        }

        val front = frontIds.mapNotNull { id ->
            runCatching {
                val chars = manager.getCameraCharacteristics(id)
                RawCamera(
                    logicalId = id,
                    physicalId = null,
                    facing = CameraCharacteristics.LENS_FACING_FRONT,
                    metric = cameraFocalMetric(chars),
                    focal = cameraFocalLength(chars)
                )
            }.getOrNull()
        }

        val mainMetric = if (rear.isNotEmpty()) {
            val logicalMetric = primaryBackId?.let { id ->
                runCatching {
                    cameraFocalMetric(manager.getCameraCharacteristics(id))
                }.getOrNull()
            }
            if (logicalMetric != null && logicalMetric.isFinite()) {
                rear.minByOrNull {
                    kotlin.math.abs(it.metric - logicalMetric)
                }?.metric ?: rear[rear.size / 2].metric
            } else {
                rear[rear.size / 2].metric
            }
        } else {
            Float.NaN
        }

        val labeled = mutableListOf<CameraLensOption>()
        rear.forEachIndexed { index, row ->
            val role = classifyRearRole(row.metric, mainMetric, index, rear.size)
            labeled.add(
                CameraLensOption(
                    key = "",
                    label = lensLabel(role, row.focal),
                    logicalCameraId = row.logicalId,
                    physicalCameraId = row.physicalId,
                    facing = CameraSelector.LENS_FACING_BACK,
                    focalMetric = row.metric,
                    focalLengthMm = row.focal
                )
            )
        }

        // Some Samsung devices keep the tele sensor behind the logical
        // rear camera instead of publishing it as a separate Camera2 ID.
        // In that case expose a Tele option through the logical camera and ask
        // the HAL for a 3x zoom ratio, which lets the OEM choose the tele
        // physical sensor internally when supported.
        val hasRealTele = labeled.any {
            it.label.startsWith("Tele", ignoreCase = true)
        }
        if (!hasRealTele && primaryBackId != null && Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val chars = manager.getCameraCharacteristics(primaryBackId)
                val zoomRange = chars.get(
                    CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
                )
                val maxZoom = zoomRange?.upper ?: 1f
                if (maxZoom >= 2.9f) {
                    labeled.add(
                        CameraLensOption(
                            key = "",
                            label = "Tele • 3x",
                            logicalCameraId = primaryBackId,
                            physicalCameraId = null,
                            facing = CameraSelector.LENS_FACING_BACK,
                            focalMetric =
                                if (mainMetric.isFinite()) mainMetric * 3f
                                else Float.MAX_VALUE,
                            focalLengthMm = null,
                            zoomRatio = 3f.coerceAtMost(maxZoom)
                        )
                    )
                }
            }
        }

        front.forEachIndexed { index, row ->
            val suffix = if (front.size > 1) "Frontal " + (index + 1) else null
            val role = if (suffix == null) "Frontal" else suffix
            labeled.add(
                CameraLensOption(
                    key = "",
                    label = lensLabel(role, row.focal),
                    logicalCameraId = row.logicalId,
                    physicalCameraId = null,
                    facing = CameraSelector.LENS_FACING_FRONT,
                    focalMetric = row.metric,
                    focalLengthMm = row.focal
                )
            )
        }

        return labeled.mapIndexed { index, option ->
            option.copy(key = "CAM" + index)
        }
    }

    private fun styledSpinnerAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(
            this,
            R.layout.spinner_item,
            R.id.spinnerText,
            labels
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    private fun setupCameraLensSelector() {
        cameraLensOptions = runCatching { buildCameraLensOptions() }
            .getOrDefault(emptyList())

        if (cameraLensOptions.isEmpty()) {
            cameraLensOptions = listOf(
                CameraLensOption(
                    key = "CAM0",
                    label = "Traseira",
                    logicalCameraId = "0",
                    physicalCameraId = null,
                    facing = CameraSelector.LENS_FACING_BACK,
                    focalMetric = Float.NaN,
                    focalLengthMm = null
                )
            )
        }

        selectedCameraOption =
            preferredCameraKey?.let { key ->
                cameraLensOptions.firstOrNull { it.key == key }
            }
                ?: cameraLensOptions.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK &&
                        it.label.contains("principal", ignoreCase = true)
                }
                ?: cameraLensOptions.firstOrNull {
                    it.facing == CameraSelector.LENS_FACING_BACK
                }
                ?: cameraLensOptions.first()

        lensFacing = selectedCameraOption?.facing ?: CameraSelector.LENS_FACING_BACK

        cameraLensSpinner.adapter =
            styledSpinnerAdapter(cameraLensOptions.map { it.label })
        cameraLensSpinner.setSelection(
            cameraLensOptions.indexOf(selectedCameraOption).coerceAtLeast(0)
        )

        cameraLensSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val option = cameraLensOptions.getOrNull(position) ?: return
                    selectCameraLens(option, updateSpinner = false)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun selectCameraLens(
        option: CameraLensOption,
        updateSpinner: Boolean = true
    ) {
        if (selectedCameraOption?.key == option.key) return

        if (torchEnabled) {
            currentCamera?.cameraControl?.enableTorch(false)
            torchEnabled = false
        }

        manualExposureEnabled = false
        manualFocusEnabled = false
        selectedCameraOption = option
        lensFacing = option.facing
        preferredCameraKey = option.key
        saveSmartLinkState()

        if (updateSpinner) {
            val index = cameraLensOptions.indexOfFirst { it.key == option.key }
            if (index >= 0 && cameraLensSpinner.selectedItemPosition != index) {
                cameraLensSpinner.setSelection(index)
            }
        }

        refreshResolutionOptions()
        startCamera()
    }

    private fun cycleCameraLens() {
        if (cameraLensOptions.isEmpty()) return
        val currentIndex = cameraLensOptions.indexOfFirst {
            it.key == selectedCameraOption?.key
        }.coerceAtLeast(0)
        val next = cameraLensOptions[(currentIndex + 1) % cameraLensOptions.size]
        selectCameraLens(next)
    }

    private fun supportedResolutionOptions(): List<ResolutionOption> {
        return try {
            val manager =
                getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val option = selectedCameraOption
            val cameraIds = listOfNotNull(
                option?.physicalCameraId,
                option?.logicalCameraId
            ).distinct()

            val standardKeys = setOf(
                "1280x720",
                "1920x1080",
                "2560x1440",
                "3840x2160"
            )
            val regular = linkedMapOf<String, Size>()
            val high = linkedMapOf<String, Size>()

            fun collectYuv(
                target: MutableMap<String, Size>,
                sizes: Array<Size>?
            ) {
                sizes.orEmpty().forEach { raw ->
                    val size = canonicalLandscapeSize(raw)
                    val key = resolutionKey(size)
                    if (
                        isUsableVideoResolution(size) &&
                        key in standardKeys
                    ) {
                        target[key] = size
                    }
                }
            }

            cameraIds.forEach { cameraId ->
                runCatching {
                    val map = manager
                        .getCameraCharacteristics(cameraId)
                        .get(
                            CameraCharacteristics
                                .SCALER_STREAM_CONFIGURATION_MAP
                        )

                    collectYuv(
                        regular,
                        map?.getOutputSizes(ImageFormat.YUV_420_888)
                    )
                    collectYuv(
                        high,
                        map?.getHighResolutionOutputSizes(
                            ImageFormat.YUV_420_888
                        )
                    )
                }
            }

            val combined = linkedMapOf<String, ResolutionOption>()
            regular.forEach { (key, size) ->
                combined[key] = ResolutionOption(
                    key = key,
                    label = resolutionLabel(size),
                    size = size,
                    highResolution =
                        size.width.toLong() * size.height.toLong() >
                            1920L * 1080L,
                    directFront4k = false
                )
            }
            high.forEach { (key, size) ->
                combined[key] = ResolutionOption(
                    key = key,
                    label = resolutionLabel(size),
                    size = size,
                    highResolution = true,
                    directFront4k = false
                )
            }

            // Samsung may expose front UHD only through its recording
            // profile/private encoder path, not through CameraX ImageAnalysis YUV.
            // Add one exact 3840x2160 option only for the selected front camera.
            if (
                option?.facing == CameraSelector.LENS_FACING_FRONT &&
                !combined.containsKey("3840x2160")
            ) {
                val profile = Front4kDirectStreamer.profileFor(
                    this,
                    option.logicalCameraId
                )
                if (profile != null) {
                    combined["3840x2160"] = ResolutionOption(
                        key = "3840x2160",
                        label = "4K UHD · 3840×2160 · frontal",
                        size = Size(3840, 2160),
                        highResolution = true,
                        directFront4k = true,
                        directCameraId = profile.cameraId,
                        directFps = profile.fps,
                        directBitrate = profile.bitrate
                    )
                }
            }

            val options = combined.values.sortedWith(
                compareBy<ResolutionOption> {
                    it.size.width.toLong() * it.size.height.toLong()
                }.thenBy { it.size.width }
            )

            if (options.isNotEmpty()) {
                options
            } else {
                listOf(
                    ResolutionOption(
                        "1280x720",
                        "HD · 1280×720",
                        Size(1280, 720),
                        false,
                        false
                    ),
                    ResolutionOption(
                        "1920x1080",
                        "Full HD · 1920×1080",
                        Size(1920, 1080),
                        false,
                        false
                    )
                )
            }
        } catch (_: Exception) {
            listOf(
                ResolutionOption(
                    "1280x720",
                    "HD · 1280×720",
                    Size(1280, 720),
                    false,
                    false
                ),
                ResolutionOption(
                    "1920x1080",
                    "Full HD · 1920×1080",
                    Size(1920, 1080),
                    false,
                    false
                )
            )
        }
    }

    private fun refreshResolutionOptions() {
        availableResolutionOptions = supportedResolutionOptions()

        val stillAvailable = availableResolutionOptions.firstOrNull {
            it.key == selectedResolution.key
        }

        selectedResolution = preferredResolutionKey?.let { key ->
            availableResolutionOptions.firstOrNull { it.key == key }
        }
            ?: stillAvailable
            ?: availableResolutionOptions.firstOrNull { it.key == "1920x1080" }
            ?: availableResolutionOptions
                .filter {
                    it.size.width.toLong() * it.size.height.toLong() <= 1920L * 1080L
                }
                .maxByOrNull {
                    it.size.width.toLong() * it.size.height.toLong()
                }
            ?: availableResolutionOptions.first()

        resolutionSpinner.adapter =
            styledSpinnerAdapter(availableResolutionOptions.map { it.label })
        resolutionSpinner.setSelection(
            availableResolutionOptions.indexOfFirst {
                it.key == selectedResolution.key
            }.coerceAtLeast(0)
        )
    }

    private fun applyHighResolutionDefaults(option: ResolutionOption) {
        val width = option.size.width
        when {
            option.directFront4k -> {
                streamJpegQuality = 50
                streamTargetFps = option.directFps ?: 30
                selectedQualityProfile = QualityProfile.CUSTOM
            }
            width >= 3840 -> {
                streamJpegQuality = 50
                streamTargetFps = 10
                selectedQualityProfile = QualityProfile.CUSTOM
            }
            width > 1920 -> {
                streamJpegQuality = 58
                streamTargetFps = 15
                selectedQualityProfile = QualityProfile.CUSTOM
            }
            else -> return
        }

        if (::qualitySpinner.isInitialized) {
            qualitySpinner.setSelection(
                QualityProfile.entries.indexOf(QualityProfile.CUSTOM)
            )
        }
    }

    private fun selectResolutionOption(newOption: ResolutionOption) {
        val option = availableResolutionOptions.firstOrNull {
            it.key == newOption.key
        } ?: run {
            Toast.makeText(
                this,
                newOption.label + " não está disponível nesta câmera.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (option.key == selectedResolution.key) return

        val transportModeChanged =
            option.directFront4k != selectedResolution.directFront4k
        val wasStreaming =
            server.isRunning() ||
                front4kDirectStreamer.isRunning() ||
                front4kDirectStreamer.isStarting()

        if (
            option.directFront4k &&
            !selectedResolution.directFront4k &&
            wasStreaming
        ) {
            fallbackResolutionAfterFront4kFailure = selectedResolution
        }

        if (transportModeChanged && wasStreaming) {
            autoRestartStreamAfterCameraBind = true
            stopStreaming()
        }

        selectedResolution = option
        preferredResolutionKey = option.key
        applyHighResolutionDefaults(option)
        saveSmartLinkState()
        actualStreamWidth = 0
        actualStreamHeight = 0
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        updateStreamInfo()
        applyPreviewAspectRatio()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    private fun findResolutionOption(value: String?): ResolutionOption? {
        val requested = value.orEmpty().trim()
        if (requested.isEmpty()) return null

        val aliasKey = when (requested.uppercase()) {
            "HD", "720P" -> "1280x720"
            "FHD", "1080P" -> "1920x1080"
            "QHD", "2K", "1440P" -> "2560x1440"
            "UHD", "4K", "2160P" -> "3840x2160"
            else -> requested.replace("×", "x").lowercase()
        }

        return availableResolutionOptions.firstOrNull {
            it.key.lowercase() == aliasKey
        }
    }

    private fun setupResolutionSelector() {
        refreshResolutionOptions()

        resolutionSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val option = availableResolutionOptions.getOrNull(position) ?: return
                    selectResolutionOption(option)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setupQualitySelector() {
        qualitySpinner.adapter =
            styledSpinnerAdapter(QualityProfile.entries.map { it.label })
        qualitySpinner.setSelection(QualityProfile.entries.indexOf(selectedQualityProfile))
        qualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val profile = QualityProfile.entries[position]
                if (profile != selectedQualityProfile) {
                    selectedQualityProfile = profile
                    if (profile != QualityProfile.CUSTOM) {
                        streamJpegQuality = profile.jpegQuality
                        streamTargetFps = profile.targetFps
                    }
                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    saveSmartLinkState()
                    if (server.isRunning()) {
                        Toast.makeText(
                            this@MainActivity,
                            "Perfil aplicado: " + profile.shortLabel,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupRotationSelector() {
        rotationSpinner.adapter =
            styledSpinnerAdapter(RotationMode.entries.map { it.label })
        rotationSpinner.setSelection(RotationMode.entries.indexOf(selectedRotationMode))
        rotationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = RotationMode.entries[position]
                if (mode != selectedRotationMode) {
                    selectedRotationMode = mode
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    applyCameraTargetRotation(
                        if (mode == RotationMode.AUTO) autoSurfaceRotation else Surface.ROTATION_0
                    )
                    updateStreamInfo()
                    saveSmartLinkState()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupAutomaticDiscovery() {
        val prefs = getSharedPreferences("goat_pro_ip", Context.MODE_PRIVATE)
        autoDiscoveryEnabled = prefs.getBoolean("auto_discovery_enabled", true)

        autoDiscoverySwitch.isChecked = autoDiscoveryEnabled
        updateAutomaticDiscoveryText()

        if (autoDiscoveryEnabled) {
            discoveryResponder.start()
        } else {
            discoveryResponder.stop()
        }

        autoDiscoverySwitch.setOnCheckedChangeListener { _, checked ->
            autoDiscoveryEnabled = checked
            prefs.edit().putBoolean("auto_discovery_enabled", checked).apply()

            if (checked) {
                discoveryResponder.start()
            } else {
                discoveryResponder.stop()
            }

            updateAutomaticDiscoveryText()
            updateConnectionStatus(server.videoClientCount())
            saveSmartLinkState()
            Toast.makeText(
                this,
                if (checked) {
                    "Conexão automática ativada"
                } else {
                    "Modo manual ativado: procure o IP no Studio"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupBackgroundStreaming() {
        val prefs = getSharedPreferences("goat_pro_ip", Context.MODE_PRIVATE)
        backgroundStreamingEnabled = prefs.getBoolean(
            "background_streaming_enabled",
            true
        )
        backgroundStreamingSwitch.isChecked = backgroundStreamingEnabled
        updateBackgroundStreamingText()

        backgroundStreamingSwitch.setOnCheckedChangeListener { _, checked ->
            backgroundStreamingEnabled = checked
            prefs.edit()
                .putBoolean("background_streaming_enabled", checked)
                .apply()
            updateBackgroundStreamingText()

            if (checked && isStreamingActive()) {
                startStreamingForegroundService()
            } else if (!checked) {
                stopStreamingForegroundService()
            }

            Toast.makeText(
                this,
                if (checked) {
                    "Segundo plano ativado: Home e tela apagada manterão a transmissão."
                } else {
                    "Segundo plano desativado."
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun updateBackgroundStreamingText() {
        if (!::backgroundStreamingSwitch.isInitialized) return
        backgroundStreamingSwitch.text = if (backgroundStreamingEnabled) {
            "Continuar transmitindo em segundo plano / tela apagada: LIGADO"
        } else {
            "Continuar transmitindo em segundo plano / tela apagada: DESLIGADO"
        }
    }

    private fun enterBackgroundCaptureMode() {
        if (!backgroundStreamingEnabled || !isStreamingActive()) return

        StreamingCameraLifecycle.setActive(true)
        startStreamingForegroundService()

        // The dedicated front 4K recorder is already UI-independent.
        if (selectedResolution.directFront4k) return
        if (backgroundHeadless) return

        backgroundHeadless = true

        val option = selectedCameraOption
        val useDirectRtsp =
            option != null &&
            rtspServer.activeClientCount() > 0 &&
            server.videoClientCount() == 0 &&
            selectedResolution.size.width <= 1920 &&
            selectedResolution.size.height <= 1080

        if (!useDirectRtsp) {
            // MJPEG or higher-resolution fallback still needs ImageAnalysis.
            startCamera()
            return
        }

        val zoom = currentCamera?.cameraInfo?.zoomState?.value?.zoomRatio
            ?: option.zoomRatio
            ?: 1f
        val targetFps =
            (if (streamTargetFps > 0) streamTargetFps else 20).coerceIn(10, 30)
        val targetBitrate = if (streamBitrateBps > 0) {
            streamBitrateBps
        } else {
            H264Encoder.recommendedBitrate(
                selectedResolution.size.width,
                selectedResolution.size.height,
                targetFps,
                streamJpegQuality
            )
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!backgroundHeadless || !isStreamingActive()) return@addListener
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                previewUseCase = null
                analysisUseCase = null
                currentCamera = null
                h264Encoder.stop()

                val started = backgroundH264Streamer.start(
                    logicalCameraId = option.logicalCameraId,
                    physicalCameraId = option.physicalCameraId,
                    targetWidth = selectedResolution.size.width,
                    targetHeight = selectedResolution.size.height,
                    targetFps = targetFps,
                    targetBitrate = targetBitrate,
                    targetZoomRatio = zoom
                )
                backgroundDirectActive = started
                if (!started) {
                    startCamera()
                }
            } catch (_: Exception) {
                backgroundDirectActive = false
                startCamera()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun exitBackgroundCaptureMode() {
        val wasHeadless = backgroundHeadless
        backgroundHeadless = false

        if (
            backgroundDirectActive ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
        ) {
            backgroundH264Streamer.stop()
            backgroundDirectActive = false
        }

        if (
            wasHeadless &&
            isStreamingActive() &&
            !selectedResolution.directFront4k
        ) {
            startCamera()
        }
    }

    private fun updateAutomaticDiscoveryText() {
        if (!::autoDiscoverySwitch.isInitialized) return
        autoDiscoverySwitch.text = if (autoDiscoveryEnabled) {
            "Conexão automática com o GOAT PRO Studio: LIGADA"
        } else {
            "Conexão automática com o GOAT PRO Studio: DESLIGADA · modo manual"
        }
    }

    private fun startCamera() {
        if (
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()
        ) return

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val cameraOption = selectedCameraOption
            val selector = if (cameraOption != null) {
                CameraSelector.Builder()
                    .addCameraFilter { cameraInfos ->
                        cameraInfos.filter { cameraInfo ->
                            runCatching {
                                Camera2CameraInfo.from(cameraInfo).cameraId ==
                                    cameraOption.logicalCameraId
                            }.getOrDefault(false)
                        }
                    }
                    .build()
            } else {
                CameraSelector.Builder().requireLensFacing(lensFacing).build()
            }
            val size = selectedResolution.size
            val targetRotation = if (selectedRotationMode == RotationMode.AUTO) {
                autoSurfaceRotation
            } else {
                Surface.ROTATION_0
            }

            if (selectedResolution.directFront4k) {
                if (
                    front4kDirectStreamer.isRunning() ||
                    front4kDirectStreamer.isStarting()
                ) {
                    return@addListener
                }

                val previewResolutionSelector =
                    ResolutionSelector.Builder()
                        .setAllowedResolutionMode(
                            ResolutionSelector
                                .PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                        )
                        .setAspectRatioStrategy(
                            AspectRatioStrategy
                                .RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy
                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(previewResolutionSelector)
                    .setTargetRotation(targetRotation)
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                try {
                    provider.unbindAll()
                    analysisUseCase = null
                    previewUseCase = preview
                    currentCamera =
                        provider.bindToLifecycle(StreamingCameraLifecycle, selector, preview)
                    torchEnabled = false
                    updateTorchButton()
                    if (!server.isRunning()) setReadyState()

                    if (autoRestartStreamAfterCameraBind) {
                        autoRestartStreamAfterCameraBind = false
                        startStreaming()
                    }
                } catch (_: Exception) {
                    previewUseCase = null
                    analysisUseCase = null
                    currentCamera = null
                    updateTorchButton()
                    setError("ERRO AO ABRIR PREVIEW 4K FRONTAL")
                }
                return@addListener
            }

            val analysisFallbackRule = if (selectedResolution.highResolution) {
                ResolutionStrategy.FALLBACK_RULE_NONE
            } else {
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
            }

            val analysisResolutionSelector = ResolutionSelector.Builder()
                .setAllowedResolutionMode(
                    if (selectedResolution.highResolution) {
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    } else {
                        ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                    }
                )
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        size,
                        analysisFallbackRule
                    )
                )
                .build()

            val previewTargetSize =
                if (selectedResolution.highResolution) Size(1280, 720) else size

            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAllowedResolutionMode(
                    ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                )
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        previewTargetSize,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val previewBuilder = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
                .setTargetRotation(targetRotation)

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(analysisResolutionSelector)
                .setTargetRotation(targetRotation)
                .setOutputImageRotationEnabled(true)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            cameraOption?.physicalCameraId?.let { physicalId ->
                Camera2Interop.Extender(previewBuilder)
                    .setPhysicalCameraId(physicalId)
                Camera2Interop.Extender(analysisBuilder)
                    .setPhysicalCameraId(physicalId)
            }

            val preview = previewBuilder
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = analysisBuilder
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor) { image ->
                        try {
                            if (!server.isRunning()) return@setAnalyzer

                            val profile = selectedQualityProfile
                            val now = System.nanoTime()
                            recordAnalysisFrame(now)

                            val targetFps = streamTargetFps
                            val frameIntervalNs = if (targetFps > 0) {
                                1_000_000_000L / targetFps
                            } else {
                                0L
                            }
                            if (frameIntervalNs > 0L && nextEncodeDueNs == 0L) {
                                nextEncodeDueNs = now
                            }
                            if (frameIntervalNs > 0L && now < nextEncodeDueNs) {
                                publishPerformanceStatsIfDue(now)
                                return@setAnalyzer
                            }

                            // Advance against a fixed timeline instead of resetting from
                            // the current camera frame. With a ~30 FPS camera and a 20 FPS
                            // target this yields the intended 2-of-3 cadence (~20 FPS),
                            // rather than the old 1-of-2 cadence (~15 FPS).
                            if (frameIntervalNs > 0L) {
                                nextEncodeDueNs += frameIntervalNs
                                if (now - nextEncodeDueNs > frameIntervalNs * 2L) {
                                    nextEncodeDueNs = now + frameIntervalNs
                                }
                            }

                            // CameraX rotates AUTO natively. The processed NV21 frame
                            // is shared by MJPEG and the hardware H.264 path so we do not
                            // perform the color/crop/rotation work twice.
                            val manualRotation = selectedRotationMode.offsetDegrees
                            val prepared = ImageUtils.imageProxyToNv21(
                                image = image,
                                rotationDegrees = manualRotation
                            )?.let { frame ->
                                WatermarkOverlay.apply(
                                    frame,
                                    watermarkEnabled
                                )
                            }

                            if (prepared != null) {
                                if (prepared.width != actualStreamWidth ||
                                    prepared.height != actualStreamHeight
                                ) {
                                    actualStreamWidth = prepared.width
                                    actualStreamHeight = prepared.height
                                    runOnUiThread { updateStreamInfo() }
                                }

                                // Keep MJPEG for compatibility, but skip JPEG compression
                                // when nobody is watching the MJPEG endpoint.
                                if (server.videoClientCount() > 0) {
                                    val jpegStartedNs = System.nanoTime()
                                    val jpeg = ImageUtils.nv21ToJpeg(
                                        prepared,
                                        streamJpegQuality
                                    )
                                    val jpegElapsedNs = System.nanoTime() - jpegStartedNs
                                    if (jpeg != null) {
                                        server.offerFrame(jpeg.bytes)
                                        recordEncodedFrame(jpeg.bytes.size, jpegElapsedNs)
                                    }
                                }

                                // RTSP H.264 is encoded by MediaCodec. With only an RTSP
                                // viewer connected there is no JPEG encode in the hot path.
                                if (rtspServer.activeClientCount() > 0) {
                                    val h264Fps =
                                        (if (streamTargetFps > 0) streamTargetFps else 30)
                                            .coerceIn(5, 60)
                                    val bitrate =
                                        if (streamBitrateBps > 0) {
                                            streamBitrateBps
                                        } else {
                                            H264Encoder.recommendedBitrate(
                                                prepared.width,
                                                prepared.height,
                                                h264Fps,
                                                streamJpegQuality
                                            )
                                        }
                                    if (h264Encoder.ensureStarted(
                                            prepared.width,
                                            prepared.height,
                                            h264Fps,
                                            bitrate
                                        )
                                    ) {
                                        h264Encoder.offerNv21(prepared, now)
                                    }
                                } else if (h264Encoder.isRunning()) {
                                    h264Encoder.stop()
                                }
                            }
                            publishPerformanceStatsIfDue(System.nanoTime())
                        } finally {
                            image.close()
                        }
                    }
                }

            try {
                provider.unbindAll()
                analysisUseCase = analysis
                if (backgroundHeadless && server.isRunning()) {
                    previewUseCase = null
                    currentCamera = provider.bindToLifecycle(
                        StreamingCameraLifecycle,
                        selector,
                        analysis
                    )
                } else {
                    previewUseCase = preview
                    currentCamera = provider.bindToLifecycle(
                        StreamingCameraLifecycle,
                        selector,
                        preview,
                        analysis
                    )
                }
                runCatching {
                    currentCamera?.cameraControl?.setZoomRatio(
                        cameraOption?.zoomRatio ?: 1f
                    )
                }
                manualExposureEnabled = false
                manualRequestApplied = false
                manualControlStatus = "Exposição automática"
                manualFocusEnabled = false
                manualFocusDiopters = 0f
                manualFrameDurationNs = 0L
                selectedAperture = availableApertures().firstOrNull()
                selectedFilterDensity = availableFilterDensities().firstOrNull()
                applyAutomaticSensorControls()
                applyLensControls()
                torchEnabled = false
                updateTorchButton()
                if (!server.isRunning()) setReadyState()

                if (autoRestartStreamAfterCameraBind) {
                    autoRestartStreamAfterCameraBind = false
                    startStreaming()
                }
            } catch (_: Exception) {
                previewUseCase = null
                analysisUseCase = null
                currentCamera = null
                updateTorchButton()
                setError("ERRO AO ABRIR A CÂMERA")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun resetPerformanceStats() {
        metricsWindowStartedNs = 0L
        analysisFrameCount = 0
        encodedFrameCount = 0
        encodedBytes = 0L
        encodeTimeTotalNs = 0L
        if (::performanceText.isInitialized) {
            runOnUiThread {
                performanceText.text = "Desempenho: aguardando transmissão…"
            }
        }
    }

    private fun recordAnalysisFrame(nowNs: Long) {
        if (metricsWindowStartedNs == 0L) metricsWindowStartedNs = nowNs
        analysisFrameCount++
    }

    private fun recordEncodedFrame(bytes: Int, elapsedNs: Long) {
        encodedFrameCount++
        encodedBytes += bytes.toLong()
        encodeTimeTotalNs += elapsedNs
    }

    private fun publishPerformanceStatsIfDue(nowNs: Long) {
        val started = metricsWindowStartedNs
        if (started == 0L) return
        val elapsedNs = nowNs - started
        if (elapsedNs < 1_000_000_000L) return

        val seconds = elapsedNs / 1_000_000_000.0
        val cameraFps = analysisFrameCount / seconds
        val jpegFps = encodedFrameCount / seconds
        val avgEncodeMs = if (encodedFrameCount > 0) {
            encodeTimeTotalNs / encodedFrameCount / 1_000_000.0
        } else {
            0.0
        }
        val megabitsPerSecond = if (seconds > 0.0) {
            encodedBytes * 8.0 / seconds / 1_000_000.0
        } else {
            0.0
        }

        analysisFrameCount = 0
        encodedFrameCount = 0
        encodedBytes = 0L
        encodeTimeTotalNs = 0L
        metricsWindowStartedNs = nowNs

        runOnUiThread {
            if (::performanceText.isInitialized) {
                performanceText.text =
                    "REAL: câmera %.1f FPS • JPEG %.1f FPS • encode %.1f ms • %.1f Mbps"
                        .format(cameraFps, jpegFps, avgEncodeMs, megabitsPerSecond)
            }
        }
    }

    private fun setupOrientationTracking() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                if (rotation == autoSurfaceRotation) return
                autoSurfaceRotation = rotation

                if (selectedRotationMode == RotationMode.AUTO) {
                    applyCameraTargetRotation(rotation)
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    runOnUiThread { updateStreamInfo() }
                }
            }
        }

        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
    }

    private fun applyCameraTargetRotation(rotation: Int) {
        previewUseCase?.targetRotation = rotation
        analysisUseCase?.targetRotation = rotation
    }

    private fun applyRemoteCameraControl(action: String, value: String?) {
        val camera = currentCamera
        when (action) {
            "resolution" -> {
                val option = findResolutionOption(value) ?: return
                if (option.key != selectedResolution.key) {
                    val index = availableResolutionOptions.indexOfFirst {
                        it.key == option.key
                    }
                    if (index >= 0) {
                        resolutionSpinner.setSelection(index)
                    } else {
                        selectResolutionOption(option)
                    }
                }
            }

            "quality" -> {
                val profile = runCatching {
                    QualityProfile.valueOf(value.orEmpty().uppercase())
                }.getOrNull() ?: return
                if (profile != selectedQualityProfile || profile != QualityProfile.CUSTOM) {
                    selectedQualityProfile = profile
                    if (profile != QualityProfile.CUSTOM) {
                        streamJpegQuality = profile.jpegQuality
                        streamTargetFps = profile.targetFps
                    }
                    qualitySpinner.setSelection(QualityProfile.entries.indexOf(profile))
                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    if (manualExposureEnabled) applyManualExposure()
                }
            }

            "jpegQuality" -> {
                val requested = value?.toIntOrNull() ?: return
                streamJpegQuality = requested.coerceIn(1, 100)
                selectedQualityProfile = QualityProfile.CUSTOM
                qualitySpinner.setSelection(QualityProfile.entries.indexOf(QualityProfile.CUSTOM))
                nextEncodeDueNs = 0L
                resetPerformanceStats()
                updateStreamInfo()
            }

            "fpsLimit" -> {
                val requested = value?.toIntOrNull() ?: return
                streamTargetFps = if (requested <= 0) 0 else requested.coerceIn(5, 60)
                selectedQualityProfile = QualityProfile.CUSTOM
                qualitySpinner.setSelection(QualityProfile.entries.indexOf(QualityProfile.CUSTOM))
                nextEncodeDueNs = 0L
                resetPerformanceStats()
                updateStreamInfo()
                if (manualExposureEnabled) applyManualExposure()
            }

            "rotation" -> {
                val mode = runCatching {
                    RotationMode.valueOf(value.orEmpty().uppercase())
                }.getOrNull() ?: return
                if (mode != selectedRotationMode) {
                    selectedRotationMode = mode
                    rotationSpinner.setSelection(RotationMode.entries.indexOf(mode))
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    applyCameraTargetRotation(
                        if (mode == RotationMode.AUTO) autoSurfaceRotation else Surface.ROTATION_0
                    )
                    updateStreamInfo()
                }
            }

            "autoDiscovery" -> {
                val enabled = value == "1" || value.equals("true", true)
                autoDiscoveryEnabled = enabled
                getSharedPreferences("goat_pro_ip", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_discovery_enabled", enabled)
                    .apply()
                if (enabled) discoveryResponder.start() else discoveryResponder.stop()
                autoDiscoverySwitch.isChecked = enabled
                updateAutomaticDiscoveryText()
                updateConnectionStatus(server.videoClientCount())
            }

            "audio" -> {
                val enabled = value == "1" || value.equals("true", true)
                if (!enabled) {
                    audioEnabled = false
                    server.setAudioEnabled(false)
                    audioCapture.stop()
                    updateAudioButton()
                } else if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    audioEnabled = true
                    server.setAudioEnabled(true)
                    if (server.isRunning() && !audioCapture.start()) {
                        audioEnabled = false
                        server.setAudioEnabled(false)
                    }
                    updateAudioButton()
                } else {
                    audioEnabled = false
                    server.setAudioEnabled(false)
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            "cameraLens" -> {
                val option = cameraLensOptions.firstOrNull {
                    it.key == value
                } ?: return
                selectCameraLens(option)
            }

            "switch" -> {
                cycleCameraLens()
            }

            "torch" -> toggleTorch()

            "zoom" -> {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
                val requested = value?.toFloatOrNull() ?: return
                val ratio = requested.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                camera.cameraControl.setZoomRatio(ratio)
            }

            "ev" -> {
                if (manualExposureEnabled) return
                val state = camera?.cameraInfo?.exposureState ?: return
                val requested = value?.toIntOrNull() ?: return
                val range = state.exposureCompensationRange
                camera.cameraControl.setExposureCompensationIndex(
                    requested.coerceIn(range.lower, range.upper)
                )
            }

            "focus" -> {
                if (camera == null || previewView.width <= 0 || previewView.height <= 0) return
                if (manualFocusEnabled) {
                    disableManualFocus()
                }
                val point = previewView.meteringPointFactory.createPoint(
                    previewView.width / 2f,
                    previewView.height / 2f
                )
                val focus = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                )
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                camera.cameraControl.startFocusAndMetering(focus)
            }

            "focusMode" -> {
                manualFocusEnabled = value == "manual" || value == "1" || value.equals("true", true)
                if (manualFocusEnabled) {
                    val maxFocus = manualFocusMaxDiopters()
                    if (maxFocus <= 0f) {
                        manualFocusEnabled = false
                        return
                    }
                    manualFocusDiopters = manualFocusDiopters.coerceIn(0f, maxFocus)
                    applyManualFocus()
                } else {
                    disableManualFocus()
                }
            }

            "focusDistance" -> {
                val maxFocus = manualFocusMaxDiopters()
                val requested = value?.toFloatOrNull() ?: return
                if (maxFocus <= 0f) {
                    manualFocusEnabled = false
                    return
                }
                manualFocusEnabled = true
                manualFocusDiopters = requested.coerceIn(0f, maxFocus)
                applyManualFocus()
            }

            "whiteBalance" -> {
                val requested = whiteBalanceModeFromName(value.orEmpty()) ?: return
                val supported = camera2IntArray(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                if (supported.contains(requested)) {
                    selectedWhiteBalanceMode = requested
                    applyAutomaticSensorControls()
                }
            }

            "antibanding" -> {
                val requested = antibandingModeFromName(value.orEmpty()) ?: return
                val supported = camera2IntArray(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                if (supported.contains(requested)) {
                    selectedAntibandingMode = requested
                    applyAutomaticSensorControls()
                }
            }

            "sceneMode" -> {
                val requested = sceneModeFromName(value.orEmpty()) ?: return
                val supported = camera2IntArray(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                if (requested == CaptureRequest.CONTROL_SCENE_MODE_DISABLED || supported.contains(requested)) {
                    selectedSceneMode = requested
                    applyAutomaticSensorControls()
                }
            }

            "frameDurationUs" -> {
                val requestedUs = value?.toLongOrNull() ?: return
                manualFrameDurationNs = if (requestedUs <= 0L) 0L else requestedUs * 1_000L
                if (manualExposureEnabled) applyManualExposure()
            }

            "aperture" -> {
                val requested = value?.toFloatOrNull() ?: return
                val values = availableApertures()
                if (values.isNotEmpty()) {
                    selectedAperture = values.minByOrNull { kotlin.math.abs(it - requested) }
                    applyLensControls()
                }
            }

            "filterDensity" -> {
                val requested = value?.toFloatOrNull() ?: return
                val values = availableFilterDensities()
                if (values.isNotEmpty()) {
                    selectedFilterDensity = values.minByOrNull { kotlin.math.abs(it - requested) }
                    applyLensControls()
                }
            }

            "manual" -> {
                manualExposureEnabled = value == "1" || value.equals("true", true)
                if (manualExposureEnabled) {
                    applyManualExposure()
                } else {
                    disableManualExposure()
                }
            }

            "iso" -> {
                val requested = value?.toIntOrNull() ?: return
                manualIso = requested
                if (manualExposureEnabled) applyManualExposure()
            }

            "shutterUs" -> {
                val requestedUs = value?.toLongOrNull() ?: return
                manualExposureTimeNs = requestedUs.coerceAtLeast(1L) * 1_000L
                if (manualExposureEnabled) applyManualExposure()
            }

            "bitrateKbps" -> {
                val requested = value?.toIntOrNull() ?: return
                streamBitrateBps = if (requested <= 0) {
                    0
                } else {
                    requested.coerceIn(500, 60_000) * 1_000
                }
                h264Encoder.stop()
                if (front4kDirectStreamer.isRunning()) {
                    autoRestartStreamAfterCameraBind = true
                    stopStreaming()
                    startCamera()
                }
                updateStreamInfo()
            }

            "watermark" -> {
                watermarkEnabled =
                    value == "1" || value.equals("true", true)
                updateStreamInfo()
            }

            "savePreset" -> {
                val slot = value?.toIntOrNull() ?: return
                saveProPreset(slot)
            }

            "loadPreset" -> {
                val slot = value?.toIntOrNull() ?: return
                loadProPreset(slot)
            }
        }
        saveSmartLinkState()
    }

    private fun camera2IntArray(
        key: CameraCharacteristics.Key<IntArray>
    ): IntArray {
        val camera = currentCamera ?: return intArrayOf()
        return try {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(key) ?: intArrayOf()
        } catch (_: Exception) {
            intArrayOf()
        }
    }

    private fun availableApertures(): FloatArray {
        val camera = currentCamera ?: return floatArrayOf()
        return try {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES
                ) ?: floatArrayOf()
        } catch (_: Exception) {
            floatArrayOf()
        }
    }

    private fun availableFilterDensities(): FloatArray {
        val camera = currentCamera ?: return floatArrayOf()
        return try {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FILTER_DENSITIES
                ) ?: floatArrayOf()
        } catch (_: Exception) {
            floatArrayOf()
        }
    }

    private fun whiteBalanceModeFromName(name: String): Int? = when (name.uppercase()) {
        "AUTO" -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        "INCANDESCENT" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        "FLUORESCENT" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        "WARM_FLUORESCENT" -> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
        "DAYLIGHT" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        "CLOUDY_DAYLIGHT" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        "TWILIGHT" -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
        "SHADE" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        else -> null
    }

    private fun whiteBalanceName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CaptureRequest.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "AUTO"
    }

    private fun antibandingModeFromName(name: String): Int? = when (name.uppercase()) {
        "OFF" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
        "50HZ" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        "60HZ" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        "AUTO" -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        else -> null
    }

    private fun antibandingName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF -> "OFF"
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ -> "50HZ"
        CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ -> "60HZ"
        else -> "AUTO"
    }

    private fun sceneModeFromName(name: String): Int? = when (name.uppercase()) {
        "AUTO" -> CaptureRequest.CONTROL_SCENE_MODE_DISABLED
        "ACTION" -> CaptureRequest.CONTROL_SCENE_MODE_ACTION
        "PORTRAIT" -> CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT
        "LANDSCAPE" -> CaptureRequest.CONTROL_SCENE_MODE_LANDSCAPE
        "NIGHT" -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
        "NIGHT_PORTRAIT" -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT
        "SPORTS" -> CaptureRequest.CONTROL_SCENE_MODE_SPORTS
        else -> null
    }

    private fun sceneModeName(mode: Int): String = when (mode) {
        CaptureRequest.CONTROL_SCENE_MODE_ACTION -> "ACTION"
        CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT -> "PORTRAIT"
        CaptureRequest.CONTROL_SCENE_MODE_LANDSCAPE -> "LANDSCAPE"
        CaptureRequest.CONTROL_SCENE_MODE_NIGHT -> "NIGHT"
        CaptureRequest.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> "NIGHT_PORTRAIT"
        CaptureRequest.CONTROL_SCENE_MODE_SPORTS -> "SPORTS"
        else -> "AUTO"
    }

    private fun applyAutomaticSensorControls(): Boolean {
        val camera = currentCamera ?: return false
        return try {
            val builder = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE,
                    selectedWhiteBalanceMode
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                    selectedAntibandingMode
                )

            if (selectedSceneMode == CaptureRequest.CONTROL_SCENE_MODE_DISABLED) {
                builder.setCaptureRequestOption(
                    CaptureRequest.CONTROL_MODE,
                    CaptureRequest.CONTROL_MODE_AUTO
                )
            } else {
                builder
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_MODE,
                        CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_SCENE_MODE,
                        selectedSceneMode
                    )
            }

            Camera2CameraControl.from(camera.cameraControl)
                .addCaptureRequestOptions(builder.build())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun applyLensControls(): Boolean {
        val camera = currentCamera ?: return false
        return try {
            val builder = CaptureRequestOptions.Builder()
            selectedAperture?.let {
                builder.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, it)
            }
            selectedFilterDensity?.let {
                builder.setCaptureRequestOption(CaptureRequest.LENS_FILTER_DENSITY, it)
            }
            Camera2CameraControl.from(camera.cameraControl)
                .addCaptureRequestOptions(builder.build())
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun manualFocusMaxDiopters(): Float {
        val camera = currentCamera ?: return 0f
        return try {
            Camera2CameraInfo.from(camera.cameraInfo)
                .getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                ) ?: 0f
        } catch (_: Exception) {
            0f
        }
    }

    private fun applyManualFocus(): Boolean {
        val camera = currentCamera ?: return false
        val maxFocus = manualFocusMaxDiopters()
        if (!manualFocusEnabled || maxFocus <= 0f) {
            manualFocusEnabled = false
            return false
        }

        return try {
            manualFocusDiopters = manualFocusDiopters.coerceIn(0f, maxFocus)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    manualFocusDiopters
                )
                .build()
            Camera2CameraControl.from(camera.cameraControl)
                .addCaptureRequestOptions(options)
            true
        } catch (_: Exception) {
            manualFocusEnabled = false
            false
        }
    }

    private fun disableManualFocus(): Boolean {
        val camera = currentCamera ?: return false
        return try {
            manualFocusEnabled = false
            manualFocusDiopters = 0f
            val control = Camera2CameraControl.from(camera.cameraControl)
            control.clearCaptureRequestOptions()

            applyAutomaticSensorControls()
            applyLensControls()
            if (manualExposureEnabled) {
                applyManualExposure()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private data class ManualSensorCapabilities(
        val isoRange: IntRange?,
        val shutterRange: LongRange?,
        val aeOffSupported: Boolean,
        val manualSensorFlag: Boolean,
        val maxFrameDurationNs: Long?
    ) {
        val supported: Boolean
            get() = isoRange != null && shutterRange != null &&
                (manualSensorFlag || aeOffSupported)
    }

    private fun manualSensorCapabilities(): ManualSensorCapabilities {
        val camera = currentCamera
            ?: return ManualSensorCapabilities(null, null, false, false, null)

        return try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val iso = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            val shutter = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            val aeModes = info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
            ) ?: intArrayOf()
            val capabilities = info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()
            val maxFrameDuration = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION
            )

            ManualSensorCapabilities(
                isoRange = iso?.let { it.lower..it.upper },
                shutterRange = shutter?.let { it.lower..it.upper },
                aeOffSupported = aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF),
                manualSensorFlag = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
                ),
                maxFrameDurationNs = maxFrameDuration
            )
        } catch (_: Exception) {
            ManualSensorCapabilities(null, null, false, false, null)
        }
    }

    private fun applyManualExposure(): Boolean {
        val camera = currentCamera ?: return false
        return try {
            val caps = manualSensorCapabilities()
            val isoRange = caps.isoRange
            val shutterRange = caps.shutterRange

            if (!manualExposureEnabled || !caps.supported ||
                isoRange == null || shutterRange == null
            ) {
                manualExposureEnabled = false
                manualRequestApplied = false
                manualControlStatus = "Exposição manual indisponível nesta câmera"
                return false
            }

            manualIso = manualIso.coerceIn(isoRange.first, isoRange.last)
            manualExposureTimeNs = manualExposureTimeNs.coerceIn(
                shutterRange.first,
                shutterRange.last
            )

            val effectiveFps = if (streamTargetFps > 0) streamTargetFps else 30
            val targetFrameNs = 1_000_000_000L /
                effectiveFps.coerceAtLeast(1)
            val requestedFrameNs = if (manualFrameDurationNs > 0L) {
                manualFrameDurationNs
            } else {
                targetFrameNs
            }
            var frameDurationNs = maxOf(
                requestedFrameNs,
                targetFrameNs,
                manualExposureTimeNs
            )
            caps.maxFrameDurationNs?.let {
                frameDurationNs = frameDurationNs.coerceAtMost(it)
            }
            frameDurationNs = maxOf(frameDurationNs, manualExposureTimeNs)

            val control = Camera2CameraControl.from(camera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_LOCK,
                    false
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    manualIso
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    manualExposureTimeNs
                )
                .setCaptureRequestOption(
                    CaptureRequest.SENSOR_FRAME_DURATION,
                    frameDurationNs
                )
                .build()

            manualRequestApplied = false
            manualControlStatus =
                "Aplicando manual: ISO $manualIso • ${manualExposureTimeNs / 1_000L} µs"

            val future = control.addCaptureRequestOptions(options)
            future.addListener({
                try {
                    future.get()
                    manualRequestApplied = true
                    manualControlStatus =
                        "MANUAL ATIVO: ISO $manualIso • ${manualExposureTimeNs / 1_000L} µs"
                } catch (ex: Exception) {
                    manualRequestApplied = false
                    manualExposureEnabled = false
                    manualControlStatus =
                        "Falha no modo manual: ${ex.cause?.message ?: ex.message ?: "Camera2"}"
                }
            }, ContextCompat.getMainExecutor(this))

            true
        } catch (ex: Exception) {
            manualExposureEnabled = false
            manualRequestApplied = false
            manualControlStatus =
                "Falha no modo manual: ${ex.message ?: "Camera2"}"
            false
        }
    }

    private fun disableManualExposure(): Boolean {
        val camera = currentCamera ?: return false
        return try {
            val control = Camera2CameraControl.from(camera.cameraControl)
            manualExposureEnabled = false
            manualRequestApplied = false
            manualControlStatus = "Voltando para exposição automática…"

            val future = control.clearCaptureRequestOptions()
            future.addListener({
                try {
                    future.get()
                    manualControlStatus = "Exposição automática"
                    applyAutomaticSensorControls()
                    applyLensControls()
                    if (manualFocusEnabled) {
                        applyManualFocus()
                    }
                } catch (ex: Exception) {
                    manualControlStatus =
                        "Falha ao restaurar automático: ${ex.cause?.message ?: ex.message ?: "Camera2"}"
                }
            }, ContextCompat.getMainExecutor(this))
            true
        } catch (ex: Exception) {
            manualControlStatus =
                "Falha ao restaurar automático: ${ex.message ?: "Camera2"}"
            false
        }
    }

    private fun buildCameraControlStateJson(): String {
        val camera = currentCamera ?: return "{\"available\":false}"
        return try {
            val zoom = camera.cameraInfo.zoomState.value
            val exposure = camera.cameraInfo.exposureState
            val evRange = exposure.exposureCompensationRange
            val manualCaps = manualSensorCapabilities()
            val isoRange = manualCaps.isoRange
            val shutterRange = manualCaps.shutterRange
            val manualSupported = manualCaps.supported
            val currentZoom = zoom?.zoomRatio ?: 1f
            val minZoom = zoom?.minZoomRatio ?: 1f
            val maxZoom = zoom?.maxZoomRatio ?: 1f
            val currentEv = exposure.exposureCompensationIndex
            val cameraName =
                selectedCameraOption?.label
                    ?: if (lensFacing == CameraSelector.LENS_FACING_BACK) "Traseira" else "Frontal"
            val cameraKey = selectedCameraOption?.key ?: ""
            val cameraOptionsCsv = cameraLensOptions.joinToString(";") {
                it.key + "|" + it.label.replace(";", " ").replace("|", " ")
            }
            val minShutterUs = (shutterRange?.first ?: 100_000L) / 1_000L
            val maxShutterUs = (shutterRange?.last ?: 1_000_000_000L) / 1_000L
            val currentShutterUs = manualExposureTimeNs / 1_000L
            val maxFocusDiopters = manualFocusMaxDiopters()
            val manualFocusSupported = maxFocusDiopters > 0f
            val aperturesCsv = availableApertures().joinToString(",")
            val filtersCsv = availableFilterDensities().joinToString(",")
            val maxFrameDurationUs = (manualCaps.maxFrameDurationNs ?: 1_000_000_000L) / 1_000L
            val currentFrameDurationUs = (
                if (manualFrameDurationNs > 0L) {
                    manualFrameDurationNs
                } else {
                    1_000_000_000L / (if (streamTargetFps > 0) streamTargetFps else 30)
                }
            ) / 1_000L
            val supportedResolutionsCsv =
                availableResolutionOptions.joinToString(",") { it.key }
            val resolutionOptionsCsv =
                availableResolutionOptions.joinToString(";") {
                    it.key + "|" + it.label.replace(";", " ").replace("|", " ")
                }
            val presetPrefs = proPresetPrefs()
            val preset1Saved = presetPrefs.getBoolean("preset_1_saved", false)
            val preset2Saved = presetPrefs.getBoolean("preset_2_saved", false)
            val preset3Saved = presetPrefs.getBoolean("preset_3_saved", false)

            "{\"available\":true" +
                ",\"resolution\":\"${selectedResolution.key}\"" +
                ",\"supportedResolutionsCsv\":\"$supportedResolutionsCsv\"" +
                ",\"resolutionOptionsCsv\":\"$resolutionOptionsCsv\"" +
                ",\"quality\":\"${selectedQualityProfile.name}\"" +
                ",\"qualityLabel\":\"${selectedQualityProfile.shortLabel}\"" +
                ",\"jpegQuality\":$streamJpegQuality" +
                ",\"targetFps\":$streamTargetFps" +
                ",\"bitrateKbps\":${streamBitrateBps / 1000}" +
                ",\"watermarkEnabled\":$watermarkEnabled" +
                ",\"smartLinkEnabled\":true" +
                ",\"preset1Saved\":$preset1Saved" +
                ",\"preset2Saved\":$preset2Saved" +
                ",\"preset3Saved\":$preset3Saved" +
                ",\"backgroundStreaming\":${isStreamingActive()}" +
                ",\"rotation\":\"${selectedRotationMode.name}\"" +
                ",\"autoDiscovery\":$autoDiscoveryEnabled" +
                ",\"audioEnabled\":$audioEnabled" +
                ",\"rtspClients\":${rtspServer.activeClientCount()}" +
                ",\"h264Running\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning()}" +
                ",\"camera\":\"$cameraName\"" +
                ",\"cameraKey\":\"$cameraKey\"" +
                ",\"cameraOptionsCsv\":\"$cameraOptionsCsv\"" +
                ",\"torch\":$torchEnabled" +
                ",\"zoom\":$currentZoom" +
                ",\"minZoom\":$minZoom" +
                ",\"maxZoom\":$maxZoom" +
                ",\"ev\":$currentEv" +
                ",\"minEv\":${evRange.lower}" +
                ",\"maxEv\":${evRange.upper}" +
                ",\"manualSupported\":$manualSupported" +
                ",\"manual\":$manualExposureEnabled" +
                ",\"manualApplied\":$manualRequestApplied" +
                ",\"manualFocusSupported\":$manualFocusSupported" +
                ",\"whiteBalance\":\"${whiteBalanceName(selectedWhiteBalanceMode)}\"" +
                ",\"antibanding\":\"${antibandingName(selectedAntibandingMode)}\"" +
                ",\"sceneMode\":\"${sceneModeName(selectedSceneMode)}\"" +
                ",\"aperturesCsv\":\"$aperturesCsv\"" +
                ",\"selectedAperture\":${selectedAperture ?: -1f}" +
                ",\"filterDensitiesCsv\":\"$filtersCsv\"" +
                ",\"selectedFilterDensity\":${selectedFilterDensity ?: -1f}" +
                ",\"frameDurationUs\":$currentFrameDurationUs" +
                ",\"maxFrameDurationUs\":$maxFrameDurationUs" +
                ",\"manualFocus\":$manualFocusEnabled" +
                ",\"focusDiopters\":$manualFocusDiopters" +
                ",\"maxFocusDiopters\":$maxFocusDiopters" +
                ",\"iso\":$manualIso" +
                ",\"minIso\":${isoRange?.first ?: 50}" +
                ",\"maxIso\":${isoRange?.last ?: 12800}" +
                ",\"shutterUs\":$currentShutterUs" +
                ",\"minShutterUs\":$minShutterUs" +
                ",\"maxShutterUs\":$maxShutterUs" +
                ",\"width\":$actualStreamWidth" +
                ",\"height\":$actualStreamHeight" +
                "}"
        } catch (_: Exception) {
            "{\"available\":false}"
        }
    }

    private fun toggleTorch() {
        val camera = currentCamera
        if (camera == null || !camera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Lanterna indisponível nesta câmera.", Toast.LENGTH_SHORT).show()
            return
        }

        torchEnabled = !torchEnabled
        camera.cameraControl.enableTorch(torchEnabled)
        updateTorchButton()
    }

    private fun toggleAudio() {
        if (audioEnabled) {
            audioEnabled = false
            server.setAudioEnabled(false)
            audioCapture.stop()
            updateAudioButton()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioEnabled = true
            server.setAudioEnabled(true)
            if (server.isRunning() && !audioCapture.start()) {
                audioEnabled = false
                server.setAudioEnabled(false)
                Toast.makeText(this, "Não foi possível iniciar o microfone.", Toast.LENGTH_SHORT).show()
            }
            updateAudioButton()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateTorchButton() {
        if (!::torchButton.isInitialized) return
        val available = currentCamera?.cameraInfo?.hasFlashUnit() == true
        torchButton.isEnabled = available
        torchButton.text = when {
            !available -> "Lanterna indisponível"
            torchEnabled -> "Lanterna: ligada"
            else -> "Lanterna: desligada"
        }
    }

    private fun updateAudioButton() {
        if (!::audioButton.isInitialized) return
        audioButton.text = if (audioEnabled) "Áudio: ativado" else "Áudio: desligado"
    }

    private fun isStreamingActive(): Boolean =
        server.isRunning() ||
            rtspServer.isRunning() ||
            front4kDirectStreamer.isRunning() ||
            front4kDirectStreamer.isStarting() ||
            backgroundH264Streamer.isRunning() ||
            backgroundH264Streamer.isStarting()

    private fun startStreamingForegroundService() {
        if (!backgroundStreamingEnabled) return
        StreamingCameraLifecycle.setActive(true)
        val intent = Intent(this, StreamingForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopStreamingForegroundService() {
        stopService(Intent(this, StreamingForegroundService::class.java))
    }

    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            setError("CONECTE O CELULAR A UMA REDE WI-FI")
            Toast.makeText(
                this,
                "Nenhum endereço IPv4 local encontrado.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        startStreamingForegroundService()
        front4kDirectStreamer.stop()
        refreshAddress()
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        server.start()
        server.setAudioEnabled(audioEnabled)

        if (audioEnabled && !audioCapture.start()) {
            audioEnabled = false
            server.setAudioEnabled(false)
            updateAudioButton()
            Toast.makeText(
                this,
                "Vídeo iniciado sem áudio.",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (selectedResolution.directFront4k) {
            h264Encoder.stop()
            rtspServer.start()
            statusText.text = "INICIANDO 4K FRONTAL H.264…"
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.green)
            )

            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    previewUseCase = null
                    analysisUseCase = null
                    currentCamera = null
                    updateTorchButton()

                    val option = selectedCameraOption
                        ?: throw IllegalStateException(
                            "Câmera frontal selecionada indisponível."
                        )
                    val cameraId =
                        selectedResolution.directCameraId
                            ?: option.logicalCameraId
                    val profile = Front4kDirectStreamer.Profile(
                        cameraId = cameraId,
                        width = selectedResolution.size.width,
                        height = selectedResolution.size.height,
                        fps = selectedResolution.directFps ?: 30,
                        bitrate =
                            if (streamBitrateBps > 0) {
                                streamBitrateBps
                            } else {
                                selectedResolution.directBitrate
                                    ?: 32_000_000
                            },
                        fromOfficialProfile =
                            Front4kDirectStreamer.profileFor(
                                this,
                                cameraId
                            )?.fromOfficialProfile == true
                    )

                    fallbackResolutionAfterFront4kFailure =
                        availableResolutionOptions.firstOrNull {
                            !it.directFront4k &&
                                it.key == "1920x1080"
                        }
                            ?: availableResolutionOptions.firstOrNull {
                                !it.directFront4k
                            }

                    val started = front4kDirectStreamer.start(profile)
                    if (!started) {
                        rtspServer.stop()
                        server.stop()
                    }
                } catch (ex: Exception) {
                    rtspServer.stop()
                    server.stop()
                    setError(
                        "4K frontal: " +
                            (ex.message ?: "falha ao abrir a câmera")
                    )
                    startCamera()
                }
            }, ContextCompat.getMainExecutor(this))
        } else {
            front4kDirectStreamer.stop()
            rtspServer.start()
            statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
            statusText.setTextColor(
                ContextCompat.getColor(this, R.color.green)
            )
        }

        updateConnectionStatus(0)
        streamButton.text = "Parar transmissão"
        streamButton.setBackgroundResource(R.drawable.bg_button_danger)
        streamButton.backgroundTintList = null
        streamButton.setTextColor(
            ContextCompat.getColor(this, android.R.color.white)
        )
    }

    private fun stopStreaming() {
        audioCapture.stop()
        server.setAudioEnabled(false)
        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()
        server.stop()
        stopStreamingForegroundService()
        backgroundHeadless = false
        StreamingCameraLifecycle.setActive(true)
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        actualStreamWidth = 0
        actualStreamHeight = 0
        setReadyState()
        updateConnectionStatus(0)
        streamButton.text = "Iniciar transmissão"
        streamButton.setBackgroundResource(R.drawable.bg_button_primary)
        streamButton.backgroundTintList = null
        streamButton.setTextColor(
            ContextCompat.getColor(this, R.color.black)
        )

        if (
            selectedResolution.directFront4k &&
            !autoRestartStreamAfterCameraBind &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

    private fun updateConnectionStatus(count: Int) {
        if (!::connectionStatusText.isInitialized) return
        val rtspCount = rtspServer.activeClientCount()
        val totalCount = count + rtspCount
        when {
            totalCount > 0 -> {
                connectionStatusText.text = "CONECTADO AO GOAT PRO STUDIO • $totalCount conexão(ões)"
                connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.green))
            }
            server.isRunning() -> {
                connectionStatusText.text = if (autoDiscoveryEnabled) {
                    "TRANSMISSÃO ATIVA • AGUARDANDO CONEXÃO AUTOMÁTICA"
                } else {
                    "TRANSMISSÃO ATIVA • MODO MANUAL • USE O IP NO STUDIO"
                }
                connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.muted))
            }
            else -> {
                connectionStatusText.text = "SEM CONEXÃO ATIVA"
                connectionStatusText.setTextColor(ContextCompat.getColor(this, R.color.muted))
            }
        }
    }

    private fun setReadyState() {
        statusText.text = "PRONTO PARA TRANSMITIR"
        statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
    }

    private fun setError(message: String) {
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, R.color.red))
    }

    private fun refreshAddress() {
        val ip = NetworkUtils.localIpv4()
        addressText.text = if (ip != null) {
            if (selectedResolution.directFront4k) {
                "4K frontal H.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            } else {
                "MJPEG: http://" + ip +
                    ":8080/video\nH.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            }
        } else {
            "Sem endereço Wi-Fi disponível"
        }
    }

    private fun updateStreamInfo() {
        val dimensions =
            if (actualStreamWidth > 0 && actualStreamHeight > 0) {
                actualStreamWidth.toString() +
                    "×" + actualStreamHeight
            } else {
                selectedResolution.label
            }
        val fpsLabel =
            if (streamTargetFps > 0) {
                streamTargetFps.toString() + " FPS"
            } else {
                "FPS sem limite"
            }

        if (selectedResolution.directFront4k) {
            streamInfoText.text =
                dimensions + " • " + fpsLabel +
                    " • H.264 hardware • 4K frontal"
            return
        }

        val profile = selectedQualityProfile
        val profileLabel =
            if (profile == QualityProfile.CUSTOM) {
                "Personalizado"
            } else {
                profile.shortLabel
            }
        streamInfoText.text =
            dimensions + " • " + fpsLabel +
                " • JPEG Q" + streamJpegQuality +
                " • " + profileLabel +
                " • " + selectedRotationMode.shortLabel
    }

    private fun copyAddressToClipboard() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            Toast.makeText(
                this,
                "Conecte o celular ao Wi-Fi primeiro.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val urls =
            if (selectedResolution.directFront4k) {
                "4K frontal H.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            } else {
                "MJPEG: http://" + ip +
                    ":8080/video\nH.264 RTSP: rtsp://" +
                    ip + ":8554/h264"
            }

        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("GOAT Cam", urls)
        )
        Toast.makeText(
            this,
            if (selectedResolution.directFront4k) {
                "Endereço 4K frontal H.264 copiado"
            } else {
                "Endereços MJPEG e H.264 copiados"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onStart() {
        super.onStart()
        StreamingCameraLifecycle.setActive(true)
        exitBackgroundCaptureMode()
    }

    override fun onResume() {
        super.onResume()
        StreamingCameraLifecycle.setActive(true)
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        if (::addressText.isInitialized) refreshAddress()
    }

    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    override fun onStop() {
        if (isStreamingActive() && backgroundStreamingEnabled) {
            enterBackgroundCaptureMode()
        } else {
            backgroundHeadless = false
            StreamingCameraLifecycle.setActive(false)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (torchEnabled) {
            currentCamera?.cameraControl?.enableTorch(false)
        }
        if (::orientationListener.isInitialized) orientationListener.disable()
        discoveryResponder.stop()
        audioCapture.stop()
        server.setAudioEnabled(false)
        h264Encoder.stop()
        backgroundH264Streamer.stop()
        backgroundDirectActive = false
        front4kDirectStreamer.stop()
        rtspServer.stop()
        server.stop()
        stopStreamingForegroundService()
        StreamingCameraLifecycle.setActive(false)
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private enum class QualityProfile(
        val label: String,
        val shortLabel: String,
        val jpegQuality: Int,
        val targetFps: Int
    ) {
        LOW_LATENCY(
            "Baixa latência • Q50 • 20 FPS",
            "Baixa latência",
            50,
            20
        ),
        BALANCED(
            "Equilibrado • Q65 • 20 FPS",
            "Equilibrado",
            65,
            20
        ),
        HIGH_QUALITY(
            "Alta qualidade • Q80 • 20 FPS",
            "Alta qualidade",
            80,
            20
        ),
        MAX_QUALITY(
            "Máxima qualidade • Q90 • 15 FPS",
            "Máxima qualidade",
            90,
            15
        ),
        CUSTOM(
            "Personalizado",
            "Personalizado",
            65,
            20
        )
    }

    private enum class RotationMode(
        val label: String,
        val shortLabel: String,
        val offsetDegrees: Int
    ) {
        AUTO("Automático", "Rotação auto", 0),
        ROTATE_90("Girar 90° para direita", "Rotação +90°", 90),
        ROTATE_180("Girar 180°", "Rotação +180°", 180),
        ROTATE_270("Girar 270° / esquerda", "Rotação +270°", 270);

        fun resolve(sensorRotationDegrees: Int): Int =
            ((sensorRotationDegrees + offsetDegrees) % 360 + 360) % 360
    }

    companion object {
        private const val PREVIEW_HEIGHT_RATIO = 9f / 16f
    }
}
