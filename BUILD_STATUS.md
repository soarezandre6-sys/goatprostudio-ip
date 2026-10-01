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
