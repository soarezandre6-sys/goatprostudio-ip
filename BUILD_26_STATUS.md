# GOAT Cam Build 26

Status: código preparado; APK ainda não gerado.

## 4K frontal
- Build 24 Camera2 + MediaCodec: recusado no aparelho.
- Build 25 Camera2 + MediaRecorder: recusado/travou no aparelho.
- Build 26: Camera1 legado + MediaRecorder H.264.
- 4K só é oferecido se a câmera frontal Camera1 publicar `CamcorderProfile.QUALITY_2160P` exato 3840x2160.
- removido fallback que inferia 4K apenas pelo tamanho do sensor.

## Segundo plano / tela apagada
- Home e bloqueio continuam protegidos por Foreground Service + WakeLock + Wi-Fi Lock.
- para RTSP em HD/FHD sem cliente MJPEG, CameraX/ImageAnalysis é desligado ao sair da tela.
- Camera2 envia direto para uma Surface do encoder H.264 de hardware.
- objetivo: reduzir cópia NV21, CPU, aquecimento e travadas.
- ao voltar ao app, o encoder direto é encerrado e Preview + ImageAnalysis são restaurados.
- se o caminho direto não abrir, há fallback automático para o modo headless da Build 25.

## Observação
A Build 26 ainda precisa de compilação e teste no aparelho.
