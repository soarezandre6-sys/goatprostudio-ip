package com.goatpro.ip

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
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

    private lateinit var cameraExecutor: ExecutorService
    private var currentCamera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedPreset = ResolutionPreset.FHD
    private var ignoreFirstSpinnerCallback = true
    private var lastEncodedFrameNs = 0L
    private var torchEnabled = false
    private var audioEnabled = false

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
        if (granted && server.isRunning()) {
            if (!audioCapture.start()) {
                audioEnabled = false
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
        cameraExecutor = Executors.newSingleThreadExecutor()

        applyPreviewAspectRatio()
        setupResolutionSelector()
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
        val labels = ResolutionPreset.entries.map { it.label }
        resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        resolutionSpinner.setSelection(ResolutionPreset.entries.indexOf(selectedPreset))
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newPreset = ResolutionPreset.entries[position]
                if (ignoreFirstSpinnerCallback) {
                    ignoreFirstSpinnerCallback = false
                    return
                }
                if (newPreset != selectedPreset) {
                    selectedPreset = newPreset
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

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
            val size = selectedPreset.size

            val preview = Preview.Builder()
                .setTargetResolution(size)
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(size)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor) { image ->
                        try {
                            if (!server.isRunning()) return@setAnalyzer

                            val now = System.nanoTime()
                            if (now - lastEncodedFrameNs < FRAME_INTERVAL_NS) return@setAnalyzer
                            lastEncodedFrameNs = now

                            ImageUtils.imageProxyToJpeg(image, selectedPreset.jpegQuality)
                                ?.let(server::offerFrame)
                        } finally {
                            image.close()
                        }
                    }
                }

            try {
                provider.unbindAll()
                currentCamera = provider.bindToLifecycle(this, selector, preview, analysis)
                torchEnabled = false
                updateTorchButton()
                if (!server.isRunning()) setReadyState()
            } catch (_: Exception) {
                currentCamera = null
                updateTorchButton()
                setError("ERRO AO ABRIR A CÂMERA")
            }
        }, ContextCompat.getMainExecutor(this))
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
            audioCapture.stop()
            updateAudioButton()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioEnabled = true
            if (server.isRunning() && !audioCapture.start()) {
                audioEnabled = false
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
        server.start()
        if (audioEnabled && !audioCapture.start()) {
            audioEnabled = false
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
        streamInfoText.text = "${selectedPreset.label} • alvo 30 FPS • MJPEG / Wi-Fi local"
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
        if (::addressText.isInitialized) refreshAddress()
    }

    override fun onDestroy() {
        if (torchEnabled) {
            currentCamera?.cameraControl?.enableTorch(false)
        }
        audioCapture.stop()
        server.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private enum class ResolutionPreset(
        val label: String,
        val size: Size,
        val jpegQuality: Int
    ) {
        HD("720p", Size(1280, 720), 76),
        FHD("1080p", Size(1920, 1080), 70)
    }

    companion object {
        private const val FRAME_INTERVAL_NS = 33_333_333L
        private const val PREVIEW_HEIGHT_RATIO = 9f / 16f
    }
}
