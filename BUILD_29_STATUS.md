# GOAT Cam Build 29

Status: correções de estabilidade + nova rota Camera1 4K frontal aplicadas; compilação completa concluída com sucesso; APK de teste gerado.

## Problema vindo da Build 28
- 4K frontal continuava travando no aparelho.
- A rota GPU estava usando bitrate de 36–48 Mbps, muito acima do teto de 18 Mbps usado pelo encoder H.264 normal do GOAT Cam para 4K.
- O `RtspH264Server` entregava pacotes de forma síncrona; atraso de rede/cliente podia bloquear a thread que drenava o `MediaCodec`, enchendo buffers e congelando a cadeia câmera -> GPU -> encoder.
- A seleção da fonte priorizava a maior SurfaceTexture, mesmo quando era uma saída 4:3 pesada.
- Quando `getOutputMinFrameDuration()` retornava 0, o código tratava isso como 120 FPS, o que podia superestimar a capacidade de uma fonte de alta resolução.

## Correções de estabilidade Build 29
- 30 FPS continua sendo o padrão seguro.
- Bitrate automático do 4K frontal usa teto normal de aproximadamente 18 Mbps em 4K/30.
- Bitrate manual do caminho 4K frontal é limitado a 28 Mbps.
- A fonte GPU fallback prioriza tamanho adequado a vídeo 16:9.
- `getOutputMinFrameDuration() == 0` cai para 30 FPS, nunca para 120 FPS.
- Fila limitada entre `MediaCodec` e RTSP para a rede não bloquear o encoder.
- Se a fila de rede atrasar, frames antigos podem ser descartados e um novo keyframe é solicitado.
- Buffer TCP do RTSP aumentado de 64 KiB para 512 KiB.

## Nova evidência: IP Webcam no mesmo aparelho
O usuário demonstrou que o aplicativo IP Webcam consegue operar a câmera frontal em 3840x2160 e disponibiliza H.264 via RTSP no mesmo Galaxy S21. Isso invalida a conclusão anterior de que o 4K frontal estivesse reservado somente ao aplicativo nativo Samsung.

As telas mostradas pelo usuário incluem:
- Video resolution: 3840x2160.
- RTSP/H.264 disponível (`/h264.sdp`, além de variantes com Opus/AAC/uLaw).
- Servidor HTTP/RTSP ativo no aparelho.

## Nova rota 4K frontal prioritária
- O erro da tentativa Camera1 antiga era exigir `CamcorderProfile.QUALITY_2160P` antes de tentar 4K.
- A Build 29 agora abre as câmeras frontais legadas e consulta diretamente `Camera.Parameters.getSupportedVideoSizes()` (com fallback para `supportedPreviewSizes`).
- Se a Camera1 publicar exatamente 3840x2160, essa rota vira prioridade mesmo que `CamcorderProfile` não publique perfil 2160p.
- Captura: Camera1 + `MediaRecorder.VideoSource.CAMERA`.
- Encoder: H.264.
- Tamanho: 3840x2160.
- Saída intermediária: MPEG-2 TS em pipe, demultiplexada e enviada ao RTSP existente.
- Bitrate padrão: 18 Mbps.
- FPS padrão: 30; valores maiores só quando selecionados manualmente e aceitos pelo aparelho.
- A rota GPU 3840x2160 permanece apenas como fallback se a Camera1 não expuser 4K exato ou se a rota legada falhar.

## Processo
- Build 28 preservada.
- Checkpoint: `checkpoint-build28-before-4k-stability-2026-10-02`.
- Branch atual: `build-29-goat-cam`.
- Release não alterada.
- Validação `:app:compileDebugKotlin` após a nova rota Camera1: SUCCESS.
- Workflow Android Debug APK run: 37083419675 — SUCCESS.
- Artefato GitHub Actions: `goat-cam-build-29-debug` (ID 11259810389).
- APK: `GOAT-Cam-Build-29-Teste.apk` — 4,899,796 bytes.
- SHA-256 APK: `78f63ceefbb9d6e76dcd430804ede2460583b2f5a467eb40853d553faca8631d`.
