from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"trecho não encontrado: {label}")
    return text.replace(old, new, 1)

root = Path('.')
front = root / 'app/src/main/java/com/goatpro/ip/Front4kDirectStreamer.kt'
main = root / 'app/src/main/java/com/goatpro/ip/MainActivity.kt'

text = front.read_text()
old = '''                // Build 35: choose the route that matches what the camera really
                // publishes. If 3840x2160 exists as a PREVIEW size, use the
                // SurfaceTexture path (which can also feed MJPEG /video). If 4K is
                // only a recording/video size, use MediaRecorder instead of forcing
                // recording parameters into the preview path.
                val previewProbe = Camera1GpuH264Streamer.probe(
                    direct.legacyCameraId,
                    direct.width,
                    direct.height
                )
                if (previewProbe?.exactPreview4k == true) {
                    if (startCamera1Gpu(direct)) {
                        return true
                    }
                }

                val samsungS21 =
                    Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                        Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                            .matches(Build.MODEL.orEmpty())
                if (direct.legacyExact4k || samsungS21) {
                    if (startLegacy(direct)) {
                        return true
                    }
                }

                // Last Camera1 fallback: preview/GPU without the OEM video-size key.
                if (startCamera1Gpu(direct)) {
                    return true
                }
'''
new = '''                // Build 36: on Galaxy S21, force the native recording pipeline first.
                // Build 35 still preferred the preview/GPU path whenever Camera1
                // advertised a 3840x2160 preview size, which allowed the same frozen
                // path to be selected again. The S21 front camera is a native 4K video
                // camera, so MediaRecorder is now the first route on this device.
                val samsungS21 =
                    Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                        Regex("^SM-G99[0168].*", RegexOption.IGNORE_CASE)
                            .matches(Build.MODEL.orEmpty())
                if (samsungS21) {
                    lastSourceDescription = "Samsung S21 • MediaRecorder 4K nativo"
                    if (startLegacy(direct)) {
                        return true
                    }
                }

                val previewProbe = Camera1GpuH264Streamer.probe(
                    direct.legacyCameraId,
                    direct.width,
                    direct.height
                )
                if (previewProbe?.exactPreview4k == true) {
                    if (startCamera1Gpu(direct)) {
                        return true
                    }
                }

                if (!samsungS21 && direct.legacyExact4k) {
                    if (startLegacy(direct)) {
                        return true
                    }
                }

                // Last Camera1 fallback: preview/GPU without OEM recording keys.
                if (startCamera1Gpu(direct)) {
                    return true
                }
'''
text = replace_once(text, old, new, 'prioridade de rota build35')
front.write_text(text)

text = main.read_text()
old = '''    private var metricsWindowStartedNs = 0L
    private var analysisFrameCount = 0
    private var encodedFrameCount = 0
    private var encodedBytes = 0L
    private var encodeTimeTotalNs = 0L
'''
new = '''    private var metricsWindowStartedNs = 0L
    private var analysisFrameCount = 0
    private var encodedFrameCount = 0
    private var encodedBytes = 0L
    private var encodeTimeTotalNs = 0L

    @Volatile
    private var front4kH264FrameCount = 0L

    @Volatile
    private var front4kLastFrameNs = 0L

    @Volatile
    private var front4kLastUiNs = 0L
'''
text = replace_once(text, old, new, 'campos diagnóstico 4k')

old = '''                ) {
                    rtspServer.onAccessUnit(
                        data,
                        presentationTimeUs,
                        keyFrame,
                        codecConfig
                    )
                }

                override fun onJpegFrame(data: ByteArray, width: Int, height: Int) {
'''
new = '''                ) {
                    if (!codecConfig && data.isNotEmpty()) {
                        val now = System.nanoTime()
                        front4kH264FrameCount += 1L
                        front4kLastFrameNs = now
                        if (now - front4kLastUiNs >= 1_000_000_000L) {
                            front4kLastUiNs = now
                            val count = front4kH264FrameCount
                            val route = front4kDirectStreamer.lastSourceDescription
                                .ifBlank { "rota 4K em inicialização" }
                            runOnUiThread {
                                performanceText.text =
                                    "4K frontal • H.264 frames: $count • $route"
                            }
                        }
                    }
                    rtspServer.onAccessUnit(
                        data,
                        presentationTimeUs,
                        keyFrame,
                        codecConfig
                    )
                }

                override fun onJpegFrame(data: ByteArray, width: Int, height: Int) {
'''
text = replace_once(text, old, new, 'contador onAccessUnit frontal')

old = '''                        performanceText.text =
                            "4K frontal GPU • H.264 • " +
                                fps + " FPS • " +
'''
new = '''                        performanceText.text =
                            "4K frontal • H.264 • " +
                                fps + " FPS • " +
'''
text = replace_once(text, old, new, 'rótulo performance 4k')

old = '''        if (selectedResolution.directFront4k) {
            h264Encoder.stop()
            rtspServer.start()
'''
new = '''        if (selectedResolution.directFront4k) {
            front4kH264FrameCount = 0L
            front4kLastFrameNs = 0L
            front4kLastUiNs = 0L
            h264Encoder.stop()
            rtspServer.start()
'''
text = replace_once(text, old, new, 'reset contador startStreaming')

old = '''                ",\\\"rtspClients\\\":${rtspServer.activeClientCount()}" +
                ",\\\"h264Running\\\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning() || highSpeedH264Streamer.isRunning()}" +
'''
new = '''                ",\\\"rtspClients\\\":${rtspServer.activeClientCount()}" +
                ",\\\"h264Running\\\":${h264Encoder.isRunning() || front4kDirectStreamer.isRunning() || highSpeedH264Streamer.isRunning()}" +
                ",\\\"front4kFrames\\\":$front4kH264FrameCount" +
                ",\\\"front4kFrameAgeMs\\\":" +
                    (if (front4kLastFrameNs > 0L) (System.nanoTime() - front4kLastFrameNs) / 1_000_000L else -1L) +
'''
text = replace_once(text, old, new, 'estado json 4k')

main.write_text(text)

status = root / 'BUILD_36_STATUS.md'
status.write_text('''# GOAT Cam Build 36\n\n- Galaxy S21 força MediaRecorder 4K nativo antes de qualquer rota GPU/preview.\n- Build 35 podia voltar ao mesmo pipeline GPU quando preview 3840x2160 era anunciado.\n- Contador real de Access Units H.264 4K aparece no próprio GOAT CAM.\n- `/camera/state` expõe `front4kFrames` e `front4kFrameAgeMs`.\n- Chrome `/video` não é usado como prova da rota MediaRecorder, pois MJPEG e H.264 são caminhos diferentes.\n- Build 35 preservada.\n''')
