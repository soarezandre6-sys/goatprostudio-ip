# GOAT Cam — plano oficial Free / Pro

Status: plano mestre dos recursos comerciais do GOAT Cam. Este arquivo deve ser consultado antes de continuar o desenvolvimento Pro. O bloqueio comercial/licenciamento ainda não está ativo nesta fase de testes.

## Legenda
- ✅ Implementado
- 🧪 Implementado, mas ainda precisa teste em aparelho
- 🟡 Parcial / base pronta
- ⏳ Ainda falta implementar

## Free planejado
- ⏳ limitar transmissão a Full HD 1080p;
- ⏳ liberar câmera traseira principal;
- ⏳ liberar uma câmera frontal;
- ✅ conexão com GOAT PRO Studio;
- ✅ controles básicos;
- ✅ marca d'água `GOAT CAM FREE` no vídeo normal;
- ⏳ tornar a remoção da marca d'água dependente da licença Pro.

## Pro — imagem, resolução e câmeras
- ✅ 2K / resoluções avançadas quando o aparelho expõe essas resoluções;
- ✅ 4K traseiro quando suportado;
- 🧪 4K frontal 3840×2160 por caminho direto Camera2 + H.264 quando suportado;
- ✅ seletor de lentes/câmeras expostas pelo aparelho;
- ✅ câmera principal;
- ✅ tele quando exposta/compatível;
- ✅ ultra-wide e demais câmeras quando expostas pelo Android;
- ✅ câmeras frontais disponíveis;
- ⏳ bloqueio comercial das lentes extras para a versão Pro.

## Pro — controles profissionais
- ✅ ISO manual;
- ✅ shutter / tempo de exposição manual;
- ✅ foco automático;
- ✅ foco manual / distância de foco;
- ✅ balanço de branco;
- ✅ compensação de exposição EV;
- ✅ zoom;
- ✅ controle de FPS;
- ✅ qualidade de transmissão / JPEG;
- ✅ bitrate H.264 manual;
- ✅ modos de cena quando suportados pelo hardware;
- ✅ abertura quando suportada;
- ✅ filtro de densidade quando suportado;
- ✅ antibanding 50/60 Hz/Auto quando suportado.

## Pro — integração com GOAT PRO Studio
- ✅ controle remoto de câmera/lente;
- ✅ controle remoto de resolução;
- ✅ controle remoto de ISO, shutter, foco, WB, EV, FPS e zoom;
- ✅ controle remoto de bitrate;
- ✅ descoberta/conexão automática básica com o Studio;
- 🟡 Smart Link local: lembra câmera, resolução, qualidade, FPS, bitrate e rotação;
- 🟡 reconexão automática: existe a base de descoberta, mas ainda falta recuperação avançada de queda e restauração completa de sessão;
- ⏳ perfil por aparelho reconhecido pelo Studio, com identidade persistente de cada celular.

## Pro — perfis e presets
- ✅ presets Pro P1, P2 e P3;
- ✅ salvar e carregar P1/P2/P3;
- 🟡 perfil personalizado: a base existe nos três slots, mas ainda falta nomear/criar quantidade flexível de perfis;
- ⏳ presets prontos de fábrica, por exemplo Astro, Segurança, Streaming, Baixa Luz e Baixa Latência;
- ⏳ presets por aparelho no ecossistema GOAT.

## Pro — overlays e identidade
- ✅ marca d'água fixa `GOAT CAM FREE` no pipeline normal;
- ⏳ remover automaticamente a marca d'água quando a licença Pro estiver ativa;
- ⏳ overlay de data e hora;
- ⏳ texto personalizado no vídeo;
- ⏳ logo personalizada do usuário;
- ⏳ indicador de bateria no vídeo;
- ⏳ indicador de FPS no vídeo;
- ⏳ informações de câmera/resolução/bitrate opcionais no overlay.

## Pro — transmissão e codecs
- ✅ MJPEG;
- ✅ H.264 RTSP;
- ⏳ H.265 / HEVC Pro quando suportado, limitado a resoluções públicas funcionais; o código experimental de 8K/HEVC foi removido e não deve ser confundido com este recurso futuro;
- ⏳ perfis de transmissão Baixa Latência / Equilibrado / Máxima Qualidade com bitrate e FPS coordenados;
- ⏳ autenticação opcional para stream remoto, se decidirmos oferecer acesso fora da rede local.

## Pro — recursos avançados futuros
- ⏳ Dual Camera, quando o aparelho permitir múltiplas câmeras simultâneas;
- ⏳ Picture-in-Picture entre duas câmeras;
- ⏳ modo tela apagada/economia de energia dentro do que o Android permitir;
- ⏳ operação em segundo plano dentro das limitações do Android;
- ⏳ reconexão automática avançada com retomada da sessão;
- ⏳ monitor de estabilidade/qualidade do link;
- ⏳ troca automática para perfil mais leve quando a rede piorar, se aprovada futuramente.

## Comercialização Free / Pro — ainda não ativada
- ⏳ sistema real de licença/assinatura Pro;
- ⏳ bloqueio de 4K para Free;
- ⏳ bloqueio de lentes extras para Free;
- ⏳ remoção de marca d'água apenas para Pro;
- ⏳ bloqueio/desbloqueio dos controles Pro;
- ⏳ tela de upgrade/restaurar compra;
- ⏳ integração com o método de venda/licenciamento que for escolhido.

## O que a Build 23 já trouxe
- ✅ 4K frontal implementado para teste;
- ✅ 8K removido do aplicativo ativo;
- ✅ marca d'água `GOAT CAM FREE`;
- ✅ bitrate manual H.264;
- ✅ Smart Link local;
- ✅ presets P1/P2/P3;
- ✅ todos os controles profissionais que já existiam foram preservados;
- ✅ integração remota existente com o GOAT PRO Studio foi preservada;
- ⏳ bloqueio Free/Pro real ainda não foi ativado.

## Diretriz do produto
A versão gratuita deve continuar útil de verdade em 1080p. O Pro deve vender ganho profissional, câmeras/resoluções avançadas e integração com o ecossistema GOAT, e não apenas bloquear artificialmente funções básicas do aparelho.

## Regra para conversas futuras
Antes de implementar novos recursos Pro, consultar este arquivo e atualizar o status de cada item conforme ele for implementado e testado. Não considerar um recurso concluído apenas porque foi planejado nesta lista.
