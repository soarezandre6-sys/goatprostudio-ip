# GOAT Cam — Status do projeto

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


### APK Build 9 gerado
- workflow run: 36893005284 (#20);
- conclusão: success;
- artifact: goat-pro-ip-build-9-debug;
- artifact id: 11177307366;
- artifact digest: sha256:50f905e87773ee024d7c6fdc0748d2302ec3fb23fbf860b2653af5a56a69ec2b;
- APK extraído: app-debug.apk;
- APK SHA-256: 617b3a326f4336048805d4a1a5d96d890c9e7d1a68e96ead94b4812878af8d72;
- tamanho APK: 4.806.815 bytes.


## Build 10 — múltiplas câmeras/lentes
Branch: `build-10-alpha`

### Nova seleção de câmera
- detecção das câmeras/lentes que o Android/Camera2 realmente expõe;
- traseira principal;
- ultra-wide/grande angular;
- tele/zoom;
- frontal;
- nomes incluem distância focal quando disponível;
- em aparelhos com câmera lógica multi-camera, a Build 10 tenta usar os IDs físicos expostos pelo Camera2;
- quando o fabricante não expõe uma lente a apps de terceiros, ela não aparece como opção falsa;
- seletor de câmera/lente no aplicativo;
- seletor de câmera/lente no painel web aberto pelo GOAT PRO Studio;
- botão rápido agora percorre todas as câmeras/lentes disponíveis;
- resoluções 720p/1080p/2K/4K passam a ser recalculadas para a lente atualmente selecionada;
- versão 0.10.0-alpha;
- workflow preparado, mas APK ainda não gerado.


### Revisão visual da Build 10
- interface Android redesenhada sem alterar o pipeline de câmera/transmissão;
- cartões com cantos arredondados, bordas sutis e elevação;
- preview em moldura arredondada elevada;
- botões secundários arredondados e botão principal com acabamento em gradiente;
- campos/spinners com fundo escuro, borda e tipografia própria;
- cabeçalho com selo circular dourado e bode sem moldura quadrada preta;
- launcher convertido para adaptive icon do Android, permitindo recorte correto circular/arredondado pelo aparelho;
- removida a borda/quadrado preto que contornava o bode no ícone antigo;
- ícone técnico do launcher e marca usada dentro do app agora são separados;
- Build 10 segue com múltiplas lentes + 2K/4K experimentais + H.264 baixa latência.


### APK Build 10 gerado após revisão visual
- workflow run: 36898883960 (#21);
- conclusão: success;
- artifact: goat-pro-ip-build-10-debug;
- artifact id: 11180168888;
- artifact digest: sha256:1db5954420825d1e576a2c99930b6fda4678a3183f59f1184f9bbbcdef54ddfe;
- APK final: GOAT-PRO-IP-Build-10.apk;
- APK SHA-256: e9437b5cd85980d0a3a2800e47b05d3d8f627225f272163845ca5fe30d09c905;
- tamanho APK: 4.833.740 bytes;
- validação ZIP/APK: sem erros de integridade;
- recursos confirmados dentro do APK: adaptive launcher icon, marca sem moldura preta, novo layout, cartões, spinner estilizado.


## Build 11 — Android 16 + integração comercial
Branch: `build-11-alpha`

- compileSdk 36;
- targetSdk 36 (Android 16);
- versionCode 11 / versionName 0.11.0-alpha;
- botão interno "Conheça o GOAT PRO Studio" abrindo https://goatprostudio.com.br;
- identidade visual da Build 10 preservada;
- múltiplas lentes, 2K/4K experimental e H.264 baixa latência preservados;
- workflow separado da Build 10.


### APK Build 11 gerado
- workflow run: 36902667164 (#22);
- conclusão: success;
- artifact: goat-pro-ip-build-11-debug;
- artifact id: 11182327082;
- artifact digest: sha256:4702317428a447b3ee02ddbf6d737d70eb74a519f4f87e902a36a308365e616b;
- APK SHA-256: a247d5799e0fa2750565f1d12b45e4a8a13bdb6638290ec2b969fc10a43a0ec8;
- tamanho APK: 4.833.940 bytes;
- integridade ZIP/APK: OK;
- targetSdk/compileSdk: 36;
- link interno oficial: https://goatprostudio.com.br.


## Build 12 — renomeação para GOAT Cam
Branch: `build-12-goat-cam`

- nome comercial alterado de **GOAT PRO IP** para **GOAT Cam**;
- launcher/app, cabeçalho, rodapé, painel web e textos visíveis atualizados;
- Android 16 / API 36 preservado;
- link para `goatprostudio.com.br` preservado;
- versão: 0.12.0-alpha / versionCode 12;
- protocolo de descoberta `GOAT_PRO_IP_V1` e `GOAT_PRO_IP_DISCOVER_V1` preservado para compatibilidade com o GOAT PRO Studio;
- identificadores internos e histórico das builds anteriores não foram apagados;
- APK Build 12 ainda não gerado.


## GOAT Cam 1.0.0 — Release comercial
Branch: `release-1.0.0-goat-cam`

- primeira versão release do GOAT Cam;
- versionCode 100 / versionName 1.0.0;
- Android 16 / compileSdk 36 / targetSdk 36;
- nome comercial GOAT Cam consolidado;
- link oficial para https://goatprostudio.com.br preservado;
- protocolo técnico GOAT_PRO_IP_V1 preservado para compatibilidade com o GOAT PRO Studio;
- pacote Android `com.goatpro.ip` preservado para continuidade técnica;
- múltiplas lentes, Full HD, 2K/4K experimentais, MJPEG e H.264/RTSP preservados;
- saída release preparada para APK e Android App Bundle (AAB);
- assinatura de produção não será armazenada no GitHub.


### GOAT Cam 1.0.0 — release assinada final
- workflow run: 36909497897 (#2);
- conclusão: success;
- head commit: 6727c9f366745b6248b6698d2a7b5373264ae18c;
- artifact público: goat-cam-release-1.0.0 (id 11185284126);
- artifact privado temporário da chave: goat-cam-release-1.0.0-signing-backup (id 11185948833, retenção de 1 dia);
- APK: GOAT-Cam-1.0.0-Release.apk;
- APK SHA-256: 9f7ef4dae413933aaf672e1931f59907a6ae967156c39569a49a490acca2a201;
- assinatura APK verificada: v2=true, v3=true;
- AAB Play: GOAT-Cam-1.0.0-Play.aab;
- AAB SHA-256: 3988f2ee069b12f40bf44f3b744984a5ee7f4e31f6be0c8a26f938d40a0ada8c;
- assinatura AAB: jar verified;
- certificado SHA-256: F4:14:95:E0:CC:F3:55:A9:8C:DF:8D:28:B3:3A:81:1A:1F:EA:B4:12:EB:D2:F9:7F:66:50:92:69:97:FF:A5:0C;
- chave privada e senha NÃO foram gravadas no repositório;
- package ID preservado: com.goatpro.ip;
- targetSdk/compileSdk: 36;
- versionCode 100 / versionName 1.0.0.


## Build 13 — correção crítica 2K/4K + tele
Branch: `build-13-goat-cam`

### Problemas encontrados no teste da 1.0.0
- Galaxy S21 não exibia 4K no seletor;
- 2K podia falhar/travar ao abrir a câmera;
- câmera tele não aparecia entre as lentes disponíveis.

### Causas corrigidas
- o seletor de lentes descartava câmeras traseiras lógicas quando encontrava câmeras físicas em uma câmera lógica multi-camera;
- a tele podia existir como câmera lógica separada e, por isso, era ignorada;
- a detecção de resolução consultava somente `getOutputSizes(YUV_420_888)` e não considerava `getHighResolutionOutputSizes(YUV_420_888)`;
- o CameraX permanecia no modo padrão `PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION`, que pode excluir resoluções de alta resolução;
- Preview e ImageAnalysis recebiam simultaneamente a mesma resolução 2K/4K, combinação que alguns HALs rejeitam ou executam de forma instável.

### Alterações da Build 13
- união de câmeras traseiras lógicas + câmeras físicas expostas pelo Camera2;
- remoção de duplicatas por métrica de campo de visão, preferindo câmera lógica diretamente acessível quando representa a mesma lente;
- classificação de tele mais tolerante para aparelhos em que a lente tele tem campo de visão próximo da principal;
- consulta combinada das resoluções YUV normais e de alta resolução da câmera física e da câmera lógica associada;
- CameraX passa a usar `PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE` em 2K/4K;
- transmissão/análise continua pedindo a resolução 2K/4K selecionada;
- preview local fica limitado a 720p nos modos 2K/4K para reduzir a carga e evitar duas superfícies de alta resolução simultâneas;
- 720p/1080p mantêm o comportamento normal de prioridade de FPS.

### Estado
- alterações salvas no GitHub;
- nenhuma APK foi gerada nesta etapa;
- próxima validação deve ser feita no Galaxy S21, conferindo: principal, ultra-wide, tele, frontal, 2K e 4K.

### Resoluções dinâmicas — atualização adicional da Build 13
- removida a lista fixa de 720p / 1080p / 2K / 4K da interface;
- cada câmera/lente passa a consultar as resoluções YUV 16:9 realmente anunciadas pelo Camera2;
- resoluções normais e de alta resolução são combinadas e ordenadas automaticamente;
- o seletor mostra o tamanho real em pixels, por exemplo `Full HD · 1920×1080`, `2K QHD · 2560×1440` e `4K UHD · 3840×2160` quando existirem;
- a lista é recalculada sempre que o usuário troca entre principal, ultra-wide, tele e frontal;
- o painel web recebe exatamente a mesma lista dinâmica do aplicativo;
- aliases antigos HD/FHD/QHD/UHD continuam aceitos nos comandos remotos para compatibilidade;
- 8K passa a ser oferecido somente quando a câmera anunciar saída compatível para `MediaCodec` e o encoder HEVC do aparelho aceitar a resolução;
- formatos pequenos e formatos fora de 16:9 são ocultados porque não correspondem ao pipeline de vídeo atual do GOAT Cam.

### 8K experimental adaptável — atualização adicional da Build 13
- checkpoint preservado antes desta mudança: `checkpoint-build13-pre-8k-2026-10-02`;
- 720p/1080p/2K/4K continuam usando o pipeline existente CameraX + ImageAnalysis + MJPEG/H.264;
- resoluções acima de 4K usam um caminho separado para não sobrecarregar NV21/JPEG;
- o app consulta `StreamConfigurationMap.getOutputSizes(MediaCodec::class.java)` para descobrir tamanhos de vídeo privados compatíveis com encoder;
- uma opção como `8K UHD · HEVC experimental · 7680×4320` só aparece se a câmera realmente anunciar esse tamanho e o encoder HEVC/H.265 do aparelho também aceitar a resolução;
- modo 8K usa Camera2 direto para a surface de entrada do `MediaCodec`, evitando conversão de cada quadro 8K para NV21/JPEG pela CPU;
- encoder 8K padrão configurado para HEVC/H.265 por hardware, 24 FPS e bitrate adaptado (aprox. 45 Mbps em 7680×4320/24);
- RTSP H.265 experimental adicionado na porta 8554, caminho `/h265`;
- descoberta automática mantém os campos antigos para compatibilidade e passa a anunciar também `h265=/h265` e `rtspCodec=H264|H265`;
- ao iniciar 8K, CameraX libera a câmera e o Camera2 assume a captura direta; ao parar, o preview CameraX volta;
- o preview local antes da transmissão continua leve; durante a transmissão 8K a prioridade é a surface direta do encoder;
- troca entre o pipeline normal e o pipeline HEVC interrompe a transmissão ativa antes de reconfigurar a câmera, evitando disputa de surface/câmera;
- nenhuma APK foi gerada automaticamente nesta etapa.


### APK de teste — Build 13
- workflow: Android Debug APK;
- run: 37044411877 (#23);
- conclusão: success;
- branch: `build-13-goat-cam`;
- head commit da execução: `2065b07714bbe3b45a878af85193f2c7ddf52650`;
- artifact: `goat-cam-build-13-debug`;
- artifact id: 11244126968;
- artifact digest: `sha256:07037369da805d06a7ee432053ca36dede429de27e022a4d8f09d083107a036a`;
- APK gerado: `app-debug.apk`;
- tamanho do APK: 4.850.280 bytes;
- APK SHA-256: `ced82fbe9d86fd6af62f3f8b9eaa428a790ebaafcecdc7676438c83faf149196`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: múltiplas lentes, resoluções dinâmicas, 2K/4K corrigidos e 8K HEVC experimental adaptável.


## Build 15 — correção após regressões da Build 14
Branch: `build-15-goat-cam`

- criada a partir do checkpoint da Build 13 que já havia sido validado com 4K funcionando;
- o pipeline H.264 direto introduzido na Build 14 foi removido desta linha; 4K volta ao caminho CameraX/ImageAnalysis/H.264 que funcionou no Galaxy S21;
- seletor de resolução deixa de listar tamanhos intermediários de sensor como substitutos de 8K; 8K significa somente `7680×4320`;
- 8K experimental usa 10 FPS como tentativa inicial;
- no Galaxy S21, o app não esconde mais a opção 8K apenas porque a tabela pública do MediaCodec/HAL não anuncia 7680×4320;
- o encoder HEVC tenta configurar exatamente `7680×4320` e deixa o hardware aceitar ou recusar a sessão real;
- enumeração das câmeras traseiras não remove mais módulos diferentes apenas porque suas distâncias focais são parecidas;
- câmeras físicas são deduplicadas por identidade/ID, preservando principal, ultra-wide e tele quando o Camera2 as expõe;
- quando a tele não aparece como ID físico, mas a câmera lógica suporta zoom de pelo menos 3x, o seletor adiciona `Tele • 3x` e solicita zoom ao HAL para permitir que a Samsung faça a troca interna de sensor;
- para a família Galaxy S21 `SM-G99x`, a busca 8K também considera o maior sensor traseiro como fallback experimental, porque o app nativo oferece 8K mesmo quando a API pública pode subdeclarar os modos;
- nenhuma APK foi gerada nesta etapa.


### APK de teste — Build 15
- workflow: Android Debug APK;
- run: 37051232462 (#25);
- conclusão: success;
- branch: `build-15-goat-cam`;
- head commit da execução: `813ba0c21d4b709224bdc3331f490eadeb9e80bb`;
- artifact: `goat-cam-build-15-debug`;
- artifact id: 11245924836;
- artifact digest: `sha256:0af4589a2e2d0703a4b8eeff266d29dac42907ee68154087d5a6395d02b7966d`;
- APK: `GOAT-Cam-Build-15-Teste.apk`;
- tamanho do APK: 4.850.280 bytes;
- APK SHA-256: `ec3d0c08809874e3fe995350b585f2270283c0635cbb3efd81a3107a3fa2d5f0`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: validar 4K restaurado, Tele/Tele 3x e tentativa 8K 7680×4320 a 10 FPS.


## Build 16 — correção da sessão 8K recusada
Branch: `build-16-goat-cam`

- criada a partir da Build 15 testada no Galaxy S21;
- diagnóstico do teste: a opção 8K 7680×4320 aparece, porém a sessão Camera2/HEVC retorna `onConfigureFailed` antes de enviar vídeo ao PC;
- a busca 8K agora prioriza o ID lógico traseiro que o Android/Samsung associa ao perfil oficial `CamcorderProfile.QUALITY_8KUHD`;
- quando existe perfil 8K oficial, não força diretamente o ID físico do maior sensor;
- sessão HEVC usa `OutputConfiguration` + `SessionConfiguration` também para câmera lógica;
- em Android 13+ a saída marca `SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD` quando o dispositivo anuncia suporte;
- request usa explicitamente `CONTROL_CAPTURE_INTENT_VIDEO_RECORD`;
- ao trocar de um stream funcionando para 8K, o app guarda a resolução anterior;
- se a câmera recusar 8K, o app restaura automaticamente a resolução anterior e reinicia a transmissão, evitando deixar o PC congelado sem stream;
- nenhuma APK da Build 16 foi gerada nesta etapa.


### APK de teste — Build 16
- workflow: Android Debug APK;
- primeira execução #26 encontrou erro de compilação nos tipos de stream use case e foi corrigida;
- execução final: 37054131574 (#27);
- conclusão: success;
- branch: `build-16-goat-cam`;
- head commit da execução: `cf0cfc5179a100a6681450624b5fa856976a1e30`;
- artifact: `goat-cam-build-16-debug`;
- artifact id: 11248055798;
- artifact digest: `sha256:1213ec866ac668b554c86bd6f56d56b1f71149d6739642b3705bf84fd999c507`;
- APK: `GOAT-Cam-Build-16-Teste.apk`;
- tamanho do APK: 4.850.280 bytes;
- APK SHA-256: `95da8b77c1550b86b88fe656d037639e5e571dac737930b10e3b0db01e49bc2a`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: validar sessão 8K pelo perfil lógico 8KUHD/VIDEO_RECORD e fallback automático para a resolução anterior se o 8K for recusado.


## Build 17 — 8K PRIVATE / USECASE_RECORD
Branch: `build-17-goat-cam`

- criada a partir da Build 16;
- 4K e tele permanecem no caminho já validado;
- o 8K procura primeiro o `RecommendedStreamConfigurationMap.USECASE_RECORD` da câmera lógica traseira;
- a busca valida `ImageFormat.PRIVATE` e também saídas de `MediaCodec` no mapa recomendado de gravação;
- prioridade para 8K exato `7680×4320`;
- quando disponível, usa `CamcorderProfile.QUALITY_8KUHD` / `EncoderProfiles` para obter FPS e bitrate reais do perfil 8K do aparelho;
- preferência por perfil HEVC quando a Samsung o anunciar;
- o MediaCodec HEVC passa a aceitar FPS do perfil oficial até 30 FPS, em vez de limitar o teste a 10–15 FPS;
- mantém `TEMPLATE_RECORD`, `CONTROL_CAPTURE_INTENT_VIDEO_RECORD` e stream use case VIDEO_RECORD;
- fallback automático para a resolução anterior continua ativo se a sessão 8K for recusada;
- nenhuma APK da Build 17 foi gerada nesta etapa.


### APK de teste — Build 17
- workflow: Android Debug APK;
- run: 37055986009 (#28);
- conclusão: success;
- branch: `build-17-goat-cam`;
- head commit da execução: `e723d7265a4c1c5e50e20ed95b564b661e194624`;
- artifact: `goat-cam-build-17-debug`;
- artifact id: 11247798437;
- artifact digest: `sha256:895c213548f36d3bd370f1966df6d245c868db87d040e8b36d915faa93de4aa8`;
- APK: `GOAT-Cam-Build-17-Teste.apk`;
- tamanho do APK: 4.866.664 bytes;
- APK SHA-256: `750b6c5e188e9245f5a5906e9ab94287a1c5da5b1ee4488d6a7edc80670876a1`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: validar 8K via PRIVATE + USECASE_RECORD + perfil QUALITY_8KUHD, preservando 4K e tele.


## Build 18 — 8K via MediaRecorder
Branch: `build-18-goat-cam`

- criada a partir da Build 17;
- 4K e tele permanecem sem alteração;
- o modo 8K deixa de usar como rota principal a sessão Camera2 -> MediaCodec Surface que foi recusada nas Builds 15–17;
- nova rota experimental: Camera2 lógico -> Surface do MediaRecorder -> HEVC;
- usa `MediaRecorder.VideoSource.SURFACE`, `MediaRecorder.VideoEncoder.HEVC` e `MediaRecorder.OutputFormat.MPEG_2_TS`;
- tamanho, FPS e bitrate vêm do perfil 8K selecionado na Build 17 quando disponíveis;
- mantém `TEMPLATE_RECORD`, `CONTROL_CAPTURE_INTENT_VIDEO_RECORD` e stream use case VIDEO_RECORD;
- MediaRecorder grava o MPEG-TS em um pipe de memória, sem criar vídeo permanente no armazenamento;
- leitor TS/PES extrai o elementary stream HEVC Annex-B e envia os access units ao servidor RTSP H.265 existente;
- detecta NALs HEVC IRAP para marcar keyframes;
- o fallback automático para a resolução anterior continua ativo se o MediaRecorder/sessão 8K for recusado;
- nenhuma APK da Build 18 foi gerada nesta etapa.


### APK de teste — Build 18
- workflow: Android Debug APK;
- run: 37057566504 (#29);
- conclusão: success;
- branch: `build-18-goat-cam`;
- head commit da execução: `71f1ccd820ec9ad308685d2e135c4d0500881437`;
- artifact: `goat-cam-build-18-debug`;
- artifact id: 11249181105;
- artifact digest: `sha256:dfce2ecdd0914aa5f5b8fe6b40e6ef4e8e489e330f22394338e38bd734a695a9`;
- APK: `GOAT-Cam-Build-18-Teste.apk`;
- tamanho do APK: 4.866.664 bytes;
- APK SHA-256: `7d96b38fff78ba17a31bdf0f266e14c915d040ab7e9f417a19e16909cfdbcb37`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: validar 8K via MediaRecorder + HEVC + Surface de gravação, preservando 4K e tele.


## Build 19 — 8K via CameraX VideoCapture/Recorder
Branch: `build-19-goat-cam`

- criada a partir da Build 18;
- 4K e tele preservados;
- adicionada dependência `androidx.camera:camera-video:1.5.1`;
- modo 8K usa `Recorder` com `Quality.HIGHEST` e `VIDEO_CAPABILITIES_SOURCE_CAMCORDER_PROFILE`;
- o próprio CameraX negocia a sessão de vídeo com a HAL;
- após o bind, `VideoCapture.getResolutionInfo()` é validado;
- somente `7680×4320` é aceito como sucesso de 8K; qualquer fallback para 4K/FHD é reportado como recusa;
- se 7680×4320 for negociado, o Recorder inicia uma gravação de teste no cache para confirmar que a sessão realmente começou;
- arquivo de teste é temporário e removido ao parar/finalizar;
- fallback automático para 4K continua ativo em caso de recusa;
- esta Build 19 valida aceitação real do 8K pelo CameraX; o caminho RTSP 8K ainda não é usado neste teste.

### APK de teste — Build 19
- workflow: Android Debug APK;
- run: 37059094320 (#30);
- conclusão: success;
- branch: `build-19-goat-cam`;
- head commit da execução: `a68bf7a6c0782f35b945da7cebb49d2285c7c5fd`;
- artifact: `goat-cam-build-19-debug`;
- artifact id: 11249217725;
- artifact digest: `sha256:3ee18edf20e44c59a8d89586e2d1a05f4d617bfaafb49755ffece65157be3377`;
- APK: `GOAT-Cam-Build-19-Teste.apk`;
- tamanho do APK: 4.883.048 bytes;
- APK SHA-256: `9097fda6d454f45df8a5d0a162e964fbfd4bcf696295ef4d8d54c8e9a031e70a`;
- compilação `:app:assembleDebug`: sucesso.


## Build 20 — diagnóstico Samsung/vendor para 8K
Branch: `build-20-goat-cam`

- criada a partir da Build 19;
- 4K e tele permanecem sem alteração;
- novo módulo `SamsungVendorDiagnostics`;
- novo botão `Diagnóstico 8K Samsung` na tela principal;
- varre câmeras lógicas e físicas expostas pelo Camera2;
- lê nomes das `CameraCharacteristics`, `CaptureRequest`, `CaptureResult`, `SessionKeys` e `PhysicalCameraRequestKeys` visíveis a apps de terceiros;
- destaca nomes ligados a Samsung/vendor/8K/UHD/4320/video/record/remosaic/high-resolution/sensor/binning/HEVC/HDR;
- inclui hardware level, capabilities, pixel array, active array, distância focal e IDs físicos;
- tenta ler valores das características candidatas quando o Android permite;
- o relatório completo é copiado automaticamente para a área de transferência;
- também salva uma cópia interna em `goat-camera-vendor-report.txt`;
- o resumo aparece na própria interface;
- não usa root, APK auxiliar nem API escondida: diagnostica somente o que o firmware expõe publicamente ao GOAT Cam;
- nenhuma APK da Build 20 foi gerada nesta etapa.


### APK de teste — Build 20
- primeira tentativa de compilação: run 37060926590 (#31), falhou por `IntArray.orEmpty()` no diagnóstico;
- correção aplicada em `SamsungVendorDiagnostics.kt`;
- compilação válida: run 37061169723 (#32);
- conclusão: success;
- branch: `build-20-goat-cam`;
- head commit da execução: `e094d36058273545f24e76f8f8cd3800eb59fa89`;
- artifact: `goat-cam-build-20-debug`;
- artifact id: 11250896077;
- artifact digest: `sha256:12b81fc1279aba89b68885f2155e4a73160bf2853a2413c3acc1aea174915bad`;
- APK: `GOAT-Cam-Build-20-Teste.apk`;
- tamanho do APK: 4.883.248 bytes;
- APK SHA-256: `0ce04e895a7ff767ace23ccd0e8c2f39b86441b7873038c45e783fbfb367cf43`;
- compilação `:app:assembleDebug`: sucesso;
- objetivo do teste: executar `Diagnóstico 8K Samsung`, copiar o relatório completo e analisar vendor/session keys expostas pelo aparelho.


## Build 21 — teste local oficial 8K
Branch: `build-21-goat-cam`

- criada a partir da Build 20;
- novo botão `Teste local 8K oficial (5 s)`;
- usa o perfil clássico `CamcorderProfile.QUALITY_8KUHD`;
- exige que o perfil reportado seja exatamente `7680×4320`;
- aplica o perfil completo com `MediaRecorder.setProfile(profile)`;
- grava em arquivo local normal no próprio celular, sem RTSP, sem pipe e sem CameraX;
- usa `CameraDevice.TEMPLATE_RECORD` e stream use case de vídeo quando disponível;
- grava por 5 segundos e finaliza automaticamente;
- após finalizar, lê o arquivo com `MediaMetadataRetriever` para confirmar a resolução real gravada;
- só mostra `8K LOCAL ACEITO • 7680×4320` se o arquivo final também for 8K real;
- o arquivo de sucesso fica em armazenamento específico do app, pasta Movies/GOAT-Cam;
- 4K, tele e diagnóstico vendor da Build 20 foram preservados;
- APK ainda não gerado nesta etapa.


### APK de teste — Build 21
- workflow: Android Debug APK;
- run: 37063451222 (#33);
- conclusão: success;
- branch: `build-21-goat-cam`;
- head commit da execução: `ad336e73bd424b6eeeffaac87a6582f3d5e982b6`;
- artifact: `goat-cam-build-21-debug`;
- artifact id: 11252120052;
- artifact digest: `sha256:0ad53e35ec58ea7b25bca7d9352a05eb929c9bc098988ab1b657a8ed4c2a74ab`;
- APK: `GOAT-Cam-Build-21-Teste.apk`;
- tamanho do APK: 4.899.812 bytes;
- APK SHA-256: `61b5c8f761202898a33a3d761c2bed8b35e9cabebf53acbb332a8ac4acf2247b`;
- compilação `:app:assembleDebug`: sucesso;
- teste: tocar em `Teste local 8K oficial (5 s)`; o app usa `CamcorderProfile.QUALITY_8KUHD` + `MediaRecorder.setProfile()` e confirma a resolução final do arquivo.


## Build 22 — remover 8K e recuperar 4K frontal
Branch: `build-22-goat-cam`

- 8K abandonado por enquanto após os testes públicos serem recusados no Galaxy S21;
- removidos da interface o diagnóstico Samsung/vendor e o teste local 8K;
- removida a opção 7680×4320 do seletor de resolução;
- removidos do fluxo ativo CameraX 8K, HEVC 8K, MediaRecorder 8K e RTSP H.265 experimental;
- removida a dependência `camera-video`, usada somente pelo teste CameraX 8K;
- 4K traseiro e tele permanecem no caminho existente;
- novo caminho exclusivo para 4K frontal: Camera2 `TEMPLATE_RECORD` -> Surface do MediaCodec AVC/H.264 -> RTSP H.264;
- quando o perfil público `QUALITY_2160P` frontal existe, FPS e bitrate são derivados dele;
- fallback específico para Galaxy S21 permite tentar 3840×2160 mesmo quando o Camera2 omite 4K da lista YUV;
- se a sessão frontal 4K for recusada pelo firmware, o app volta automaticamente para 1080p;
- nenhuma APK foi gerada nesta etapa.
