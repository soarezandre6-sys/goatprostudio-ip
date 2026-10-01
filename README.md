# GOAT PRO IP

Aplicativo Android gratuito e companheiro do **GOAT PRO Studio**.

## Objetivo
Transformar o smartphone em uma câmera IP pronta para ser usada no GOAT PRO Studio, sem depender de aplicativos de terceiros.

## Build 1 - v0.1.0-alpha
Estado atual do MVP:

- identidade visual GOAT PRO IP com logo GOAT invertida em dourado/preto;
- câmera traseira e frontal;
- preview local com CameraX;
- presets 720p e 1080p;
- alvo/cap de processamento em aproximadamente 30 FPS;
- transmissão MJPEG pela rede Wi-Fi local;
- servidor HTTP interno na porta `8080`;
- URL principal exibida no app: `http://IP_DO_CELULAR:8080/video`;
- botão iniciar/parar transmissão;
- botão copiar endereço;
- endpoint `/snapshot.jpg` para quadro JPEG atual;
- endpoint `/health` para teste simples de disponibilidade;
- página local `/` para teste rápido em navegador.

## Teste da Build 1
1. Conecte celular e PC na mesma rede Wi-Fi.
2. Abra o GOAT PRO IP e autorize a câmera.
3. Selecione 720p ou 1080p.
4. Toque em **Iniciar transmissão**.
5. No PC, teste primeiro a URL exibida pelo app em um navegador.
6. Depois use a mesma URL no GOAT PRO Studio como fonte IP/MJPEG.

## Meta de validação
A Build 1 precisa provar quatro pontos antes de crescer:

1. transmitir vídeo de forma estável;
2. mostrar IP/porta corretamente;
3. abrir a transmissão dentro do GOAT PRO Studio;
4. só então evoluir automação, áudio, lanterna e descoberta automática.

## Roadmap curto
### Build 2
- áudio opcional;
- lanterna;
- status de conexão mais completo;
- tela de vínculo com o GOAT PRO Studio;
- refinamento de estabilidade.

### Build 3
- descoberta automática do GOAT PRO IP na mesma rede;
- conexão simplificada sem digitar IP;
- integração nativa no painel de câmeras do GOAT PRO Studio.

## Observação técnica
MJPEG é proposital nesta primeira etapa por ser simples de testar e integrar. Em 1080p/30 ele pode consumir bastante CPU e banda em alguns celulares. Se os testes mostrarem gargalo, a evolução natural é H.264/RTSP ou WebRTC com codificação por hardware.
