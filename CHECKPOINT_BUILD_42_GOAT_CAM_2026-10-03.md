# GOAT CAM — CHECKPOINT BUILD 42

Data: 2026-10-03
Repositório: `soarezandre6-sys/goatprostudio-ip`
Branch atual: `build-42-goat-cam`
Base segura atual: Build 39
Commit-base da branch 42: `fe43b5c56d351e0544eca26d24db82cb468cb12f`

## Estado confirmado dos testes

### Build 39
- Abre e transmite.
- As duas câmeras frontais conseguem entrar no modo 4K por fallback GPU.
- O fluxo H.264 continua gerando frames.
- Problema principal: imagem 4K muito pesada/lenta no GOAT PRO Studio.
- A imagem fica lisa/plastificada, com perda de textura fina do rosto e pouca definição aparente.
- Esta é a última base funcional conhecida e deve ser preservada.

### Build 40
- Objetivo era recuperar o Live View local e melhorar qualidade/bitrate.
- No teste real no Samsung, o app instalou mas não abriu corretamente.
- Build 40 descartada como base.

### Build 41
- Foi criada a partir da Build 39 para tentar usar 4K direto por RTSP/H.264, reduzir processamento e retirar o MJPEG concorrente.
- Compilou com sucesso, porém o teste real piorou.
- Não apareceu imagem no Live View do GOAT CAM.
- A imagem no GOAT PRO Studio ficou escura/preta e congelou.
- Build 41 descartada.

## Diagnóstico atual

A Build 41 retirou o MJPEG que antes mascarava o problema e expôs uma falha no lado do GOAT PRO Studio:
- o Studio 572 podia aceitar a abertura do endereço RTSP como se a conexão estivesse válida;
- porém não confirmava que um frame real havia sido decodificado;
- resultado possível: RTSP aberto, mas sem quadro válido, deixando a imagem preta/congelada.

Portanto, o próximo teste não deve mexer novamente na câmera antes de confirmar o receptor do Studio.

## Estado da Build 42

A branch `build-42-goat-cam` foi criada diretamente da Build 39 funcional.

Regra para a Build 42:
- não carregar as alterações arriscadas das Builds 40 e 41;
- não desativar MJPEG por enquanto;
- não forçar 28 Mbps;
- não alterar preview local/GPU neste momento;
- não mudar vários parâmetros da Camera2 ao mesmo tempo;
- fazer uma alteração por vez, somente depois de confirmar o resultado do Studio 573.

No momento deste checkpoint, a Build 42 permanece sem alteração de código em relação à base Build 39, além deste arquivo de checkpoint.

## Dependência com GOAT PRO Studio

Foi criada separadamente no repositório do GOAT PRO Studio a versão de teste `573`, derivada da 572, para corrigir o recebimento RTSP.

Comportamento esperado do Studio 573:
- tenta RTSP/H.264 para GOAT CAM 4K;
- só considera o RTSP conectado após receber e decodificar pelo menos 1 frame real;
- espera até 5 segundos pelo primeiro frame;
- se não houver frame válido, abandona o RTSP e volta ao HTTP/MJPEG automaticamente;
- objetivo: impedir tela preta/congelada causada por um RTSP aberto sem vídeo útil.

## Próximo teste recomendado

Usar exatamente:
- GOAT CAM Build 39
- GOAT PRO Studio Build 573 TESTE

Objetivo do teste:
1. Confirmar se o RTSP/H.264 realmente entrega imagem contínua ao Studio.
2. Verificar se a imagem 4K fica mais fluida que no MJPEG.
3. Se o RTSP falhar, confirmar se o Studio retorna sozinho ao MJPEG sem ficar preto.
4. Só depois desse resultado decidir a primeira alteração real da Build 42.

## Regra de continuidade

- Build 39 = referência funcional atual.
- Build 40 = descartada.
- Build 41 = descartada.
- Build 42 = próxima linha válida de desenvolvimento, baseada na 39.
- Não gerar nova APK da 42 até haver motivo técnico claro e uma mudança isolada para testar.
