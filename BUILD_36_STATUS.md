# GOAT Cam Build 36

- Galaxy S21 força MediaRecorder 4K nativo antes de qualquer rota GPU/preview.
- Build 35 podia voltar ao mesmo pipeline GPU quando preview 3840x2160 era anunciado.
- Contador real de Access Units H.264 4K aparece no próprio GOAT CAM.
- `/camera/state` expõe `front4kFrames` e `front4kFrameAgeMs`.
- Chrome `/video` não é usado como prova da rota MediaRecorder, pois MJPEG e H.264 são caminhos diferentes.
- Build 35 preservada.
