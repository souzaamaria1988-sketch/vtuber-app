# VTuber Studio

App Android (Kotlin) com overlay VTuber + editor de sprites.

**Versao atual: v2.0 (versionCode 4)** — tela inicial mostra `v2.0 - build 4`.

## O que tem

- Importar quantos sprites quiser direto da galeria (dentro do app).
- Listas separadas: IDLE (calado) e TALKING (falando); nome do arquivo com
  "talk" vai pra lista Falando; numero no fim = ordem na animacao.
- Preview ao vivo com microfone (veja o personagem reagir antes de ativar).
- Estilo Discord: calado = desce um pouco + transparente + escurecido;
  falando = sobe, imagem normal + brilho branco (tudo ajustavel).
- Calibracao automatica do microfone (mede o ruido ambiente).
- Microfone em modo compartilhado: nao quebra gravacao de tela nem chamadas.
- Overlay: long-press edita (arrastar move, bolinhas redimensionam).

## Instalar

1. Actions -> run mais recente -> artifact `vtuber-apk-build-<numero>`.
2. Desinstale a versao anterior (assinaturas de build CI diferem).
3. Instale, confirme o selo `v2.0 - build 4`, conceda microfone + sobreposicao.

## Stack

Kotlin 2.0.21 - Compose BOM 2024.12.01 - AGP 8.7.2 - Gradle 8.11.1 - compileSdk 35 - minSdk 26 - JDK 17
