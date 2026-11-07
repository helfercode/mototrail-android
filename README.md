# MotoTrail Android 0.2.0 — Navigation Foundation

Protótipo Android nativo em Kotlin e Jetpack Compose.

## Estado real desta versão

Implementado no código:
- Aplicação Android com quatro áreas: Mapa, Percursos, Comunidade e Perfil.
- Seletor de ficheiros para importar GPX.
- Leitura XML de pontos GPX.
- Cálculo aproximado da distância do track.
- Pedido de permissões de localização e consulta da última posição conhecida.
- A localização não é enviada para qualquer servidor.

Ainda não implementado:
- MapLibre e mapas OpenStreetMap reais.
- Desenho do track sobre um mapa.
- Navegação e encaminhamento.
- Mapas offline.
- Backend, contas, comunidade online, P2P e SOS.

## Compilar sem Android Studio

O projeto inclui um workflow GitHub Actions em `.github/workflows/build-apk.yml`.

1. Criar um repositório GitHub vazio.
2. Extrair este ZIP e carregar todos os ficheiros para o repositório.
3. Abrir o separador **Actions**.
4. Executar **Build MotoTrail APK** (ou aguardar a compilação após o push).
5. Abrir a execução concluída e descarregar o artefacto `MotoTrail-0.2.0-debug`.
6. Extrair `app-debug.apk` e transferi-lo para o Android.
7. No telefone, autorizar a instalação dessa origem e instalar o APK.

## Compilação local

Requer JDK 17, Android SDK (platform 35 e build-tools 35.0.0) e Gradle 8.9:

```powershell
gradle :app:assembleDebug
```

APK esperado:
`app/build/outputs/apk/debug/app-debug.apk`

## Teste inicial recomendado

- Abrir a aplicação.
- Consultar a localização com o telefone no exterior.
- Importar um ficheiro GPX.
- Confirmar o nome, número de pontos e distância aproximada.
- Confirmar que a área Comunidade e Perfil identifica claramente as funções ainda não disponíveis.

## Nota

Este projeto não foi compilado nem testado neste ambiente. O workflow remoto foi incluído para gerar o APK sem Android Studio; a compilação só fica confirmada quando uma execução GitHub Actions terminar com sucesso.
