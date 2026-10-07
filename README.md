# LylyReader Android

Android app that converts digital books and documents into audiobook files on the device. The project combines a native Kotlin interface with an embedded Python processing engine for document parsing, text cleanup, text-to-speech orchestration, audio cache, and MP3 metadata handling.

Read this README in [Portuguese](README.pt-BR.md) or [Spanish](README.es.md).

<div align="center">

[![Download APK](https://img.shields.io/badge/Download-APK%20v1.8.0-4F46E5?style=for-the-badge)](https://github.com/JonJonesBR/LylyReader-Android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader-Android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Why This Project Matters

LylyReader was built as a practical Android tool for people who want to listen to long-form text without relying on a desktop workflow. It accepts common book/document formats, extracts readable text, lets the user choose a voice engine, and exports an audiobook file with playback and library support.

The implementation is intentionally hybrid:

- Kotlin handles Android UI, lifecycle, file intents, playback, notifications, queue state, and app settings.
- Python runs inside the APK through Chaquopy and handles parsing, text normalization, TTS pipeline coordination, audio cache, and metadata.
- The app supports multiple TTS engines: Microsoft Edge TTS, Google Gemini TTS, ElevenLabs and OpenRouter TTS online, Supertonic (offline neural voices) and the device's built-in Android TTS (offline) — API keys are stored locally by the user.

## Features

- Import EPUB, PDF, TXT, DOC, DOCX, MOBI and Markdown files from the Android share sheet or file picker.
- Convert text into audiobooks using online engines (Microsoft Edge TTS, Google Gemini TTS, ElevenLabs, OpenRouter) or fully offline voices (Supertonic neural TTS, or the device's built-in Android TTS).
- ElevenLabs voice engine using the official API with your own key (stored securely on-device).
- Offline on-device voices via ONNX Runtime: **Supertonic** (multilingual).
- Guided reading mode: the text scrolls smoothly with the narration and you can change the speaking speed live.
- Background guided reading: narration keeps playing when you leave the reader, with an in-app indicator and a notification that takes you back to the exact paragraph.
- Smart TTS buffering: upcoming paragraphs are synthesized ahead of playback (deeper window for online engines), and a content-addressed synthesis cache is shared between guided reading and the final conversion so audio is reused, not regenerated.
- "Continue reading" shortcuts on the home and library screens resume a guided reading instantly from where you stopped.
- Smart text normalization in guided reading: expands abbreviations, symbols (&,@,+,=,#), dates, percentages, currency, units, and acronyms for natural PT-BR pronunciation.
- Share a text excerpt from the reader via Android share sheet.
- Sepia and pure black (OLED) reading themes, alongside Light/Dark, with an optional serif font and a horizontal text margin control.
- Rich reading progress: percentage of the book read and estimated time remaining in the current chapter.
- Navigable table of contents, accessible at any time while reading.
- Bookmark any paragraph while reading with a single tap; a dedicated list lets you jump back to any saved spot.
- Text highlights with an optional note, saved per book.
- Word lookup from text selection, opening an installed dictionary app or a web search.
- Auto-rewind when resuming a paused audiobook, with configurable short or long rewind options.
- Smooth volume fade-out in the last seconds of the sleep timer, instead of a hard cut.
- Guided reading voice and speed saved per book, automatically restored when you reopen each title.
- Reading and listening statistics screen (minutes listened/read, books finished, day streak), fully local and private.
- Library statistics screen showing total books, total duration, and aggregate reading progress.
- Sort library by progress (incomplete books first).
- Full backup and restore (bookmarks, highlights, reading/listening progress, statistics and settings) as a single ZIP file, with a conservative merge on import that never loses local data; a lighter settings-only JSON export/import is also available.
- Internal log viewer for troubleshooting, accessible from settings.
- Delete installed offline voices directly from the voice management screen.
- Sliders with manual numeric input; quick-access brightness control from the reader appearance sheet; tooltips and tutorial access.
- 10 built-in Supertonic PT-BR voices (F1–F5 / M1–M5) included in the APK, with faster synthesis on low-end devices.
- Preview voices before starting long conversions.
- Warning and shortcut when the selected Android voice has no downloaded data, with a direct link to install it.
- Configure narration speed, paragraph pauses, bitrate, Supertonic quality (steps), and output folder.
- Light / Dark / System theme selector.
- Sleep timer with the remaining time shown on screen and in the media notification.
- Resume interrupted conversions with an audio chunk cache.
- Save generated audiobooks with cover art and chapter metadata when available.
- Export the chapters of a book as separate .txt files, or the audiobook as a video (MP4).
- Play generated audio in the app: resume playback from where you stopped, with a "% listened" indicator in the library, plus background playback and notification controls.
- Skip to the previous or next chapter directly from the media notification, Bluetooth controls or a wearable, when the audiobook has chapter marks.
- Browse and play your converted audiobook library from Android Auto.
- Jump between reading and listening to the same book: open the matching audiobook from the reader once it has been converted, or jump back to the source text from the player — including a "Read original" shortcut right after conversion.
- Manage a local audiobook library (with name search) and a conversion queue.
- Interface reorganized into tabs (Settings, reader actions menu, home screen menu), with a clear title and description on each option.

## Architecture

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/          Activities, adapters and view models (MainActivity, ReaderActivity, SettingsActivity, ...)
  service/     Playback/conversion services (AudioPlayerService, GuidedReadingService, ConversionWorker, SleepTimerManager)
  tts/         Offline TTS engine (OnnxTtsEngine, OnnxSynthBridge, Supertonic helpers)
  domain/      Use cases (PythonEngineUseCase bridge to Chaquopy, extraction/export)
  player/      Audio player engine abstraction (MediaPlayer/ExoPlayer)
  data/        Room database, repositories and secure preferences
  util/        Helpers (LanguageDetector, VoiceCatalog, crash logging, ...)

app/src/main/python/
  audiobook_android.py         Kotlin-facing Python entrypoint
  config_android.py            Runtime configuration
  core_processor_android.py    Conversion orchestration
  tts.py                       Engine auto-routing (Edge/Gemini/ONNX bridge)
  text_processor.py            Text extraction and cleanup helpers
  gemini_tts.py                Gemini TTS client logic
  audio_cache.py               Resume/cache layer for generated chunks
```

Native ONNX libraries and bundled models are distributed in the release APK and are not stored in this repository.

## Security And Privacy

- Real API keys are not committed to the repository.
- `.env.example` documents local configuration placeholders.
- Gemini keys are stored on device with `EncryptedSharedPreferences` and migrated away from the previous plain `SharedPreferences` key on first read.
- The repository has a GitHub Actions hygiene workflow that blocks common leaked-secret patterns.
- Generated audio, imported books, cache files, release keys, service accounts and local signing files are ignored by Git.

## Tech Stack

- Kotlin
- Android Views, ViewBinding and Material Components
- WorkManager
- Foreground service and media notification controls
- Chaquopy with Python 3.11
- Python libraries: `edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`
- Gradle Kotlin DSL

## Requirements

- Android Studio with JDK 17
- Android SDK 36
- Android 8.0 or newer on the target device
- Internet access for online TTS engines
- Optional: a Google AI Studio API key for Gemini TTS

## Run Locally

```bash
git clone https://github.com/JonJonesBR/LylyReader-Android.git
cd LylyReader-Android
./gradlew :app:assembleDebug
```

Install the debug APK on a connected device:

```bash
./gradlew :app:installDebug
```

For release builds, create a local `release.properties` file with signing values. Do not commit this file.

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=change-me-locally
keyAlias=release
keyPassword=change-me-locally
```

## Download

The latest public APK is available on the [GitHub Releases page](https://github.com/JonJonesBR/LylyReader-Android/releases/latest). This repository currently builds app version `1.8.0`.

## Roadmap

- Add automated tests for document extraction and shared-file edge cases.
- Add instrumented tests for the main import/conversion screens.
- Improve long-running conversion resilience with deeper WorkManager coverage.
- Expand Play Store listing metadata for English and Spanish.

## Credits

LylyReader stands on the work of many open-source projects and communities:

- **Supertone – Supertonic** (offline neural voices): https://github.com/supertone-inc/supertonic
- **Kyutai Labs – Pocket TTS** (offline voices, CC BY 4.0) and the **PocketTTS.cpp** runtime by VolgaGerm (MIT): https://github.com/kyutai-labs/pocket-tts · https://github.com/VolgaGerm/PocketTTS.cpp
- **Kokoro** and **sherpa-onnx** by k2-fsa (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- **ONNX Runtime** by Microsoft (MIT): https://github.com/microsoft/onnxruntime
- **Piper** voices from the rhasspy and community projects (each voice has its own license): https://github.com/rhasspy/piper
- **Meta MMS-TTS** (CC BY-NC 4.0, non-commercial use): https://huggingface.co/facebook/mms-tts-por
- **Chaquopy** – Python on Android (MIT): https://chaquo.com/chaquopy/
- **Microsoft Edge TTS** (online voices, via the unofficial `edge-tts` client, LGPL-3.0): https://github.com/rany2/edge-tts
- **Literata** typeface by The Literata Project (SIL OFL 1.1): https://github.com/googlefonts/literata
- **Project Gutenberg**, **Wikisource** and **Internet Archive**, the public-domain sources of the built-in book search.
- Python packages: `aiohttp`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `numpy`, `olefile`, `mobi`, `httpx`.
- Android libraries from Google and AndroidX (Apache-2.0), and the Kotlin language by JetBrains (Apache-2.0).

Full attribution details for bundled assets and models are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## License

MIT. See [LICENSE](LICENSE).
