# LylyReader

**Turn any book into an audiobook, right on your Android phone.** LylyReader imports EPUB, PDF, TXT, DOCX, MOBI and Markdown files, reads them aloud with neural voices (including fully offline ones), and keeps your audiobooks and reading progress in one library.

Read this README in [Português](README.pt-BR.md) or [Español](README.es.md).

<div align="center">

[![Download APK](https://img.shields.io/github/v/release/JonJonesBR/LylyReader?label=Download%20APK&style=for-the-badge&color=4F46E5)](https://github.com/JonJonesBR/LylyReader/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Videos

Short promotional videos, one per language:

<div align="center">

https://github.com/user-attachments/assets/3f099670-c9ce-47a4-8846-af99d97583c0

</div>

## Highlights

- **Audiobooks from any book:** import a file, or search free public-domain books (Project Gutenberg, Wikisource, Internet Archive) without leaving the app.
- **Neural voices, online and offline:** Edge, Gemini, ElevenLabs and OpenRouter online; Supertonic, Kokoro, Piper, MMS and Pocket offline, on the device.
- **Guided reading:** the text follows the narration sentence by sentence, keeps playing in the background, and resumes where you stopped.
- **Conversion queue:** convert several books in a row, and the queue keeps going with the app closed.
- **Per-book voice:** each book keeps its own voice, speed and tone, and you can give characters their own voices.
- **Free and private:** no account, no ads, no tracking. Your books and API keys stay on your device.

## Features

### Import and books
- Import EPUB, PDF, TXT, DOC, DOCX, MOBI and Markdown from the share sheet or the file picker.
- Import a book from a direct link (for example Google Drive, Dropbox or a direct file link).
- Download all results of a search in the background.
- A library with tabs (All, Reading, With audio, Downloaded), search, covers, "Continue" shortcuts and sorting.
- Backup and restore of bookmarks, highlights, progress, statistics and settings in a single ZIP, with a merge that never deletes local data.

### Reading
- Guided reading with live speed control, text normalization for natural pronunciation (abbreviations, dates, currency, units, acronyms) and a pronunciation dictionary.
- Background narration, with an indicator and a notification that returns you to the exact paragraph.
- Table of contents, bookmarks, highlights with notes, word lookup, text search and reading progress per chapter.
- Themes: Light, Dark, Sepia and pure black, with an optional serif font (Literata) and margin control.

### Audiobooks and conversion
- Convert a whole book, a chapter or a selected excerpt, with cover art and chapter marks.
- The **Conversions** screen shows what is being generated, with the queue and the result.
- Each book keeps its voice, speed and tone; you can preview voices before a long conversion.
- Export audiobooks as MP3, chapters as .txt, or the audiobook as an MP4 video (split into parts for YouTube).
- Playback with resume, sleep timer with fade-out, auto-rewind, background playback and notification controls.
- Chapter skipping from the notification, Bluetooth controls, wearables and **Android Auto**.

### Voices and settings
- A first-run voice picker that filters by language (Portuguese, English, Spanish or all) and downloads in the background, with pause and resume.
- Interface and content in **Portuguese, English and Spanish**.
- Light, Dark and System theme; configurable speech speed, pauses, bitrate and output folder.
- Built-in log viewer for troubleshooting.
- Voice cloning (Pocket TTS): record or import a short sample of your own voice. A step-by-step guide helps you download Kyutai's free official file with your own Hugging Face account, and the app prepares it on the phone.

## Voices

**Included in the app (offline, no download):** 10 Supertonic voices (F1–F5, M1–M5), usable in several languages, including Portuguese.

**Online (need internet):**

| Language | Voices |
|---|---|
| Português (Brasil) | Thalita, Antonio, Francisca |
| Português (Portugal) | Raquel |
| English (US, AU) | Ava, Andrew, Emma, Brian, William |
| Français | Vivienne, Remy |
| Deutsch | Seraphina, Florian |
| Italiano | Giuseppe |
| Korean | Hyunsu |

Online voices come from Microsoft Edge TTS. Gemini, ElevenLabs and OpenRouter voices use your own API key.

**Downloadable offline packages:**

| Engine | Language(s) | Notes |
|---|---|---|
| Supertonic | Multilingual (EN, PT, ES, FR, KO and more) | Neural voices, the default offline engine |
| Kokoro (Sherpa-ONNX) | English (US and UK), Portuguese (Santa) | Varied English voices |
| MMS-TTS (Meta) | Português | Non-commercial license (CC BY-NC 4.0) |
| Pocket TTS 3.3 | Português do Brasil, English, Español | Fixed public presets. Optional voice cloning: the app guides you, step by step, to download Kyutai's official file with your own Hugging Face account (no login inside the app) |
| Piper | Português (Cadu, Edresson, Faber, Jeff, Dii, Miro), English (Norman, LJSpeech), Español (Claude) | License varies per voice; Dii and Miro are CC BY-NC-SA |

Each offline package keeps its own license. Check [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) before redistributing a model.

## Credits

LylyReader stands on the work of many open-source projects and communities. The full list is in the README (credits section) and in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md). In short: Supertonic (Supertone), Pocket TTS (Kyutai Labs), Kokoro and sherpa-onnx (k2-fsa), ONNX Runtime (Microsoft), Piper and its voices, MMS-TTS (Meta), Chaquopy, edge-tts, the Literata font, and the public-domain sources Project Gutenberg, Wikisource and Internet Archive.

## Security And Privacy

- No account and no analytics. Books, progress and statistics stay on the device.
- Voice cloning needs no login in the app: you sign in only in your browser, and your voice sample stays on the device.
- API keys (Gemini, ElevenLabs, OpenRouter) are stored in the device's encrypted storage and are never sent to this project.
- Real API keys are not committed to the repository.
- The repository has a GitHub Actions hygiene workflow that blocks common leaked-secret patterns.
- Generated audio, imported books, cache files, release keys and local signing files are ignored by Git.

## Architecture

The app combines a native Kotlin interface with an embedded Python engine:

- Kotlin handles the Android UI, lifecycle, file intents, playback, notifications, the queue and the settings.
- Python runs inside the APK through Chaquopy and handles parsing, text normalization, TTS orchestration, the audio cache and metadata.
- Offline voices run on ONNX Runtime (Supertonic, Kokoro, MMS, Pocket) and on Piper.

Main folders:

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/       screens, dialogs and adapters
  domain/   use cases and business rules
  service/  background conversion, playback and downloads
  tts/      offline voice engines and model managers
  data/     local storage and repositories
app/src/main/python/   parsing, normalization and TTS pipeline
```

## Tech Stack

- Kotlin, Android Views, ViewBinding and Material Components
- WorkManager, foreground services and media notification controls
- Chaquopy with Python 3.11 (`edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`, `numpy`)
- ONNX Runtime and Sherpa-ONNX for offline voices
- Gradle Kotlin DSL

## Requirements

- Android Studio with JDK 17
- Android SDK 36
- Android 8.0 or newer on the target device
- Internet access for online TTS engines
- Optional: a Google AI Studio API key for Gemini TTS

## Run Locally

```bash
git clone https://github.com/JonJonesBR/LylyReader.git
cd LylyReader
./scripts/fetch-onnxruntime.sh
./gradlew :app:assembleDebug
```

On Windows, run `powershell -File scripts/fetch-onnxruntime.ps1` instead of the first script.

Install the debug APK on a connected device:

```bash
./gradlew :app:installDebug
```

## Download

The latest APK is on the [Releases page](https://github.com/JonJonesBR/LylyReader/releases/latest). It is a **debug build without the author's signature**, and it installs as a separate app (`com.jonjonesbr.audiobookgen.debug`).

## Roadmap

- Automated tests for document extraction and shared-file edge cases.
- Instrumented tests for the main import and conversion screens.
- Faster long-audiobook video export on low-end devices.
- More languages for the interface.

Ideas and bug reports are welcome in [Issues](../../issues).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports go through [SECURITY.md](SECURITY.md).

## License

MIT. See [LICENSE](LICENSE).
