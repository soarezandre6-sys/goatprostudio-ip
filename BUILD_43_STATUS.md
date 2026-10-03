# GOAT CAM — BUILD 43

"
    "Base: Build 42 funcional.

"
    "Objetivo: melhorar fluidez do 4K frontal sem alterar o protocolo que voltou a funcionar.

"
    "Mudancas isoladas:
"
    "- preserva a rota Samsung S21 MediaRecorder/MPEG-TS/H.264 da Build 42;
"
    "- tenta fixar a faixa Camera1 no FPS alvo (preferencia por 30-30 quando publicada);
"
    "- reduz alocacoes no parser MPEG-TS reutilizando o buffer PES;
"
    "- libera bitrate nativo ate 18 Mbps, sem repetir o experimento de 28 Mbps da Build 41;
"
    "- pede efeito de cor NONE quando suportado;
"
    "- mostra FPS H.264 real medido no app para diferenciar 15/20/30 FPS.

"
    "Nao alterado:
"
    "- RTSP server/protocolo;
"
    "- fallback MJPEG;
"
    "- formato 3840x2160;
"
    "- ordem de fallback de camera;
"
    "- GOAT PRO Studio.
"
    