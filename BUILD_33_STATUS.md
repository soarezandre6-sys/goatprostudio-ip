# GOAT Cam Build 33

Status: correção de estabilidade do 4K frontal aplicada e APK gerado com sucesso.

## Problema vindo da Build 32
- Preview 4K frontal permanecia fluido no celular.
- Ao conectar ao GOAT PRO Studio, a imagem podia abrir e congelar.

## Correções Build 33
- Stream frontal mantém geometria fixa em 3840x2160; a rotação não troca mais o encoder para 2160x3840.
- Aplicado tanto ao caminho Camera1 GPU quanto ao fallback Camera2/GPU.
- Timestamps enviados ao MediaCodec passam a ser monotônicos, evitando PTS repetido/regressivo.
- Bitrate do 4K frontal limitado a 12 Mbps para reduzir picos de rede mantendo 3840x2160 e 30 FPS.
- Fila encoder -> RTSP ampliada.
- Fila RTSP por cliente ampliada de 4 para 24 access units.
- Buffer TCP RTSP ampliado para 2 MB.
- Payload RTP aumentado de 1200 para 1400 bytes para reduzir fragmentação/pacotes em 4K.
- Congestionamento curto não derruba mais imediatamente a conexão; o cliente retoma no próximo keyframe.

## GitHub
- Branch: `build-33-goat-cam`
- Commit principal da correção: `b5dbf3e58df4994159610e18c3666635c75234df`
- Build GitHub Actions: SUCCESS
- Artefato: `goat-cam-build-33-debug`
