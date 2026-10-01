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
