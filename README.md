# VTuber Studio

App Android (Kotlin) com overlay VTuber + multi-personagem + editor de video.

**Versao atual: v2.2 (versionCode 6)** — selo `v2.2 - build 6`.

## v2.2

- MULTI-PERSONAGEM: quantos quiser, cada um com posicao, tamanho e frames
  proprios. Chips no Studio para alternar/criar/remover.
- Animacoes: entrada pop escalonada, flutuacao com fase por personagem,
  bounce ao falar, brilho branco.
- Microfone ~3x mais rapido (dispara na primeira amostra alta).
- Limitacao honesta: impossivel ouvir a voz dos outros numa chamada
  (o Android da o microfone exclusivo ao app da chamada).

## Instalar

Actions -> artifact `vtuber-apk-build-<n>` -> desinstale o anterior ->
instale -> confirme `v2.2 - build 6`.

## Stack

Kotlin 2.0.21 - Compose BOM 2024.12.01 - AGP 8.7.2 - Gradle 8.11.1 - compileSdk 35 - minSdk 26 - JDK 17
