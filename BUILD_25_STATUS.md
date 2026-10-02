# GOAT Cam Build 25 — 4K frontal

Status: código preparado; APK ainda não gerado.

## Motivo
A Build 24 continuou recebendo `4K frontal recusado` no Galaxy S21 mesmo com a câmera nativa Samsung gravando em 4K na frontal.

## Mudança principal
O 4K frontal não usa mais a Surface direta do `MediaCodec` da Build 24.

A Build 25 usa:
- `MediaRecorder.VideoSource.SURFACE`;
- H.264;
- 3840x2160;
- valores do `CamcorderProfile.QUALITY_2160P` quando disponíveis;
- `TEMPLATE_RECORD` do Camera2;
- stream-use-case VIDEO_RECORD quando anunciado;
- saída MPEG-2 TS em pipe de memória;
- demux H.264 Annex-B para o RTSP existente do GOAT Cam;
- watchdog de 5 segundos sem frames;
- fallback para Full HD se a câmera recusar a sessão.

## Preservado da Build 24
- segundo plano / Home / tela apagada;
- foreground service;
- presets 1/2/3;
- Smart Link;
- marca d'água Free;
- bitrate manual;
- 4K traseiro e lentes traseiras.

## Próximo teste
Selecionar a frontal, escolher 4K e iniciar a transmissão. Se falhar, registrar a mensagem completa depois de `4K frontal recusado` para distinguir recusa da sessão Camera2, falha do MediaRecorder ou ausência de frames.
