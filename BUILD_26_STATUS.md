# GOAT Cam Build 26

Status: APK de teste compilado com sucesso em 2026-10-02.

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

## Build
- workflow: Android Debug APK run 37075554098
- resultado: success
- artefato: goat-cam-build-26-debug
- artifact id: 11256691092
- APK extraído: GOAT-Cam-Build-26-Teste.apk
- SHA-256 do APK: `5bf2ac3287397947a018b49e5cc2bf4ee5b0f68641508d31ce4edb03a464bb26`

## Próximo teste no aparelho
1. verificar se o 4K frontal aparece; se aparecer, iniciar transmissão e observar se abre sem travar;
2. em Full HD RTSP, apertar Home e depois bloquear a tela;
3. observar fluidez, aquecimento e se a conexão permanece contínua;
4. voltar ao app e confirmar se o preview retorna sem derrubar a transmissão.
