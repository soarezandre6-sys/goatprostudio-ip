# GOAT Cam Build 29

Status: correção de estabilidade aplicada; compilação Kotlin validada com sucesso; APK de teste ainda não gerado.

## Problema vindo da Build 28
- 4K frontal continuava travando no aparelho.
- A rota GPU estava usando bitrate de 36–48 Mbps, muito acima do teto de 18 Mbps usado pelo encoder H.264 normal do GOAT Cam para 4K.
- O `RtspH264Server` entrega pacotes de forma síncrona; atraso de rede/cliente podia bloquear a thread que drenava o `MediaCodec`, enchendo buffers e congelando a cadeia câmera -> GPU -> encoder.
- A seleção da fonte priorizava a maior SurfaceTexture, mesmo quando era uma saída 4:3 pesada.
- Quando `getOutputMinFrameDuration()` retornava 0, o código tratava isso como 120 FPS, o que podia superestimar a capacidade de uma fonte de alta resolução.

## Correções Build 29
- 30 FPS continua sendo o padrão seguro.
- Bitrate automático do 4K frontal passa a usar `H264Encoder.recommendedBitrate`, com teto normal de aproximadamente 18 Mbps em 4K/30.
- Bitrate manual do caminho 4K frontal é limitado a 28 Mbps para evitar saturação do pipeline experimental.
- A fonte pública escolhida para a composição 4K passa a priorizar o tamanho mais adequado ao vídeo 16:9, em vez de escolher cegamente a maior saída 4:3.
- `getOutputMinFrameDuration() == 0` agora cai para 30 FPS, nunca para 120 FPS.
- Nova fila limitada entre `MediaCodec` e RTSP. A thread de drain do encoder não bloqueia mais esperando rede/Studio.
- Se a fila de rede atrasar, o pipeline descarta frame antigo, mantém latência limitada e solicita novo keyframe.
- Buffer TCP do RTSP aumentado de 64 KiB para 512 KiB para absorver picos de IDR 4K.

## Observação sobre 4K frontal Samsung
- O Galaxy S21 testado grava 4K/60 no aplicativo nativo Samsung.
- Nos diagnósticos do GOAT Cam, a API pública do aparelho não publicou uma Surface frontal 3840x2160 nem perfil público 2160p utilizável por terceiros.
- Portanto, a rota atual é `fonte frontal pública -> GPU -> saída H.264 3840x2160`.
- Isso produz uma saída 3840x2160, mas não cria detalhe nativo que a HAL pública não entrega. O 4K nativo do app Samsung continua dependente do caminho interno/privilegiado do fabricante, que não foi exposto nas APIs públicas diagnosticadas.

## Processo
- Build 28 preservada.
- Checkpoint: `checkpoint-build28-before-4k-stability-2026-10-02`.
- Branch atual: `build-29-goat-cam`.
- Release não alterada.
- Validação `:app:compileDebugKotlin`: SUCCESS.
