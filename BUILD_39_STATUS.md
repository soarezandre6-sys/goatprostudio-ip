# GOAT CAM Build 39 — Status

## Origem
Build 38 fez a frontal 4K aparecer em HTTP/MJPEG, mas o fluxo ficou pesado, com poucos quadros, perda de detalhe percebida e travamento progressivo.

## Causa tratada
A Build 38 disparava capturas JPEG 4K em temporizador fixo. Quando uma captura demorava mais que o intervalo, novas capturas podiam se acumular na sessão Camera2.

## Correções da Build 39
- Apenas 1 captura MJPEG pode ficar em andamento por vez (`mjpegCaptureInFlight`).
- O próximo JPEG é agendado após a conclusão do quadro anterior; não há fila crescente de snapshots.
- Alvo MJPEG aumentado de 5 FPS para até 12 FPS, com autorregulação pela latência real da câmera.
- Qualidade JPEG limitada à faixa 82–92 (padrão 88) para preservar detalhe sem explodir o tamanho dos quadros.
- Watchdog de 1,5 s libera a captura se o HAL aceitar um snapshot mas não devolver JPEG.
- H.264/GPU continua independente do MJPEG.

## Build
- Branch: `build-39-goat-cam`
- Commit principal do controle de fluxo: `5f6bc20edc3c8069ceed6d206a63698b314347e5`
- Correção de compilação Kotlin aplicada em seguida.
- GitHub Actions run final: `37098402280`
- Resultado: SUCCESS
- Artifact: `goat-cam-build-39-debug`
- Artifact ID: `11265421296`
- SHA-256 do ZIP: `09042c39d049a93ca496a6a34acd518a7642995e478f1d031a66bed033881c0d`

## Teste recomendado
1. Frontal 1 -> 4K -> iniciar transmissão.
2. Abrir `http://IP:8080/video` e observar por pelo menos 30 segundos.
3. Repetir no GOAT PRO Studio.
4. Confirmar se a imagem deixou de acumular travamentos e se ganhou fluidez/detalhe.

Se o MJPEG 4K permanecer naturalmente pesado mesmo sem travamento progressivo, o próximo passo recomendado é fazer o GOAT PRO Studio preferir RTSP/H.264 (`:8554/h264`) para 4K fluido, mantendo MJPEG principalmente como compatibilidade/navegador.
