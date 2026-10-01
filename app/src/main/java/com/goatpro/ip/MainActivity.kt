package com.goatpro.ip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var addressText: TextView
    private lateinit var streamInfoText: TextView
    private lateinit var streamButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var torchButton: Button
    private lateinit var audioButton: Button
    private lateinit var copyAddressButton: Button
    private lateinit var resolutionSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var rotationSpinner: Spinner

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
    private var selectedRotationMode = RotationMode.AUTO

    private var lastEncodedFrameNs = 0L
    private var torchEnabled = false
    private var audioEnabled = false
    private var actualStreamWidth = 0
    private var actualStreamHeight = 0

    private val server by lazy {
        MjpegServer(8080, object : MjpegServer.Listener {
            override fun onVideoClientCountChanged(count: Int) {
                runOnUiThread { updateConnectionStatus(count) }
            }
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
        streamButton = findViewById(R.id.streamButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        torchButton = findViewById(R.id.torchButton)
        audioButton = findViewById(R.id.audioButton)
        copyAddressButton = findViewById(R.id.copyAddressButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        rotationSpinner = findViewById(R.id.rotationSpinner)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupOrientationTracking()

        applyPreviewAspectRatio()
        setupResolutionSelector()
        setupQualitySelector()
        setupRotationSelector()
        refreshAddress()
        updateStreamInfo()
        updateConnectionStatus(0)
        updateAudioButton()
        updateTorchButton()
        discoveryResponder.start()

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
                    lastEncodedFrameNs = 0L
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
                    lastEncodedFrameNs = 0L
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
                    lastEncodedFrameNs = 0L
                    applyCameraTargetRotation(
                        if (mode == RotationMode.AUTO) autoSurfaceRotation else Surface.ROTATION_0
                    )
                    updateStreamInfo()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor) { image ->
                        try {
                            if (!server.isRunning()) return@setAnalyzer

                            val profile = selectedQualityProfile
                            val now = System.nanoTime()
                            val frameIntervalNs = 1_000_000_000L / profile.targetFps
                            if (now - lastEncodedFrameNs < frameIntervalNs) return@setAnalyzer
                            lastEncodedFrameNs = now

                            val rotation = selectedRotationMode.resolve(image.imageInfo.rotationDegrees)
                            val encoded = ImageUtils.imageProxyToJpeg(
                                image = image,
                                quality = profile.jpegQuality,
                                rotationDegrees = rotation
                            )

                            if (encoded != null) {
                                server.offerFrame(encoded.bytes)
                                if (encoded.width != actualStreamWidth || encoded.height != actualStreamHeight) {
                                    actualStreamWidth = encoded.width
                                    actualStreamHeight = encoded.height
                                    runOnUiThread { updateStreamInfo() }
                                }
                            }
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
                    lastEncodedFrameNs = 0L
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
        lastEncodedFrameNs = 0L
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
        lastEncodedFrameNs = 0L
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
                connectionStatusText.text = "TRANSMISSÃO ATIVA • AGUARDANDO CONEXÃO"
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
        streamInfoText.text =
            "$dimensions • ${profile.targetFps} FPS • JPEG Q${profile.jpegQuality} • ${profile.shortLabel} • ${selectedRotationMode.shortLabel}"
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
            "Baixa latência • Q62 • 30 FPS",
            "Baixa latência",
            62,
            30
        ),
        BALANCED(
            "Equilibrado • Q80 • 30 FPS",
            "Equilibrado",
            80,
            30
        ),
        HIGH_QUALITY(
            "Alta qualidade • Q90 • 25 FPS",
            "Alta qualidade",
            90,
            25
        ),
        MAX_QUALITY(
            "Máxima qualidade • Q96 • 20 FPS",
            "Máxima qualidade",
            96,
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
