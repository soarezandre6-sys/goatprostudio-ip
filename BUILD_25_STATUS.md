# GOAT Cam Build 25 — 4K frontal

Status: APK de teste gerado com sucesso.

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
- foreground service;
- presets 1/2/3;
- Smart Link;
- marca d'água Free;
- bitrate manual;
- 4K traseiro e lentes traseiras.

## Correção segundo plano / tela apagada
- adicionada chave nativa `Continuar transmitindo em segundo plano / tela apagada`;
- padrão: LIGADO;
- foreground service só é mantido quando a chave está ligada;
- WakeLock e Wi-Fi lock continuam ativos;
- ao pressionar Home ou bloquear a tela no stream normal, CameraX troca para captura headless (ImageAnalysis sem PreviewView);
- ao voltar ao app, o Preview é restaurado sem parar MJPEG/RTSP;
- no 4K frontal MediaRecorder, a sessão Camera2 própria é preservada e não depende do PreviewView.

## APK de teste
- workflow run: `37073251031`
- artifact: `goat-cam-build-25-debug`
- artifact id: `11255467532`
- APK SHA-256: `2eaaf4b6d287ba644b02547ceeb4dcdcf7f9c0dbb7b77209c087a94a9687a6de`
- tamanho do APK: `4867028 bytes`

## Próximo teste
1. Testar 4K frontal.
2. Com transmissão ativa e a chave de segundo plano LIGADA, apertar Home e confirmar que o vídeo continua sem reconectar.
3. Bloquear/apagar a tela e confirmar que o vídeo continua sem perder a sessão.
4. Se o 4K frontal falhar, registrar a mensagem completa depois de `4K frontal recusado`.
