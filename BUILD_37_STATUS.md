# GOAT Cam Build 37

- IPv4 preservado.
- IPv6 adicionado ao GOAT CAM.
- URLs IPv6 usam colchetes no formato correto: `http://[IPv6]:8080/video`.
- HTTP/MJPEG abre listeners separados IPv4 e IPv6 na porta 8080.
- RTSP/H.264 abre listeners separados IPv4 e IPv6 na porta 8554.
- SDP RTSP anuncia `IP6` quando a conexão é IPv6.
- Descoberta automática mantém `ip=` para compatibilidade e adiciona `ipv4=` e `ipv6=`.
- Tela e botão copiar mostram os endereços IPv4 e IPv6 disponíveis.
- A transmissão pode iniciar se houver IPv4 ou IPv6.
- Base: Build 36.
- Correção final de strings Kotlin: commit `a574c5f0ddbd1b4f11dc3e4d8a5e78beb7b34178`.
- GitHub Actions `:app:assembleDebug`: SUCCESS.
- Run: `37095581748`.
- Artefato: `goat-cam-build-37-debug` (ID `11264296414`).
- APK SHA-256: `9a55f8a9d616fd5830686fde66df11f6647f0e41a7f315ddee7be2ac535e6286`.
