# GOAT Cam Build 31

Status: nova rota 4K frontal implementada e compilação Kotlin validada com sucesso; APK de teste ainda não gerado.

## Problema vindo da Build 30
- O 4K frontal continuava travando no Galaxy S21.
- A rota principal ainda dependia de Camera1 + MediaRecorder + MPEG-2 TS + demux antes do RTSP.
- Esse caminho adiciona mux/demux, pipe e buffering desnecessários para transmissão ao vivo.
- A lógica antiga também tratava `supportedVideoSizes` e `supportedPreviewSizes` como alternativas, podendo ignorar um 4K presente em uma lista quando a outra lista existia.

## Nova rota Build 31
- Nova classe `Camera1GpuH264Streamer`.
- Fluxo principal: `Camera1 -> SurfaceTexture -> OpenGL ES -> MediaCodec H.264 -> RTSP`.
- Sem MediaRecorder no caminho principal.
- Sem MPEG-2 TS no caminho principal.
- Sem demux TS no caminho principal.
- Consulta `supportedPreviewSizes` e `supportedVideoSizes` separadamente.
- Se `supportedPreviewSizes` publicar 3840x2160, usa essa fonte diretamente.
- Se só `supportedVideoSizes` publicar 3840x2160, isso fica registrado no diagnóstico da rota.
- Se nenhum deles publicar 4K exato, usa a melhor fonte frontal disponível e a GPU compõe a saída 3840x2160 como fallback.
- Encoder H.264 por `MediaCodec` com Surface de entrada, GOP curto, B-frames desativados quando disponível e pedido de keyframe quando necessário.
- Fila curta entre encoder e RTSP para a rede não bloquear a drenagem do MediaCodec.
- 30 FPS continua sendo o padrão seguro.
- Bitrate padrão continua na faixa de 18 Mbps para 4K/30.

## Fallbacks
1. Camera1 GPU H.264 (novo caminho principal).
2. Camera2 GPU H.264 existente se a rota Camera1 GPU falhar.
3. O código antigo MediaRecorder permanece no arquivo apenas como legado, mas não é mais selecionado pelo fluxo principal do `start()`.

## Diagnóstico útil no aparelho
A rota registra em `lastSourceDescription` qual fonte foi usada, por exemplo:
- `preview 4K nativo`
- `video-size 4K`
- `composição GPU`

## Processo
- Build 30 preservada.
- Checkpoint: `checkpoint-build30-before-camera1-gpu-2026-10-02`.
- Branch atual: `build-31-goat-cam`.
- Release não alterada.
- Validação `:app:compileDebugKotlin`: SUCCESS.
- APK Build 31 ainda não gerado.
