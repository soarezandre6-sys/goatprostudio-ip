# Build 24 — plano de correção

- Corrigir 4K frontal reaproveitando o caminho que funcionou para 4K traseiro sempre que a câmera frontal expuser 3840×2160 em YUV/PRIVATE.
- Enumerar 4K para todas as câmeras frontais expostas, não apenas uma.
- Refazer presets P1/P2/P3 como Preset 1/2/3 com ações Salvar/Carregar claras.
- Implementar streaming em segundo plano/tela apagada com foreground service, wake lock e lifecycle independente da Activity.
- Preservar 4K traseiro, tele, controles Camera2 e recursos Pro da Build 23.
- Não reintroduzir 8K.
