# GOAT Cam Build 24 — resultado

- Branch: `build-24-goat-cam`
- Workflow: Android Debug APK
- Run: `37071307395` (#35)
- Resultado: sucesso
- Artifact: `goat-cam-build-24-debug`
- Artifact ID: `11254588504`
- Artifact digest: `sha256:c53ed1c876e2eadff6c33e7e35830735748e9cccd24c21a85cf43542628fd416`
- APK de teste: `GOAT-Cam-Build-24-Teste.apk`
- Tamanho do APK: 4.866.844 bytes
- APK SHA-256: `f08c1c45ed776df14686d016eb5122000dfa4d4d5a77f9bcdfc160cd59ad758d`

## Pontos principais para testar
- 4K frontal nas câmeras frontais elegíveis;
- watchdog de 4 segundos e fallback automático para Full HD se o 4K não entregar frames;
- transmissão continua ao apertar Home;
- transmissão continua com tela apagada, usando foreground service + wake lock + Wi-Fi lock;
- Preset 1/2/3: salvar e aplicar câmera, resolução e controles manuais;
- Smart Link e recursos da Build 23 preservados.
