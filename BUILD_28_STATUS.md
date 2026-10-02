# GOAT Cam Build 28

Status: micro revisão aplicada no código; APK ainda não gerado nem validado no aparelho.

## Diagnóstico do 4K frontal
- As tentativas anteriores pediam 3840x2160 diretamente para a HAL pública da câmera.
- No Galaxy S21 testado, a câmera frontal pública não anunciou 3840x2160 como stream Camera2, embora o aplicativo nativo Samsung grave em 4K.
- O maior sensor/stream frontal público observado fica abaixo de 3840 pixels de largura (diagnóstico anterior: câmera frontal principal em torno de 3648x2736).
- Isso explica por que Camera2/MediaCodec, Camera2/MediaRecorder e Camera1/MediaRecorder recusaram 3840x2160 direto.

## Nova rota 4K frontal
- Nova classe `GpuCameraH264Streamer`.
- A câmera abre somente um tamanho SurfaceTexture que a API pública realmente anuncia.
- A GPU faz crop 16:9, escala e rotação para a Surface do encoder H.264.
- Saída alvo continua 3840x2160.
- Em 30 FPS prioriza a maior fonte pública disponível; em 60 FPS pode escolher uma fonte pública menor que sustente 60 FPS e compor a saída 4K.
- Não existe cópia NV21/JPEG/CPU nesse caminho.
- A opção 4K frontal só aparece quando há uma fonte frontal pública de alta resolução (mínimo de 5 MP) para evitar anunciar 4K em aparelhos inadequados.

## Segundo plano e rotação
- Causa encontrada: `onPause()` desligava o `OrientationEventListener` ao apertar Home/bloquear a tela.
- O antigo `BackgroundH264Streamer` câmera->encoder também não tinha etapa capaz de rotacionar pixels.
- `onPause()` agora mantém o sensor de orientação ativo quando há transmissão em segundo plano com rotação AUTO.
- O modo leve de fundo passou para câmera -> GPU -> H.264.
- A GPU aplica crop/escala/rotação sem voltar ao ImageAnalysis pesado da CPU.
- Ao cruzar orientação retrato/paisagem, o encoder GPU é reconfigurado com dimensões adequadas.

## Segurança / processo
- Build 27 preservada.
- Checkpoint criado: `checkpoint-build27-before-gpu-pipeline-2026-10-02`.
- Release não foi alterada.
- APK Build 28 ainda não foi gerado.
