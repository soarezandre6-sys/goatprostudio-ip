# GOAT Cam Build 32

Status: revisão de travamento 4K frontal aplicada; compilação Kotlin validada com sucesso; APK ainda não gerado.

## Problema vindo da Build 31
- O 4K frontal continuou congelando no Galaxy S21.
- A rota Camera1 -> GPU -> MediaCodec já mantinha câmera e encoder separados da CPU pesada, mas a entrega RTSP ainda podia bloquear em escrita de socket, principalmente em 4K.
- A fila entre encoder e RTSP protegia o MediaCodec, porém o cliente podia continuar vendo um quadro congelado se a thread de entrega ficasse presa no socket.

## Correções Build 32
- Cada cliente RTSP passa a ter fila própria e thread de envio própria.
- A captura/encoder não escreve mais diretamente no socket do Studio.
- Fila RTSP limitada: atraso não gera backlog infinito.
- Em congestionamento, frames antigos são descartados e o cliente espera novo keyframe.
- Após congestionamento repetido, a conexão do cliente é encerrada para permitir reconexão limpa em vez de permanecer congelada.
- SPS/PPS continuam sendo reenviados junto ao keyframe quando necessário.
- No Camera1 GPU foi adicionado watchdog de saúde com diagnóstico por estágio:
  - DIAG CAMERA: câmera parou de entregar frames.
  - DIAG ENCODER: câmera segue ativa, mas H.264 parou.
  - DIAG RTSP/REDE: encoder segue gerando H.264, mas entrega ao Studio parou.
- Em pressão persistente na fila de entrega, o bitrate H.264 pode reduzir gradualmente até 10 Mbps sem mudar resolução nem FPS.
- 30 FPS permanece como padrão.
- 4K 3840x2160 permanece disponível na frontal do Galaxy S21.

## Processo
- Build 31 preservada.
- Checkpoint: `checkpoint-build31-before-diagnostics-2026-10-02`.
- Branch atual: `build-32-goat-cam`.
- Release não alterada.
- Validação `:app:compileDebugKotlin`: SUCCESS.
- Arquivos temporários de patch/workflow removidos após a validação.
- APK Build 32 ainda não gerado.
