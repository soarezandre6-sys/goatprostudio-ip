# GOAT PRO IP — Status do projeto

## Build 1
Estado atual: primeira versão Android em desenvolvimento/teste.

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
- workflow de compilação Android preparado.

### Arquivos de referência
- APK de teste: `artifacts/GOAT_PRO_IP_Build1.apk`
- Plano de ação: `docs/GOAT_PRO_IP_Plano_de_Acao.pdf`
- Roteiro de teste: `docs/BUILD_1_TESTE.md`

### Próxima validação
1. instalar o APK em aparelho Android real;
2. testar 720p e 1080p;
3. abrir a transmissão no navegador do PC;
4. abrir a transmissão no GOAT PRO Studio;
5. medir estabilidade, atraso e FPS percebido.

## Regra de organização
Este repositório é exclusivo do GOAT PRO IP. O projeto GOAT PRO Studio / Build 561 permanece separado no repositório anterior.
