# Contribuindo com o LylyReader

Obrigado pelo interesse! Este projeto é mantido pela comunidade; correções, melhorias, traduções e relatos de bugs são bem-vindos.

## Antes de começar
- Procure nas [issues](../../issues) se o assunto já existe. Para mudanças grandes, abra uma issue antes para alinharmos a ideia.
- **Nunca** inclua chaves de API, senhas, keystores ou dados pessoais em commits, issues ou capturas de tela.
- **Conteúdo:** o app só deve oferecer fontes legais de livros (domínio público ou licenças livres). Não serão aceitas fontes que distribuam obras protegidas sem autorização, nem formas de contornar proteções.

## Preparando o ambiente
Requisitos: Android Studio (ou apenas JDK 17), Android SDK 36 e Python 3.11 (usado pelo Chaquopy no build).

```bash
git clone https://github.com/JonJonesBR/LylyReader.git
cd LylyReader
./scripts/fetch-onnxruntime.sh        # Windows: .\scripts\fetch-onnxruntime.ps1
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Dica: não rode o Gradle dentro de uma pasta sincronizada (Google Drive, OneDrive etc.); o build gera milhares de arquivos pequenos e fica lento.

## Enviando uma mudança
1. Faça um fork e crie um branch a partir de `main`.
2. Mantenha o PR pequeno e focado em um assunto.
3. Garanta que `./gradlew :app:testDebugUnitTest` passa. Inclua testes para regras de negócio novas (há muitos exemplos em `app/src/test`).
4. Textos visíveis ao usuário vão em `res/values*/strings.xml` **nos três idiomas** (inglês, português e espanhol).
5. Respeite o estilo do código ao redor; `detekt` roda no CI.
6. Descreva no PR o que mudou e como você testou. Se mexeu na interface, anexe capturas.

## Áreas sensíveis
Mudanças em `app/build.gradle.kts` (empacotamento, Chaquopy), no schema do banco (`app/schemas`) e nos processos nativos de TTS exigem cuidado extra; explique bem o motivo no PR.

## Licença
Ao contribuir, você concorda que sua contribuição será licenciada sob a [MIT](LICENSE).
