# LylyReader

**Convierte cualquier libro en audiolibro, directamente en tu móvil Android.** LylyReader importa archivos EPUB, PDF, TXT, DOCX, MOBI y Markdown, los lee en voz alta con voces neuronales (incluidas voces totalmente sin conexión) y guarda tus audiolibros y tu progreso de lectura en una sola biblioteca.

Lee este README en [English](README.md) o [Português](README.pt-BR.md).

<div align="center">

[![Descargar APK](https://img.shields.io/github/v/release/JonJonesBR/LylyReader?label=Descargar%20APK&style=for-the-badge&color=4F46E5)](https://github.com/JonJonesBR/LylyReader/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![Licencia](https://img.shields.io/badge/Licencia-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Videos

Videos promocionales cortos, uno por idioma:

| Idioma | Video |
|---|---|
| 🇧🇷 Português | [LylyReader_promo_v2_pt.mp4](media/LylyReader_promo_v2_pt.mp4) |
| 🇺🇸 English | [LylyReader_promo_v2_en.mp4](media/LylyReader_promo_v2_en.mp4) |
| 🇪🇸 Español | [LylyReader_promo_v2_es.mp4](media/LylyReader_promo_v2_es.mp4) |

## Destacados

- **Audiolibro de cualquier libro:** importa un archivo o busca libros gratuitos de dominio público (Proyecto Gutenberg, Wikisource, Internet Archive) sin salir de la app.
- **Voces neuronales, en línea y sin conexión:** Edge, Gemini, ElevenLabs y OpenRouter en línea; Supertonic, Kokoro, Piper, MMS y Pocket sin conexión, en el propio dispositivo.
- **Lectura guiada:** el texto sigue la narración frase a frase, sigue sonando en segundo plano y retoma donde lo dejaste.
- **Cola de conversiones:** convierte varios libros seguidos; la cola sigue aunque cierres la app.
- **Voz por libro:** cada libro guarda su voz, velocidad y tono, y puedes dar voces distintas a los personajes.
- **Gratis y privado:** sin cuenta, sin anuncios y sin rastreo. Tus libros y tus claves de API se quedan en tu dispositivo.

## Funcionalidades

### Importación y libros
- Importa EPUB, PDF, TXT, DOC, DOCX, MOBI y Markdown desde el menú de compartir o el selector de archivos.
- Importa un libro desde un enlace directo (por ejemplo Google Drive, Dropbox o un enlace directo al archivo).
- Descarga en segundo plano todos los resultados de una búsqueda.
- Biblioteca con pestañas (Todos, Leyendo, Con audio, Descargados), búsqueda, portadas, accesos "Continuar" y ordenación.
- Copia de seguridad y restauración de marcadores, destacados, progreso, estadísticas y ajustes en un único ZIP, con una fusión que nunca borra datos locales.

### Lectura
- Lectura guiada con control de velocidad en vivo, normalización del texto para una pronunciación natural (abreviaturas, fechas, monedas, unidades, siglas) y diccionario de pronunciación.
- Narración en segundo plano, con indicador y una notificación que te lleva de vuelta al párrafo exacto.
- Índice, marcadores, destacados con notas, consulta de palabras, búsqueda en el texto y progreso de lectura por capítulo.
- Temas: Claro, Oscuro, Papel y negro puro, con fuente con serifas opcional (Literata) y control de márgenes.

### Audiolibros y conversión
- Convierte un libro completo, un capítulo o un fragmento seleccionado, con portada y marcas de capítulo.
- La pantalla **Conversiones** muestra lo que se está generando, la cola y el resultado.
- Cada libro mantiene su voz, velocidad y tono; puedes escuchar una muestra de la voz antes de una conversión larga.
- Exporta el audiolibro en MP3, los capítulos en .txt o el audiolibro como video MP4 (dividido en partes para YouTube).
- Reproducción con continuación donde la dejaste, temporizador con desvanecimiento, retroceso automático, reproducción en segundo plano y controles en la notificación.
- Cambio de capítulo desde la notificación, controles Bluetooth, relojes y **Android Auto**.

### Voces y ajustes
- Selección de voces en el primer uso, con filtro por idioma (portugués, inglés, español o todos) y descarga en segundo plano, con pausa y reanudación.
- Interfaz y contenido en **portugués, inglés y español**.
- Tema Claro, Oscuro o del sistema; velocidad de la voz, pausas, bitrate y carpeta de salida configurables.
- Visor de registros para diagnóstico.

## Voces

**Incluidas en la app (sin conexión, sin descarga):** 10 voces Supertonic (F1–F5, M1–M5), que funcionan en varios idiomas, incluido el portugués.

**En línea (requieren internet):**

| Idioma | Voces |
|---|---|
| Portugués (Brasil) | Thalita, Antonio, Francisca |
| Portugués (Portugal) | Raquel |
| Inglés (EE. UU., Australia) | Ava, Andrew, Emma, Brian, William |
| Francés | Vivienne, Remy |
| Alemán | Seraphina, Florian |
| Italiano | Giuseppe |
| Coreano | Hyunsu |

Las voces en línea vienen de Microsoft Edge TTS. Las voces de Gemini, ElevenLabs y OpenRouter usan tu propia clave de API.

**Paquetes sin conexión para descargar:**

| Motor | Idioma(s) | Notas |
|---|---|---|
| Supertonic | Multilingüe (EN, PT, ES, FR, KO y más) | Voces neuronales; motor sin conexión predeterminado |
| Kokoro (Sherpa-ONNX) | Inglés (EE. UU. y Reino Unido), portugués (Santa) | Varias voces en inglés |
| MMS-TTS (Meta) | Portugués | Licencia no comercial (CC BY-NC 4.0) |
| Pocket TTS 3.3 | Portugués de Brasil, inglés, español | Presets públicos fijos, sin clonación de voz |
| Piper | Portugués (Cadu, Edresson, Faber, Jeff, Dii, Miro), inglés (Norman, LJSpeech), español (Claude) | La licencia varía según la voz; Dii y Miro son CC BY-NC-SA |

Cada paquete sin conexión mantiene su propia licencia. Consulta [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) antes de redistribuir un modelo.

## Seguridad y privacidad

- Sin cuenta y sin analítica. Libros, progreso y estadísticas se quedan en el dispositivo.
- Las claves de API (Gemini, ElevenLabs, OpenRouter) se guardan en el almacenamiento cifrado del dispositivo y nunca se envían a este proyecto.
- Las claves reales de API no se versionan en el repositorio.
- El repositorio tiene un flujo de higiene en GitHub Actions que bloquea patrones comunes de secretos filtrados.
- El audio generado, los libros importados, los archivos de caché y las claves de publicación y de firma local se ignoran en Git.

## Arquitectura

La app combina una interfaz nativa en Kotlin con un motor en Python integrado:

- Kotlin se encarga de la interfaz Android, el ciclo de vida, los intents de archivos, la reproducción, las notificaciones, la cola y los ajustes.
- Python se ejecuta dentro del APK mediante Chaquopy y se encarga de la lectura de archivos, la normalización del texto, la orquestación de TTS, la caché de audio y los metadatos.
- Las voces sin conexión funcionan con ONNX Runtime (Supertonic, Kokoro, MMS, Pocket) y con Piper.

Carpetas principales:

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/       pantallas, diálogos y adaptadores
  domain/   casos de uso y reglas de negocio
  service/  conversión, reproducción y descargas en segundo plano
  tts/      motores de voz sin conexión y gestores de modelos
  data/     almacenamiento local y repositorios
app/src/main/python/   lectura, normalización y pipeline de TTS
```

## Stack técnico

- Kotlin, Android Views, ViewBinding y Material Components
- WorkManager, servicios en primer plano y controles de medios en la notificación
- Chaquopy con Python 3.11 (`edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`, `numpy`)
- ONNX Runtime y Sherpa-ONNX para las voces sin conexión
- Gradle Kotlin DSL

## Requisitos

- Android Studio con JDK 17
- Android SDK 36
- Android 8.0 o superior en el dispositivo
- Internet para los motores de voz en línea
- Opcional: una clave de API de Google AI Studio para Gemini TTS

## Ejecución local

```bash
git clone https://github.com/JonJonesBR/LylyReader.git
cd LylyReader
./scripts/fetch-onnxruntime.sh
./gradlew :app:assembleDebug
```

En Windows, ejecuta `powershell -File scripts/fetch-onnxruntime.ps1` en lugar del primer script.

Para instalar el APK de depuración en un dispositivo conectado:

```bash
./gradlew :app:installDebug
```

## Descarga

El APK más reciente está en la [página de Releases](https://github.com/JonJonesBR/LylyReader/releases/latest). Es una **compilación de depuración sin la firma del autor**, y se instala como una app separada (`com.jonjonesbr.audiobookgen.debug`).

## Hoja de ruta

- Pruebas automatizadas para la extracción de documentos y casos de archivos compartidos.
- Pruebas instrumentadas para las pantallas principales de importación y conversión.
- Exportación de video de audiolibros largos más rápida en dispositivos sencillos.
- Más idiomas en la interfaz.

Las ideas y los reportes de errores son bienvenidos en [Issues](../../issues).

## Contribuir

Consulta [CONTRIBUTING.md](CONTRIBUTING.md). Los reportes de seguridad van por [SECURITY.md](SECURITY.md).

## Créditos

LylyReader se apoya en el trabajo de muchos proyectos y comunidades de código abierto:

- **Supertone – Supertonic** (voces neuronales sin conexión): https://github.com/supertone-inc/supertonic
- **Kyutai Labs – Pocket TTS** (voces sin conexión, CC BY 4.0) y el runtime **PocketTTS.cpp** de VolgaGerm (MIT): https://github.com/kyutai-labs/pocket-tts · https://github.com/VolgaGerm/PocketTTS.cpp
- **Kokoro** y **sherpa-onnx** de k2-fsa (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- **ONNX Runtime** de Microsoft (MIT): https://github.com/microsoft/onnxruntime
- **Voces Piper** de los proyectos rhasspy y de la comunidad (cada voz tiene su propia licencia): https://github.com/rhasspy/piper
- **MMS-TTS** de Meta (CC BY-NC 4.0, uso no comercial): https://huggingface.co/facebook/mms-tts-por
- **Chaquopy** – Python en Android (MIT): https://chaquo.com/chaquopy/
- **Voces en línea de Microsoft Edge** (mediante el cliente no oficial `edge-tts`, LGPL-3.0): https://github.com/rany2/edge-tts
- **Tipografía Literata** de The Literata Project (SIL OFL 1.1): https://github.com/googlefonts/literata
- **Proyecto Gutenberg**, **Wikisource** e **Internet Archive**, las fuentes de dominio público de la búsqueda de libros.
- Paquetes de Python: `aiohttp`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `numpy`, `olefile`, `mobi`, `httpx`.
- Bibliotecas de Android de Google y AndroidX (Apache-2.0), y el lenguaje Kotlin de JetBrains (Apache-2.0).

Los detalles completos de atribución de los recursos y modelos incluidos están en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Licencia

MIT. Consulta [LICENSE](LICENSE).
