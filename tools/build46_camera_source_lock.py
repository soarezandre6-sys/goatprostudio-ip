from pathlib import Path

p = Path('app/src/main/java/com/goatpro/ip/MainActivity.kt')
s = p.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global s
    count = s.count(old)
    if count != 1:
        raise SystemExit(f'{label}: esperado 1 ocorrência, encontrado {count}')
    s = s.replace(old, new, 1)

# 1) O encoder CameraX comum nunca pode continuar injetando quadros quando
# o modo 4K frontal dedicado tomou posse do RTSP.
replace_once(
'''    private val h264Encoder: H264Encoder by lazy {
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
''',
'''    private val h264Encoder: H264Encoder by lazy {
        H264Encoder(object : H264Encoder.Listener {
            override fun onAccessUnit(
                data: ByteArray,
                presentationTimeUs: Long,
                keyFrame: Boolean,
                codecConfig: Boolean
            ) {
                // Build 46: do not let late frames from the previous rear/regular
                // encoder mix with the dedicated front-4K source.
                if (selectedResolution.directFront4k) return
                rtspServer.onAccessUnit(
                    data,
                    presentationTimeUs,
                    keyFrame,
                    codecConfig
                )
            }
        })
    }
''',
'h264 source guard'
)

# 2) O streamer dedicado da frontal só pode publicar enquanto a seleção atual
# ainda for frontal + 4K dedicado. Quadros atrasados após troca de câmera somem.
replace_once(
'''                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    if (!codecConfig && data.isNotEmpty()) {
''',
'''                override fun onAccessUnit(
                    data: ByteArray,
                    presentationTimeUs: Long,
                    keyFrame: Boolean,
                    codecConfig: Boolean
                ) {
                    // Build 46: stale callbacks from a camera that has just been
                    // deselected must never reach the shared RTSP server.
                    if (
                        !selectedResolution.directFront4k ||
                        selectedCameraOption?.facing != CameraSelector.LENS_FACING_FRONT
                    ) return

                    if (!codecConfig && data.isNotEmpty()) {
''',
'front4k stale frame guard'
)

# 3) Troca de câmera vira operação atômica: derruba todos os pipelines antigos,
# muda a seleção e deixa o bind da nova câmera reiniciar uma única transmissão.
replace_once(
'''    private fun selectCameraLens(
        option: CameraLensOption,
        updateSpinner: Boolean = true
    ) {
        if (selectedCameraOption?.key == option.key) return

        if (torchEnabled) {
''',
'''    private fun selectCameraLens(
        option: CameraLensOption,
        updateSpinner: Boolean = true
    ) {
        if (selectedCameraOption?.key == option.key) return

        val wasStreaming = isStreamingActive()
        if (wasStreaming) {
            // Build 46: one selected lens = one active video producer.
            // Stop the old camera/encoder/RTSP path before changing selection.
            autoRestartStreamAfterCameraBind = true
            stopStreaming()
        }

        if (torchEnabled) {
''',
'atomic camera switch'
)

# 4) Protege também o callback assíncrono que prepara o MediaRecorder frontal.
# Se a seleção mudou enquanto CameraProvider respondia, o callback antigo aborta.
old = '''            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                try {
                    val provider = providerFuture.get()
                    provider.unbindAll()
'''
new = '''            val expectedFrontCameraKey = selectedCameraOption?.key
            val expectedFrontResolutionKey = selectedResolution.key
            val providerFuture = ProcessCameraProvider.getInstance(this)
            providerFuture.addListener({
                if (
                    !selectedResolution.directFront4k ||
                    selectedCameraOption?.key != expectedFrontCameraKey ||
                    selectedResolution.key != expectedFrontResolutionKey ||
                    !server.isRunning()
                ) {
                    return@addListener
                }
                try {
                    val provider = providerFuture.get()
                    provider.unbindAll()
'''
# Esse padrão aparece em mais de um fluxo; altere apenas o primeiro depois do marcador frontal.
marker = 'statusText.text = "INICIANDO 4K FRONTAL H.264…"'
pos = s.find(marker)
if pos < 0:
    raise SystemExit('front4k async marker não encontrado')
tail = s[pos:]
count = tail.count(old)
if count < 1:
    raise SystemExit('front4k async callback não encontrado após marcador')
tail = tail.replace(old, new, 1)
s = s[:pos] + tail

p.write_text(s, encoding='utf-8')
print('Build 46 camera source lock aplicado')
