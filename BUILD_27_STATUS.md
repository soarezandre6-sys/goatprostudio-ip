# GOAT Cam Build 27

Status: código integrado; APK ainda não gerado.

## 4K frontal
- 4K volta a aparecer no Galaxy S21 mesmo quando o CamcorderProfile legado não publica 2160p.
- primeiro usa perfil oficial 2160p quando existir.
- depois verifica `Camera.Parameters.supportedVideoSizes` da Camera1.
- no Galaxy S21 há fallback experimental 3840x2160.
- tenta 60 FPS; se a preparação do MediaRecorder recusar, tenta 30 FPS antes de desistir.

## FPS
- painel aceita até 120 FPS, mas o máximo é atualizado por câmera/resolução.
- fluxo normal aplica `CONTROL_AE_TARGET_FPS_RANGE` até 60 FPS quando a câmera anuncia.
- acima de 60 FPS entra a rota `CameraConstrainedHighSpeedCaptureSession` somente quando a combinação é oficialmente anunciada pelo Camera2.
- H.264 foi liberado para até 120 FPS.
- segundo plano leve foi liberado para até 60 FPS.

## Padrão de FPS
- toda nova abertura do app inicia em 30 FPS.
- trocar câmera/lente ou resolução também volta para 30 FPS.
- 60 e 120 FPS continuam disponíveis somente por escolha manual e quando suportados.
- Smart Link não restaura automaticamente um FPS alto de uma sessão anterior.
- o 4K frontal também parte de 30 FPS e só sobe se o usuário escolher depois.

## Segurança
- Build 26 preservada.
- checkpoint: `checkpoint-build26-before-fps4k-2026-10-02`.
- nenhuma alteração na release.
