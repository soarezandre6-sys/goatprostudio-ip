# GOAT CAM Build 45

## Correcoes confirmadas
- Controle de bitrate H.264 4K limitado a 8-18 Mbps.
- Alterar bitrate durante 4K nao derruba nem reabre a camera; entra no proximo inicio do stream.
- Diagnostico visivel do RTSP: transporte TCP/UDP, fila atual/pico, tempo de envio e estouros de fila.
- Buffer TCP do fallback HTTP/MJPEG reduzido de 1 MB para 256 KB para priorizar baixa latencia.
- Mantidos resolucao 4K, rota nativa/fallback, bitrate automatico e qualidade da Build 44.

## Objetivo do teste
Separar gargalo de captura/encoder de gargalo de rede/envio. Se o FPS H.264 estiver alto e a fila/tempo de envio crescer, o atraso esta depois do encoder. Se o FPS estiver baixo com fila vazia e envio rapido, o gargalo esta antes do RTSP.
