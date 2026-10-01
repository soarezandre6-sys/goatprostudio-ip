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
    private lateinit var addressText: TextView
    private lateinit var streamInfoText: TextView
    private lateinit var streamButton: Button
    private lateinit var switchCameraButton: Button
    private lateinit var copyAddressButton: Button
    private lateinit var resolutionSpinner: Spinner

    private lateinit var cameraExecutor: ExecutorService
    private val server = MjpegServer(8080)
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var selectedPreset = ResolutionPreset.FHD
    private var ignoreFirstSpinnerCallback = true
    private var lastEncodedFrameNs = 0L

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            setError("PERMISSÃO DE CÂMERA NECESSÁRIA")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        streamInfoText = findViewById(R.id.streamInfoText)
        streamButton = findViewById(R.id.streamButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        copyAddressButton = findViewById(R.id.copyAddressButton)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        cameraExecutor = Executors.newSingleThreadExecutor()

        applyPreviewAspectRatio()
        setupResolutionSelector()
        refreshAddress()
        updateStreamInfo()

        streamButton.setOnClickListener {
            if (server.isRunning()) stopStreaming() else startStreaming()
        }

        switchCameraButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }

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

                            // Cap the software encoder to a maximum of ~30 FPS.
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
                provider.bindToLifecycle(this, selector, preview, analysis)
                if (!server.isRunning()) setReadyState()
            } catch (_: Exception) {
                setError("ERRO AO ABRIR A CÂMERA")
            }
        }, ContextCompat.getMainExecutor(this))
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
        statusText.text = "TRANSMITINDO PARA O GOAT PRO STUDIO"
        statusText.setTextColor(ContextCompat.getColor(this, R.color.green))
        streamButton.text = "Parar transmissão"
        streamButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red)
        streamButton.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }

    private fun stopStreaming() {
        server.stop()
        lastEncodedFrameNs = 0L
        setReadyState()
        streamButton.text = "Iniciar transmissão"
        streamButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.gold)
        streamButton.setTextColor(ContextCompat.getColor(this, R.color.black))
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
