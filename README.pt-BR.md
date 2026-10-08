# LylyReader

**Transforme qualquer livro em audiolivro, direto no seu celular Android.** O LylyReader importa arquivos EPUB, PDF, TXT, DOCX, MOBI e Markdown, lê em voz alta com vozes neurais (inclusive vozes totalmente offline) e guarda seus audiolivros e seu progresso de leitura numa só biblioteca.

Leia este README em [English](README.md) ou [Español](README.es.md).

<div align="center">

[![Baixar APK](https://img.shields.io/github/v/release/JonJonesBR/LylyReader?label=Baixar%20APK&style=for-the-badge&color=4F46E5)](https://github.com/JonJonesBR/LylyReader/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![Licença](https://img.shields.io/badge/Licen%C3%A7a-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Vídeos

Vídeos promocionais curtos, um em cada idioma:

| Idioma | Vídeo |
|---|---|
| 🇧🇷 Português | [LylyReader_promo_v2_pt.mp4](media/LylyReader_promo_v2_pt.mp4) |
| 🇺🇸 English | [LylyReader_promo_v2_en.mp4](media/LylyReader_promo_v2_en.mp4) |
| 🇪🇸 Español | [LylyReader_promo_v2_es.mp4](media/LylyReader_promo_v2_es.mp4) |

## Destaques

- **Audiolivro de qualquer livro:** importe um arquivo ou busque livros gratuitos de domínio público (Projeto Gutenberg, Wikisource, Internet Archive) sem sair do app.
- **Vozes neurais, online e offline:** Edge, Gemini, ElevenLabs e OpenRouter online; Supertonic, Kokoro, Piper, MMS e Pocket offline, no próprio aparelho.
- **Leitura guiada:** o texto acompanha a narração frase a frase, continua tocando em segundo plano e retoma de onde você parou.
- **Fila de conversões:** converta vários livros seguidos; a fila continua mesmo com o app fechado.
- **Voz por livro:** cada livro guarda a sua voz, velocidade e tom, e você pode dar vozes diferentes aos personagens.
- **Grátis e privado:** sem conta, sem anúncios e sem rastreamento. Seus livros e chaves de API ficam no seu aparelho.

## Funcionalidades

### Importação e livros
- Importe EPUB, PDF, TXT, DOC, DOCX, MOBI e Markdown pelo compartilhamento ou pelo seletor de arquivos.
- Importe um livro por link direto (por exemplo Google Drive, Dropbox ou link direto de arquivo).
- Baixe em segundo plano todos os resultados de uma busca.
- Biblioteca com abas (Todos, Lendo, Com áudio, Baixados), busca, capas, atalhos "Continuar" e ordenação.
- Backup e restauração de marcadores, destaques, progresso, estatísticas e configurações em um único ZIP, com uma mesclagem que nunca apaga dados locais.

### Leitura
- Leitura guiada com controle de velocidade ao vivo, normalização do texto para uma pronúncia natural (abreviações, datas, moedas, unidades, siglas) e dicionário de pronúncia.
- Narração em segundo plano, com indicador e uma notificação que leva de volta ao parágrafo exato.
- Sumário, marcadores, destaques com notas, consulta de palavras, busca no texto e progresso de leitura por capítulo.
- Temas: Claro, Escuro, Papel e preto puro, com fonte serifada opcional (Literata) e controle de margem.

### Audiolivros e conversão
- Converta um livro inteiro, um capítulo ou um trecho selecionado, com capa e marcas de capítulo.
- A tela **Conversões** mostra o que está sendo gerado, a fila e o resultado.
- Cada livro mantém sua voz, velocidade e tom; dá para ouvir uma prévia da voz antes de uma conversão longa.
- Exporte o audiolivro em MP3, os capítulos em .txt ou o audiolivro como vídeo MP4 (dividido em partes para o YouTube).
- Reprodução com continuação de onde parou, temporizador com fade-out, retrocesso automático, reprodução em segundo plano e controles na notificação.
- Troca de capítulo pela notificação, controles Bluetooth, relógios e **Android Auto**.

### Vozes e configurações
- Escolha de vozes no primeiro acesso, com filtro por idioma (português, inglês, espanhol ou todos) e download em segundo plano, com pausa e retomada.
- Interface e conteúdo em **português, inglês e espanhol**.
- Tema Claro, Escuro ou do sistema; velocidade da fala, pausas, bitrate e pasta de saída configuráveis.
- Visualizador de registros para diagnóstico.

## Vozes

**Incluídas no app (offline, sem download):** 10 vozes Supertonic (F1–F5, M1–M5), que funcionam em vários idiomas, inclusive português.

**Online (precisam de internet):**

| Idioma | Vozes |
|---|---|
| Português (Brasil) | Thalita, Antonio, Francisca |
| Português (Portugal) | Raquel |
| Inglês (EUA, Austrália) | Ava, Andrew, Emma, Brian, William |
| Francês | Vivienne, Remy |
| Alemão | Seraphina, Florian |
| Italiano | Giuseppe |
| Coreano | Hyunsu |

As vozes online vêm do Microsoft Edge TTS. As vozes do Gemini, do ElevenLabs e do OpenRouter usam a sua própria chave de API.

**Pacotes offline para baixar:**

| Motor | Idioma(s) | Observações |
|---|---|---|
| Supertonic | Multilíngue (EN, PT, ES, FR, KO e outros) | Vozes neurais; motor offline padrão |
| Kokoro (Sherpa-ONNX) | Inglês (EUA e Reino Unido), português (Santa) | Várias vozes em inglês |
| MMS-TTS (Meta) | Português | Licença não comercial (CC BY-NC 4.0) |
| Pocket TTS 3.3 | Português do Brasil, inglês, espanhol | Presets públicos fixos, sem clonagem de voz |
| Piper | Português (Cadu, Edresson, Faber, Jeff, Dii, Miro), inglês (Norman, LJSpeech), espanhol (Claude) | A licença varia por voz; Dii e Miro são CC BY-NC-SA |

Cada pacote offline mantém a sua própria licença. Confira o [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) antes de redistribuir um modelo.

## Segurança e privacidade

- Sem conta e sem análises de uso. Livros, progresso e estatísticas ficam no aparelho.
- As chaves de API (Gemini, ElevenLabs, OpenRouter) ficam no armazenamento criptografado do aparelho e nunca são enviadas a este projeto.
- Chaves reais de API não são versionadas no repositório.
- O repositório tem um workflow de higiene no GitHub Actions que bloqueia padrões comuns de segredos vazados.
- Áudios gerados, livros importados, arquivos de cache, chaves de release e de assinatura local são ignorados pelo Git.

## Arquitetura

O app combina uma interface nativa em Kotlin com um motor em Python embutido:

- O Kotlin cuida da interface Android, do ciclo de vida, dos intents de arquivo, da reprodução, das notificações, da fila e das configurações.
- O Python roda dentro do APK via Chaquopy e cuida da leitura de arquivos, da normalização do texto, da orquestração de TTS, do cache de áudio e dos metadados.
- As vozes offline rodam em ONNX Runtime (Supertonic, Kokoro, MMS, Pocket) e em Piper.

Pastas principais:

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/       telas, diálogos e adaptadores
  domain/   casos de uso e regras de negócio
  service/  conversão, reprodução e downloads em segundo plano
  tts/      motores de voz offline e gerenciadores de modelos
  data/     armazenamento local e repositórios
app/src/main/python/   leitura, normalização e pipeline de TTS
```

## Stack técnica

- Kotlin, Android Views, ViewBinding e Material Components
- WorkManager, serviços em primeiro plano e controles de mídia na notificação
- Chaquopy com Python 3.11 (`edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`, `numpy`)
- ONNX Runtime e Sherpa-ONNX para as vozes offline
- Gradle Kotlin DSL

## Requisitos

- Android Studio com JDK 17
- Android SDK 36
- Android 8.0 ou mais novo no aparelho
- Internet para os motores de voz online
- Opcional: uma chave de API do Google AI Studio para o Gemini TTS

## Execução local

```bash
git clone https://github.com/JonJonesBR/LylyReader.git
cd LylyReader
./scripts/fetch-onnxruntime.sh
./gradlew :app:assembleDebug
```

No Windows, rode `powershell -File scripts/fetch-onnxruntime.ps1` no lugar do primeiro script.

Para instalar o APK de depuração num aparelho conectado:

```bash
./gradlew :app:installDebug
```

## Download

O APK mais recente está na [página de Releases](https://github.com/JonJonesBR/LylyReader/releases/latest). É uma **compilação de depuração sem a assinatura do autor**, e instala como um app separado (`com.jonjonesbr.audiobookgen.debug`).

## Roadmap

- Testes automatizados para a extração de documentos e casos de arquivos compartilhados.
- Testes instrumentados para as telas principais de importação e conversão.
- Exportação de vídeo de audiolivros longos mais rápida em aparelhos simples.
- Mais idiomas na interface.

Ideias e relatos de bugs são bem-vindos nas [Issues](../../issues).

## Contribuindo

Veja o [CONTRIBUTING.md](CONTRIBUTING.md). Relatos de segurança passam pelo [SECURITY.md](SECURITY.md).

## Créditos

O LylyReader se apoia no trabalho de muitos projetos e comunidades de código aberto:

- **Supertone – Supertonic** (vozes neurais offline): https://github.com/supertone-inc/supertonic
- **Kyutai Labs – Pocket TTS** (vozes offline, CC BY 4.0) e o runtime **PocketTTS.cpp** de VolgaGerm (MIT): https://github.com/kyutai-labs/pocket-tts · https://github.com/VolgaGerm/PocketTTS.cpp
- **Kokoro** e **sherpa-onnx** da k2-fsa (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- **ONNX Runtime** da Microsoft (MIT): https://github.com/microsoft/onnxruntime
- **Vozes Piper** dos projetos rhasspy e da comunidade (cada voz tem sua própria licença): https://github.com/rhasspy/piper
- **MMS-TTS** da Meta (CC BY-NC 4.0, uso não comercial): https://huggingface.co/facebook/mms-tts-por
- **Chaquopy** – Python no Android (MIT): https://chaquo.com/chaquopy/
- **Vozes online da Microsoft Edge** (via o cliente não oficial `edge-tts`, LGPL-3.0): https://github.com/rany2/edge-tts
- **Fonte Literata** do The Literata Project (SIL OFL 1.1): https://github.com/googlefonts/literata
- **Projeto Gutenberg**, **Wikisource** e **Internet Archive**, as fontes de domínio público da busca de livros.
- Pacotes Python: `aiohttp`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `numpy`, `olefile`, `mobi`, `httpx`.
- Bibliotecas Android do Google e do AndroidX (Apache-2.0), e a linguagem Kotlin da JetBrains (Apache-2.0).

Os detalhes completos de atribuição dos recursos e modelos incluídos estão em [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Licença

MIT. Veja [LICENSE](LICENSE).
