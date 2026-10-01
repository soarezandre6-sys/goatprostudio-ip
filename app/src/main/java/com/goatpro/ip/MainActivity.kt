package com.goatpro.ip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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
    private lateinit var resolutionSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var rotationSpinner: Spinner
    private lateinit var autoDiscoverySwitch: SwitchCompat

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var orientationListener: OrientationEventListener
    private var currentCamera: Camera? = null
    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var autoSurfaceRotation = Surface.ROTATION_0

    @Volatile
    private var selectedPreset = ResolutionPreset.FHD

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
    private var actualStreamWidth = 0
    private var actualStreamHeight = 0

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

    private val audioCapture by lazy {
        AudioCapture(this, server::offerAudio)
    }

    private val discoveryResponder by lazy {
        DiscoveryResponder(
            httpPort = 8080,
            isStreaming = { server.isRunning() },
            isAudioEnabled = { audioEnabled && server.isAudioEnabled() }
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
                Toast.makeText(this, "Não foi possível iniciar o microfone.", Toast.LENGTH_SHORT).show()
            }
        }
        if (!granted) {
            Toast.makeText(this, "Permissão de microfone não concedida.", Toast.LENGTH_SHORT).show()
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
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        rotationSpinner = findViewById(R.id.rotationSpinner)
        autoDiscoverySwitch = findViewById(R.id.autoDiscoverySwitch)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()

        applyPreviewAspectRatio()
        setupResolutionSelector()
        setupQualitySelector()
        setupRotationSelector()
        setupAutomaticDiscovery()
        refreshAddress()
        updateStreamInfo()
        updateConnectionStatus(0)
        updateAudioButton()
        updateTorchButton()

        streamButton.setOnClickListener {
            if (server.isRunning()) stopStreaming() else startStreaming()
        }

        switchCameraButton.setOnClickListener {
            if (torchEnabled) {
                currentCamera?.cameraControl?.enableTorch(false)
                torchEnabled = false
            }
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

        torchButton.setOnClickListener { toggleTorch() }
        audioButton.setOnClickListener { toggleAudio() }
        copyAddressButton.setOnClickListener { copyAddressToClipboard() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
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

    private fun setupResolutionSelector() {
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            ResolutionPreset.entries.map { it.label }
        )
        resolutionSpinner.setSelection(ResolutionPreset.entries.indexOf(selectedPreset))
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newPreset = ResolutionPreset.entries[position]
                if (newPreset != selectedPreset) {
                    selectedPreset = newPreset
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    applyPreviewAspectRatio()
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startCamera()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupQualitySelector() {
        qualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            QualityProfile.entries.map { it.label }
        )
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
        rotationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            RotationMode.entries.map { it.label }
        )
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

    private fun updateAutomaticDiscoveryText() {
        if (!::autoDiscoverySwitch.isInitialized) return
        autoDiscoverySwitch.text = if (autoDiscoveryEnabled) {
            "Conexão automática com o GOAT PRO Studio: LIGADA"
        } else {
            "Conexão automática com o GOAT PRO Studio: DESLIGADA · modo manual"
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val size = selectedPreset.size
            val targetRotation = if (selectedRotationMode == RotationMode.AUTO) {
                autoSurfaceRotation
            } else {
                Surface.ROTATION_0
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        size,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
                .setOutputImageRotationEnabled(true)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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

                            // CameraX now rotates the ImageProxy natively to targetRotation.
                            // AUTO therefore requires no Kotlin per-pixel rotation. Manual
                            // overrides are still applied as an explicit extra quarter-turn.
                            val manualRotation = selectedRotationMode.offsetDegrees
                            val encodeStartedNs = System.nanoTime()
                            val encoded = ImageUtils.imageProxyToJpeg(
                                image = image,
                                quality = streamJpegQuality,
                                rotationDegrees = manualRotation
                            )
                            val encodeElapsedNs = System.nanoTime() - encodeStartedNs

                            if (encoded != null) {
                                server.offerFrame(encoded.bytes)
                                recordEncodedFrame(encoded.bytes.size, encodeElapsedNs)
                                if (encoded.width != actualStreamWidth || encoded.height != actualStreamHeight) {
                                    actualStreamWidth = encoded.width
                                    actualStreamHeight = encoded.height
                                    runOnUiThread { updateStreamInfo() }
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
                previewUseCase = preview
                analysisUseCase = analysis
                currentCamera = provider.bindToLifecycle(this, selector, preview, analysis)
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
                val preset = when (value?.uppercase()) {
                    "HD", "720P" -> ResolutionPreset.HD
                    "FHD", "1080P" -> ResolutionPreset.FHD
                    else -> return
                }
                if (preset != selectedPreset) {
                    selectedPreset = preset
                    resolutionSpinner.setSelection(ResolutionPreset.entries.indexOf(preset))
                    actualStreamWidth = 0
                    actualStreamHeight = 0
                    nextEncodeDueNs = 0L
                    resetPerformanceStats()
                    updateStreamInfo()
                    applyPreviewAspectRatio()
                    startCamera()
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
            "switch" -> {
                if (torchEnabled) {
                    camera?.cameraControl?.enableTorch(false)
                    torchEnabled = false
                }
                manualExposureEnabled = false
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                startCamera()
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
                selectedWhiteBalanceMode = requested
                applyAutomaticSensorControls()
            }

            "antibanding" -> {
                val requested = antibandingModeFromName(value.orEmpty()) ?: return
                selectedAntibandingMode = requested
                applyAutomaticSensorControls()
            }

            "sceneMode" -> {
                val requested = sceneModeFromName(value.orEmpty()) ?: return
                selectedSceneMode = requested
                applyAutomaticSensorControls()
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
            var frameDurationNs = maxOf(requestedFrameNs, manualExposureTimeNs)
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
            val cameraName = if (lensFacing == CameraSelector.LENS_FACING_BACK) "traseira" else "frontal"
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

            "{\"available\":true" +
                ",\"resolution\":\"${selectedPreset.name}\"" +
                ",\"quality\":\"${selectedQualityProfile.name}\"" +
                ",\"qualityLabel\":\"${selectedQualityProfile.shortLabel}\"" +
                ",\"jpegQuality\":$streamJpegQuality" +
                ",\"targetFps\":$streamTargetFps" +
                ",\"rotation\":\"${selectedRotationMode.name}\"" +
                ",\"autoDiscovery\":$autoDiscoveryEnabled" +
                ",\"audioEnabled\":$audioEnabled" +
                ",\"camera\":\"$cameraName\"" +
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

    private fun startStreaming() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            setError("CONECTE O CELULAR A UMA REDE WI-FI")
            Toast.makeText(this, "Nenhum endereço IPv4 local encontrado.", Toast.LENGTH_LONG).show()
            return
        }

        refreshAddress()
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        server.start()
        server.setAudioEnabled(audioEnabled)
        if (audioEnabled && !audioCapture.start()) {
            audioEnabled = false
            server.setAudioEnabled(false)
            updateAudioButton()
            Toast.makeText(this, "Vídeo iniciado sem áudio.", Toast.LENGTH_SHORT).show()
        }
        statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
        statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        updateConnectionStatus(0)
        streamButton.text = "Parar transmissão"
        streamButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        streamButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }

    private fun stopStreaming() {
        audioCapture.stop()
        server.setAudioEnabled(false)
        server.stop()
        nextEncodeDueNs = 0L
        resetPerformanceStats()
        setReadyState()
        updateConnectionStatus(0)
        streamButton.text = "Iniciar transmissão"
        streamButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gold)
        streamButton.setTextColor(ContextCompat.getColor(this, R.color.black))
    }

    private fun updateConnectionStatus(count: Int) {
        if (!::connectionStatusText.isInitialized) return
        when {
            count > 0 -> {
                connectionStatusText.text = "CONECTADO AO GOAT PRO STUDIO • $count conexão(ões)"
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
            "http://$ip:8080/video"
        } else {
            "Sem endereço Wi-Fi disponível"
        }
    }

    private fun updateStreamInfo() {
        val profile = selectedQualityProfile
        val dimensions = if (actualStreamWidth > 0 && actualStreamHeight > 0) {
            "${actualStreamWidth}×${actualStreamHeight}"
        } else {
            selectedPreset.label
        }
        val fpsLabel = if (streamTargetFps > 0) "$streamTargetFps FPS" else "FPS sem limite"
        val profileLabel = if (profile == QualityProfile.CUSTOM) "Personalizado" else profile.shortLabel
        streamInfoText.text =
            "$dimensions • $fpsLabel • JPEG Q$streamJpegQuality • $profileLabel • ${selectedRotationMode.shortLabel}"
    }

    private fun copyAddressToClipboard() {
        val ip = NetworkUtils.localIpv4()
        if (ip == null) {
            Toast.makeText(this, "Conecte o celular ao Wi-Fi primeiro.", Toast.LENGTH_SHORT).show()
            return
        }
        val url = "http://$ip:8080/video"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GOAT PRO IP", url))
        Toast.makeText(this, "Endereço copiado", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (::orientationListener.isInitialized && orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        if (::addressText.isInitialized) refreshAddress()
    }

    override fun onPause() {
        if (::orientationListener.isInitialized) orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        if (torchEnabled) {
            currentCamera?.cameraControl?.enableTorch(false)
        }
        if (::orientationListener.isInitialized) orientationListener.disable()
        discoveryResponder.stop()
        audioCapture.stop()
        server.setAudioEnabled(false)
        server.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private enum class ResolutionPreset(
        val label: String,
        val size: Size
    ) {
        HD("720p", Size(1280, 720)),
        FHD("1080p", Size(1920, 1080))
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
