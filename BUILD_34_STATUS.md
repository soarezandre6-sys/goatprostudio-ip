# GOAT Cam Build 34

Status: correção do congelamento HTTP/MJPEG no 4K frontal aplicada e APK gerado com sucesso.

## Causa confirmada
Na Build 33, o modo `directFront4k` iniciava o servidor HTTP na porta 8080, mas desligava o `ImageAnalysis` que normalmente gera JPEGs para `MjpegServer.offerFrame()`. O endpoint `/video` permanecia aberto, porém sem quadros novos; por isso Chrome e outros clientes HTTP exibiam um frame antigo/congelado.

## Correções Build 34
- 4K frontal continua usando Camera1 -> SurfaceTexture -> GPU -> MediaCodec para H.264/RTSP.
- A mesma Camera1 passa a fornecer NV21 para MJPEG quando existe cliente HTTP conectado; a câmera não é aberta duas vezes.
- JPEG é comprimido em worker separado para não bloquear câmera/H.264.
- Fila MJPEG usa somente o quadro mais recente e descarta atraso.
- MJPEG frontal limitado a até 15 FPS para estabilidade; H.264/RTSP mantém o alvo de 30 FPS.
- Frame MJPEG antigo é limpo ao iniciar uma nova transmissão.
- Buffer TCP HTTP ampliado para quadros UHD.
- O aplicativo volta a anunciar `http://IP:8080/video` também no modo 4K frontal, além de `rtsp://IP:8554/h264`.

## Validação
- Branch: `build-34-goat-cam`.
- Commit principal da correção: `f9a42278c4eacc65638e0e65b36254502786fac9`.
- GitHub Actions: SUCCESS.
- APK: `goat-cam-build-34-debug`.
- SHA-256 do artefato ZIP: `2eb5722011d7087974bdb0af39bca607b333defddfb8782a412a2cff7a35ceb4`.

## Teste principal
1. Selecionar câmera frontal e 4K.
2. Iniciar transmissão.
3. Abrir `http://IP_DO_CELULAR:8080/video` no Chrome e confirmar imagem contínua.
4. Em seguida, testar H.264/RTSP no GOAT PRO Studio.
