# GOAT CAM Build 40 — Status

## Origem
Build 39 passou a exibir a frontal 4K no GOAT PRO Studio, porém o MJPEG 4K permaneceu pesado e com aparência excessivamente suavizada. Durante a transmissão direta, o Live View local do GOAT CAM também ficava preto porque o CameraX era desligado para liberar a câmera ao pipeline 4K.

## Correções implementadas
- Live View local da frontal 4K agora usa a mesma textura OES/GPU que alimenta o MediaCodec H.264.
- Não abre uma segunda câmera e não usa JPEG para o preview do celular.
- A textura é desenhada em duas superfícies EGL: encoder H.264 e TextureView local.
- Preview local limitado a 15 FPS para não bloquear o H.264 4K.
- Falha/perda do Surface local não encerra a transmissão H.264.
- MJPEG continua disponível apenas como saída de compatibilidade/navegador.
- Teto de bitrate do fallback GPU 4K elevado de 12 Mbps para até 28 Mbps, respeitando o bitrate solicitado, para preservar mais textura/detalhe.

## Branch e validação
- Branch: `build-40-goat-cam`
- Commit principal: `9d871a9aa3cbd98a6bcc14716ffc319f2127a29d`
- Compile check: GitHub Actions run `37099686954`
- Resultado: SUCCESS (`:app:compileDebugKotlin`)
- APK NÃO gerado nesta etapa.

## Próximo teste
Usar junto com a branch de teste do GOAT PRO Studio que prefere RTSP/H.264 para GOAT CAM 4K. Gerar APK somente após autorização.
