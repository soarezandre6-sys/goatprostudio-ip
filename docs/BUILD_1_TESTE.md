# GOAT PRO IP - Roteiro de teste da Build 1

## Preparação
- Celular Android e computador na mesma rede Wi-Fi.
- GOAT PRO Studio aberto no Windows.

## Teste A - câmera
- Abrir o app.
- Autorizar câmera.
- Confirmar preview.
- Alternar traseira/frontal.
- Testar 720p e 1080p.

## Teste B - transmissão
- Tocar em Iniciar transmissão.
- Confirmar status verde.
- Copiar endereço IP.
- Abrir `http://IP:8080/` no navegador do PC.
- Confirmar vídeo em movimento.
- Testar `http://IP:8080/snapshot.jpg`.

## Teste C - GOAT PRO Studio
- Adicionar fonte IP/MJPEG.
- Usar `http://IP:8080/video`.
- Confirmar imagem.
- Observar atraso, travamentos e FPS percebido.

## Registrar no teste
- Modelo do celular.
- Android.
- Resolução usada.
- Distância/qualidade do Wi-Fi.
- Se abriu no navegador.
- Se abriu no GOAT PRO Studio.
- Atraso aproximado.
- Travamentos.
