# VTuber Studio

App Android (Kotlin) com overlay VTuber + editor de personagem + editor de video.

**Versao atual: v2.1 (versionCode 5)** — tela inicial mostra `v2.1 - build 5`.

## v2.1

- Detector de voz consertado: nao trava mais por causa do assistente "Ok
  Google" (que deixava uma gravacao ativa e bloqueava o detector pra sempre).
- Barra de nivel de microfone ao vivo no preview (diagnostico).
- Limiar adaptativo (dispara acima de 4x o ruido ambiente).
- NOVO: aba Video — escolha um video da galeria, marque inicio/fim na
  timeline e corte sem recodificar (copia os frames originais). Salva na
  galeria (Filmes/VTuber, Android 10+) ou compartilha (Android 8+).

## Instalar

1. Actions -> run mais recente -> artifact `vtuber-apk-build-<numero>`.
2. Desinstale a versao anterior (assinaturas de build CI diferem).
3. Instale, confirme o selo `v2.1 - build 5`, conceda microfone + sobreposicao.

## Stack

Kotlin 2.0.21 - Compose BOM 2024.12.01 - AGP 8.7.2 - Gradle 8.11.1 - compileSdk 35 - minSdk 26 - JDK 17
