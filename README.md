# VTuber App

App Android nativo (Kotlin) com overlay VTuber: personagem flutuante sobre outros
apps que alterna idle1/idle2 quando voce esta calado e talking quando voce fala.

**Versao atual: v1.1 (versionCode 2)** — a tela inicial mostra o selo `v1.1 - build 2`.
Se o seu app instalado NAO mostra isso, voce esta com uma build antiga.

## Como gerar o APK

- Rode o `vtuber-bootstrap.html` no navegador: informe um token GitHub (PAT classico
  com escopos **repo** + **workflow**), selecione as 3 imagens e clique no botao.
  Ele cria este repositorio, envia tudo em 1 commit e dispara o Actions.
- Ou altere/commite arquivos direto no GitHub: o workflow compila a cada push em main.

## Como instalar

1. Abra **Actions** e clique na run mais recente.
2. Baixe o artifact `vtuber-apk-build-<numero>` (ex.: vtuber-apk-build-42).
3. **Desinstale** qualquer versao anterior do app.
4. Instale o app-debug.apk (permitir fontes desconhecidas).
5. Abra o app, confirme o selo `v1.1 - build 2` e toque em **Ativar overlay**.
6. Conceda microfone + sobreposicao.

## Como usar

- Toque e segure o personagem (~0,5 s) para abrir o editor.
- Arraste o corpo para mover; bolinhas dos cantos redimensionam.
- **Pronto** fecha o editor; **Resetar** volta ao padrao.
- A notificacao tem acoes **Editar** / **Parar**.
- Gire o celular: posicao/tamanho relativos sao mantidos (salvos em % da tela).
- Toques fora do sprite passam para o app de baixo.

## Stack

Kotlin 2.0.21 - Compose BOM 2024.12.01 - AGP 8.7.2 - Gradle 8.11.1 - compileSdk 35 - minSdk 26 - JDK 17
