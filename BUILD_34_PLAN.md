# GOAT Cam Build 34

Objetivo: corrigir congelamento do HTTP/MJPEG em 4K frontal.

Diagnóstico confirmado na Build 33: a rota directFront4k desliga o ImageAnalysis que alimenta MjpegServer.offerFrame(). A porta 8080 permanece aberta, mas /video deixa de receber quadros novos e o navegador exibe um frame antigo/congelado.

Correção prevista:
- manter H.264/RTSP 4K via Camera1 -> SurfaceTexture -> GPU -> MediaCodec;
- habilitar callback NV21 da mesma Camera1 somente quando existir cliente MJPEG;
- JPEG 4K em worker separado, com fila latest-frame e limite de FPS para não bloquear câmera/H.264;
- limpar frame MJPEG antigo ao iniciar transmissão;
- aumentar buffer TCP do HTTP para quadros 4K;
- voltar a anunciar HTTP /video também no modo frontal 4K.
