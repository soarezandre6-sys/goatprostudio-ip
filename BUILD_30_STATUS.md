# GOAT Cam Build 30

Status: correção do desaparecimento da opção 4K frontal aplicada; compilação Kotlin validada com sucesso; APK ainda não gerado.

## Problema da Build 29
- A opção 4K frontal podia desaparecer do painel.
- A causa era o teste de elegibilidade do fallback GPU usando uma fonte próxima de 16:9; no Galaxy S21 isso podia escolher 1920x1080, ficar abaixo do limite de 5 MP e fazer `profileFor()` retornar null.

## Correções Build 30
- O 4K frontal volta a ser oferecido no Galaxy S21 mesmo quando Camera1/Camera2 não publicam 3840x2160 nas listas usuais.
- Para decidir elegibilidade via GPU, o app passa a consultar a maior fonte frontal pública disponível.
- A rota de transmissão continua priorizando Camera1.
- Se Camera1 não listar 3840x2160, o S21 agora tenta o parâmetro legado OEM `video-size=3840x2160` antes de desistir.
- MediaRecorder continua em H.264 3840x2160, 30 FPS padrão e bitrate seguro.
- Se a rota Camera1 falhar, o fallback GPU usa uma fonte frontal realmente de alta resolução, em vez de cair em 1920x1080.
- 30 FPS continua sendo o padrão de inicialização.

## Processo
- Build 29 preservada.
- Branch atual: `build-30-goat-cam`.
- Release não alterada.
- Validação `:app:compileDebugKotlin`: SUCCESS.
- APK Build 30 ainda não gerado.
