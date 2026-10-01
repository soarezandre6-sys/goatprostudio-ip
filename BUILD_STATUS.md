# GOAT PRO IP — Status do projeto

## Build 1
Estado atual: **APK de teste gerado com sucesso e pronto para validação em aparelho Android real**.

### Implementado
- projeto Android separado do GOAT PRO Studio;
- câmera traseira e frontal;
- preview com CameraX;
- 720p e 1080p;
- alvo de aproximadamente 30 FPS;
- transmissão MJPEG pela rede Wi-Fi local;
- servidor HTTP na porta 8080;
- URL principal `/video`;
- snapshot `/snapshot.jpg`;
- health check `/health`;
- iniciar/parar transmissão;
- copiar endereço IP;
- identidade visual GOAT PRO IP em dourado/preto;
- workflow de compilação Android;
- configuração AndroidX corrigida;
- APK debug compilado com sucesso.

### APK Build 1 — checkpoint
- workflow: `.github/workflows/android-debug.yml`
- run do GitHub Actions: `36816615217`
- resultado: **success**
- commit compilado: `7af822c43f408416aa767b07c6ef2b284fe468fa`
- artifact: `goat-pro-ip-debug`
- artifact id: `11142120618`
- tamanho do artifact: `4.133.618 bytes`
- SHA-256 do artifact: `b5ae06392cc4bb9626a8a4a9d77a1f21ae4653baa8648fd2324f53f645db3d72`
- expiração do artifact no GitHub Actions: `2026-12-30`

### Arquivos de referência
- Plano de ação: `docs/PLANO_DE_ACAO.md`
- Roteiro de teste: `docs/BUILD_1_TESTE.md`

### Próxima validação
1. instalar o APK em aparelho Android real;
2. testar 720p e 1080p;
3. testar câmera traseira e frontal;
4. abrir a transmissão no navegador do PC;
5. abrir a transmissão no GOAT PRO Studio;
6. medir estabilidade, atraso e FPS percebido;
7. corrigir os problemas encontrados e avançar para a próxima iteração da Build 1.

## Regra de organização
Este repositório é exclusivo do GOAT PRO IP. O projeto GOAT PRO Studio / Build 561 permanece separado no repositório anterior.


## Build 2 — em desenvolvimento
Branch: `build-2-alpha`

### Implementado até agora
- versão Android atualizada para `0.2.0-alpha`;
- preview 16:9 responsivo herdado da correção pós-Build 1;
- áudio opcional com captura do microfone;
- endpoint local de áudio PCM em `/audio.pcm`;
- controle de lanterna quando a câmera selecionada possui flash;
- status de conexão com contagem de clientes de vídeo;
- painel de vínculo com o GOAT PRO Studio;
- melhorias no encerramento de clientes e limpeza do servidor.

### Mantido para Build 3
- descoberta automática do GOAT PRO IP na mesma rede;
- conexão sem digitação manual de IP;
- integração nativa de descoberta no GOAT PRO Studio.

### Regra de compilação
A branch `build-2-alpha` não dispara o workflow automático. Gerar APK somente após autorização do usuário.


## Build 4 — teste integrado
Branch: `build-4-alpha`

### Objetivo
- manter vídeo 16:9 e áudio do celular já validados;
- manter descoberta automática por rede local;
- corrigir a integração da Build 3 para que o GOAT PRO Studio crie/reconecte automaticamente a fonte de vídeo `GOAT PRO IP · [IP]` na cena;
- evitar criação duplicada da mesma fonte;
- preservar a busca manual de IP/hotspot para outras câmeras.

### Integração Windows
Branch de teste correspondente: `goat-pro-build-564-ip-integration`.
A Build 561 estável permanece intacta.


## Build 5 — qualidade, latência e rotação
Branch: `build-5-alpha`

### Implementado
- herda a descoberta automática e o áudio de rede das Builds 3/4;
- integração Windows correspondente em `goat-pro-build-565-ip-integration`;
- correção do Studio para fonte automática preta, publicação imediata do primeiro quadro e reconexão;
- seleção de resolução com preferência real por 16:9 e alvo 720p/1080p;
- perfis de qualidade/largura de banda:
  - baixa latência: JPEG Q78 / 30 FPS;
  - equilibrado: JPEG Q86 / 30 FPS;
  - alta qualidade: JPEG Q92 / 24 FPS;
  - máxima qualidade: JPEG Q95 / 20 FPS;
- rotação do stream: automático, +90°, +180° e +270°;
- rotação feita em NV21 antes do JPEG, evitando dupla compressão;
- cópia otimizada do plano Y para reduzir custo em Full HD;
- fila de envio MJPEG reduzida para priorizar quadros recentes;
- exibição da resolução real do stream no aplicativo.

### Próxima validação
- comparar qualidade/latência em 1080p nos quatro perfis;
- testar orientação horizontal e vertical;
- confirmar vídeo automático no GOAT PRO Studio;
- medir estabilidade antes de considerar 4K.


### Correções após teste da Build 5
- rotação automática agora usa o sensor de orientação do aparelho em tempo real, mesmo com a interface do app mantida em portrait;
- CameraX recebe atualização dinâmica de targetRotation no preview e no ImageAnalysis;
- modos manuais 90°/180°/270° continuam disponíveis e ignoram a rotação automática;
- removida a lógica de ignorar a primeira troca dos seletores;
- perfis de qualidade ficaram mais distintos e efetivos:
  - baixa latência: Q62 / 30 FPS;
  - equilibrado: Q80 / 30 FPS;
  - alta qualidade: Q90 / 25 FPS;
  - máxima qualidade: Q96 / 20 FPS;
- ao trocar o perfil durante transmissão, o app confirma visualmente que o perfil foi aplicado.

APK ainda não recompilado após essas correções.


### Painel web de controles — correção adicional da Build 5
- a página raiz do GOAT PRO IP deixou de ser apenas uma tela de preview;
- o botão "Painel web / controles da câmera" do GOAT PRO Studio continua abrindo o mesmo host/porta, mas agora recebe um painel próprio do GOAT PRO IP;
- controles remotos adicionados: trocar câmera, lanterna, zoom, compensação de exposição e autofoco central;
- ISO e tempo de exposição manual usam Camera2 quando o aparelho anuncia suporte MANUAL_SENSOR;
- estado/capacidades da câmera disponíveis em /camera/state;
- comandos aplicados em /camera/control;
- não exige nova build do GOAT PRO Studio; somente o APK precisa ser recompilado quando autorizado.


## Build 6 — automático/manual + revisão Build 5
Branch: `build-6-alpha`

### Herdado e corrigido
- conexão automática de áudio/vídeo com GOAT PRO Studio;
- correção da fonte automática preta no receptor Windows já disponível no Studio 565;
- rotação automática em tempo real pelo sensor do aparelho;
- rotações manuais 90°/180°/270°;
- perfis efetivos de qualidade/latência;
- painel web próprio com zoom, EV, foco, câmera, lanterna e controles manuais Camera2 quando suportados.

### Novo na Build 6
- chave persistente `Conexão automática com o GOAT PRO Studio`;
- ligada por padrão;
- ligada: responde à descoberta UDP e o Studio pode adicionar/reconectar automaticamente;
- desligada: servidor de vídeo/áudio continua ativo em HTTP, mas o APK para de se anunciar ao Studio;
- desligar a chave não derruba uma conexão de vídeo já estabelecida;
- modo escolhido é salvo e mantido ao reabrir o aplicativo;
- status da transmissão informa quando está em modo manual.

### Processo
- versão Android: `0.6.0-alpha`;
- workflow preparado para `build-6-alpha`;
- APK só será compilado mediante autorização explícita.


## Build 7 — Full HD / FPS
Branch: `build-7-alpha`

### Motivo
Teste real da Build 6:
- 1080p: aproximadamente 6–8 FPS no Studio;
- 720p: aproximadamente 16–18 FPS;
- rotação automática validada pelo usuário.

### Gargalo encontrado
- a Build 6 rotacionava o buffer NV21 pixel a pixel em Kotlin antes do JPEG;
- em Full HD isso adicionava processamento proporcional ao número de pixels e limitava fortemente o throughput.

### Alterações
- ImageAnalysis agora usa rotação de saída nativa do CameraX;
- no modo AUTO, removida a rotação NV21 pixel a pixel do caminho crítico;
- rotações manuais continuam como override;
- mantido STRATEGY_KEEP_ONLY_LATEST para não acumular frames antigos;
- adicionada telemetria visível em tempo real:
  - FPS entregue pela câmera ao analyzer;
  - FPS efetivamente convertido em JPEG;
  - tempo médio de encode em ms;
  - taxa aproximada do MJPEG em Mbps;
- telemetria reinicia ao trocar resolução/perfil e ao iniciar/parar transmissão;
- versão 0.7.0-alpha;
- nenhuma alteração no GOAT PRO Studio nesta rodada;
- APK ainda não compilado: workflow aguardando autorização explícita.


### Referência IP Webcam Pro recebida do teste
Configuração observada:
- Camera2 Primary Camera;
- vídeo 1920×1080;
- Quality 50;
- orientação Landscape;
- FPS limit: No limit;
- Focus/Flash/Antibanding/Scene/White Balance em Default.

### Ajuste correspondente na Build 7
- perfil Baixa latência alterado para Q50 / alvo 20 FPS;
- Equilibrado Q65 / 20 FPS;
- Alta qualidade Q80 / 20 FPS;
- Máxima qualidade Q90 / 15 FPS;
- fast-path para o layout Camera2 YUV_420_888 com chroma VU intercalado, evitando centenas de milhares de leituras ByteBuffer por quadro Full HD;
- fallback genérico mantido para aparelhos/layouts incompatíveis;
- objetivo de validação: aproximar 1080p de 20 FPS antes de iniciar 2K/4K.


### Correção do painel web — exposição manual
Teste da Build 6:
- zoom digital validado;
- compensação EV validada;
- chave de exposição manual não liberava ISO/obturador.

Correção na Build 7:
- suporte manual não depende mais apenas da flag MANUAL_SENSOR;
- valida também AE OFF + faixas reais de ISO e tempo de exposição anunciadas pelo Camera2;
- modo manual envia AE OFF + SENSOR_SENSITIVITY + SENSOR_EXPOSURE_TIME + SENSOR_FRAME_DURATION;
- aplicação é confirmada pelo ListenableFuture do Camera2; falha desativa o estado manual;
- ao desligar manual, os overrides Camera2 são limpos e a câmera volta para exposição automática;
- painel recebe estado manualApplied para distinguir solicitação de aplicação real;
- nenhuma nova APK gerada após esta correção.


### Painel remoto completo — Build 7
O painel web aberto pelo GOAT PRO Studio passa a controlar também as configurações do stream, para uso com o celular montado em tripé sem toque local:
- resolução 720p / 1080p;
- perfil de qualidade/largura de banda;
- rotação Automático / 90° / 180° / 270°;
- conexão automática com o GOAT PRO Studio ligada/desligada;
- áudio do celular ligado/desligado;
- mantém troca de câmera, lanterna, zoom, EV, autofoco, ISO e obturador manual.
As alterações remotas sincronizam os seletores/estado do aplicativo no celular. Mudança somente no APK; o Studio 565 continua usando o mesmo botão de Painel Web.
APK ainda não recompilado após esta alteração.


## Build 8 — Full HD real + 20 FPS
Branch: `build-8-alpha`

### Diagnóstico da Build 7
Teste real mostrou:
- câmera entregando ~29,5–29,6 FPS;
- perfil Q50 / alvo 20 FPS entregando ~14,8 FPS JPEG;
- perfil Q90 / alvo 15 FPS entregando ~11,8 FPS JPEG;
- resolução marcada como 1080p, mas stream real exibido como 1080×608.

### Causas encontradas
- limitador antigo usava intervalo desde o último quadro aceito; com câmera ~30 FPS e alvo 20 FPS isso aceitava aproximadamente 1 a cada 2 quadros, resultando ~15 FPS;
- após rotação nativa, o buffer vertical 1080×1920 ainda era forçado por um recorte 16:9 horizontal, produzindo 1080×608;
- perfil Q50 era internamente limitado para no mínimo Q55.

### Correções
- agendamento por timeline fixa, permitindo cadência 2-de-3 a partir de 30 FPS para atingir ~20 FPS;
- recorte 16:9 agora preserva orientação:
  - horizontal: 1920×1080;
  - vertical: 1080×1920;
- qualidade JPEG agora respeita Q50 literalmente;
- telemetria real de câmera/JPEG/encode/Mbps mantida;
- versão 0.8.0-alpha;
- somente APK será necessário nesta rodada;
- APK ainda não compilado: aguarda autorização explícita.


### Painel manual — barras de ISO e obturador
Correção adicional da Build 8:
- removidos campos numéricos com setas para ISO e tempo de exposição;
- ISO agora usa slider com valor atual e faixa real reportada pela câmera;
- passo do ISO é adaptado à faixa disponível, preservando ajuste fino;
- obturador usa slider logarítmico/progressivo para cobrir de microssegundos a exposições longas sem ficar impraticável;
- painel exibe o obturador em formato amigável (ex.: 1/1000 s, 1/30 s, 100 ms) e também em microssegundos;
- limites mínimo/máximo da câmera aparecem abaixo de cada slider;
- nenhuma nova APK gerada após esta correção.


### Foco manual e pipeline de imagem — correções adicionais da Build 8
- foco manual adicionado ao painel web;
- chave Auto/Manual + slider de distância;
- Camera2 usa CONTROL_AF_MODE_OFF + LENS_FOCUS_DISTANCE no modo manual;
- slider respeita LENS_INFO_MINIMUM_FOCUS_DISTANCE informado pela lente;
- 0 dioptrias representa infinito/longe e o limite máximo representa foco mais próximo/macro;
- autofocus central desliga o modo manual antes de executar AF;
- foco manual e exposição manual passam a coexistir sem sobrescrever os respectivos overrides Camera2;
- CameraX atualizado de 1.4.1 para 1.5.1;
- ImageAnalysis passa a solicitar OUTPUT_IMAGE_FORMAT_NV21 diretamente, reduzindo conversão/reorganização de chroma no pipeline;
- objetivo continua sendo 1920×1080 real próximo de 20 FPS antes de avançar para 2K/4K;
- APK ainda não compilado após essas alterações.


### Painel avançado inspirado no teste comparativo
Após comparação visual com o painel do IP Webcam Pro, a Build 8 passa a incluir:
- qualidade JPEG independente de 1 a 100;
- limite de FPS independente, incluindo modo sem limite;
- foco automático/manual + distância de foco;
- balanço de branco;
- antibanding;
- modo de cena, incluindo noturno quando suportado;
- ISO e obturador por sliders;
- duração do frame manual;
- abertura e densidade de filtro exibidas somente quando a lente/câmera anunciar valores disponíveis;
- validação de modos Camera2 suportados antes de aplicar WB/antibanding/cena;
- controles de lente/sensor preservados ao alternar entre exposição/foco automático e manual.

A Build 8 ainda não foi compilada após essas alterações.


## Build 9 — H.264 baixa latência
Branch: `build-9-alpha`

### Resultado real da Build 8
- H.264/RTSP: qualidade visual excelente;
- H.264/RTSP: mais latência e mais travamento que o MJPEG automático;
- MJPEG já validado em Full HD vertical real perto de 20 FPS.

### Diagnóstico
- encoder H.264 da Build 8 usava VBR;
- B-frames/reordenação não eram explicitamente desativados;
- modo low-latency do MediaCodec não era solicitado;
- RTSP sobre TCP fazia flush a cada pacote RTP fragmentado, aumentando overhead e possibilidade de travamento;
- GOAT PRO Studio ainda recebe RTSP pelo FFmpeg/OpenCV, que pode acrescentar buffer próprio.

### Correções aplicadas no APK
- CBR quando suportado pelo encoder;
- prioridade realtime e operating rate alinhado ao FPS;
- B-frames desativados;
- perfil AVC Baseline quando suportado;
- low-latency do MediaCodec ativado quando o hardware expõe suporte;
- SPS/PPS repetidos em IDR para entrada mais rápida do decoder;
- socket RTSP TCP com buffer menor e TCP_NODELAY;
- flush RTSP reduzido de cada pacote RTP para uma vez por access unit/quadro.

### Processo
- nenhuma alteração no GOAT PRO Studio nesta rodada;
- versão 0.9.0-alpha;
- APK ainda não compilado; aguarda autorização explícita.


### Testes 2K / 4K adicionados à Build 9
- 2K/QHD: 2560×1440, exibido somente quando a câmera anuncia exatamente esse modo em YUV;
- 4K/UHD: 3840×2160, exibido somente quando a câmera anuncia exatamente esse modo em YUV;
- modos experimentais usam fallback NONE para impedir que 4K/2K sejam selecionados mas transmitam silenciosamente em resolução inferior;
- ao entrar em 2K: perfil experimental automático Q58 / 15 FPS;
- ao entrar em 4K: perfil experimental automático Q50 / 10 FPS;
- 1080p continua padrão;
- painel web também mostra/desabilita resoluções conforme suporte real informado pelo APK;
- H.264 não força Baseline acima de Full HD, permitindo que o codec escolha perfil/nível compatível com alta resolução;
- bitrate H.264 limitado de forma conservadora: teto 14 Mbps em 2K e 18 Mbps em 4K;
- nenhuma nova build do GOAT PRO Studio necessária para este teste manual.
