# GOAT Cam Build 35

"
    "Correção focada no congelamento 4K da câmera frontal no próprio GOAT CAM.

"
    "- Remove `video-size=3840x2160` da rota Camera1 preview/SurfaceTexture.
"
    "- Se a câmera publica preview 4K real, mantém preview 4K + GPU/MJPEG.
"
    "- Se 4K existe apenas como modo de gravação, prioriza MediaRecorder nativo 4K.
"
    "- Galaxy S21 recebe tentativa da rota nativa antes de composição GPU de fallback.
"
    "- Se a rota preview travar em execução, tenta MediaRecorder nativo antes do fallback Camera2.
"
    "- Build 34 preservada; alterações isoladas em `build-35-goat-cam`.
"
    