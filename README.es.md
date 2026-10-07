# LylyReader Android

Aplicacion Android que convierte libros digitales y documentos en archivos de audiolibro directamente en el dispositivo. El proyecto combina una interfaz nativa en Kotlin con un motor de procesamiento en Python embebido para lectura de documentos, limpieza de texto, orquestacion de text-to-speech, cache de audio y metadatos MP3.

Lee este README en [ingles](README.md) o [portugues](README.pt-BR.md).

<div align="center">

[![Download APK](https://img.shields.io/badge/Download-APK%20v1.8.0-4F46E5?style=for-the-badge)](https://github.com/JonJonesBR/LylyReader-Android/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/JonJonesBR/LylyReader-Android/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](app/src/main/java/com/jonjonesbr/audiobookgen)
[![Licencia](https://img.shields.io/badge/Licencia-MIT-blue?style=for-the-badge)](LICENSE)

</div>

## Por Que Importa Este Proyecto

LylyReader fue creado como una herramienta Android practica para personas que quieren escuchar textos largos sin depender de un flujo de trabajo en escritorio. Acepta formatos comunes de libros y documentos, extrae texto legible, permite elegir un motor de voz y exporta un archivo de audiolibro con soporte de reproduccion y biblioteca local.

La implementacion es intencionalmente hibrida:

- Kotlin gestiona la UI de Android, ciclo de vida, intents de archivo, reproduccion, notificaciones, estado de cola y ajustes de la app.
- Python se ejecuta dentro del APK mediante Chaquopy y gestiona parsing, normalizacion de texto, coordinacion del pipeline TTS, cache de audio y metadatos.
- La app soporta varios motores TTS: Microsoft Edge TTS, Google Gemini TTS, ElevenLabs y OpenRouter TTS en linea, Supertonic (voces neuronales sin conexion) y el TTS nativo de Android (sin conexion) — las claves de API se almacenan localmente por el usuario.

## Funcionalidades

- Importacion de archivos EPUB, PDF, TXT, DOC, DOCX, MOBI y Markdown desde el menu de compartir de Android o el selector de archivos.
- Conversion de texto en audiolibros usando motores en linea (Microsoft Edge TTS, Google Gemini TTS, ElevenLabs, OpenRouter) o voces totalmente sin conexion (Supertonic neuronal, o el TTS nativo de Android).
- Motor de voz ElevenLabs mediante la API oficial con tu propia clave (almacenada de forma segura en el dispositivo).
- Voces sin conexion en el dispositivo via ONNX Runtime: **Supertonic** (multilingue).
- Modo de lectura guiada: el texto se desplaza suavemente con la narracion y puedes cambiar la velocidad de la voz en vivo.
- Lectura guiada en segundo plano: la narracion sigue al salir del lector, con un indicador dentro de la app y una notificacion que te lleva de vuelta al parrafo exacto.
- Buffer inteligente de TTS: los parrafos siguientes se sintetizan antes de la reproduccion (ventana mas profunda para motores en linea) y un cache de sintesis por contenido se comparte entre la lectura guiada y la conversion final, reutilizando el audio en vez de regenerarlo.
- Atajos "Seguir leyendo" en la pantalla de inicio y en la biblioteca reanudan una lectura guiada al instante, desde donde te quedaste.
- Normalizacion inteligente de texto en la lectura guiada: expande abreviaturas, simbolos (&,@,+,=,#), fechas, porcentajes, moneda, unidades y siglas para una pronunciacion natural en PT-BR.
- Comparta un fragmento de texto desde el lector mediante el menu de compartir de Android.
- Temas de lectura Sepia y Negro puro (OLED), ademas de Claro/Oscuro, con fuente serif opcional y control de margen horizontal del texto.
- Progreso enriquecido en el lector: porcentaje del libro leido y tiempo estimado restante en el capitulo actual.
- Indice de capitulos navegable, accesible en cualquier momento durante la lectura.
- Marca cualquier parrafo durante la lectura con un toque; una lista dedicada te permite volver a cualquier punto guardado.
- Resaltados de texto con nota opcional, guardados por libro.
- Definicion de palabra desde la seleccion de texto, abriendo un diccionario instalado en el dispositivo o una busqueda web.
- Auto-retroceso al reanudar un audiolibro pausado, con opciones de retroceso corto o largo configurables.
- Fundido de salida suave del volumen en los ultimos segundos del temporizador de sueno, en vez de un corte brusco.
- Voz y velocidad de la lectura guiada guardadas por libro, restauradas automaticamente al reabrir cada titulo.
- Pantalla de estadisticas de lectura y escucha (minutos escuchados/leidos, libros terminados, racha de dias), totalmente local y privada.
- Pantalla de estadisticas de la biblioteca que muestra total de libros, duracion total y progreso agregado de lectura.
- Ordenacion de la biblioteca por progreso (no completados primero).
- Copia de seguridad y restauracion completas (marcadores, resaltados, progreso de lectura/escucha, estadisticas y ajustes) como un unico archivo ZIP, con fusion conservadora en la importacion que nunca pierde datos locales; tambien esta disponible una exportacion/importacion mas ligera, solo de ajustes, en JSON.
- Visor de registro interno para solucion de problemas, accesible desde los ajustes.
- Eliminacion de voces sin conexion instaladas directamente desde la pantalla de gestion de voces.
- Sliders con entrada numerica manual; control rapido de brillo en la hoja de apariencia del lector; tooltips y acceso al tutorial.
- 10 voces Supertonic PT-BR (F1–F5 / M1–M5) incluidas en el APK, con sintesis mas rapida en dispositivos de gama baja.
- Vista previa de voces antes de iniciar conversiones largas.
- Aviso y acceso directo cuando la voz de Android seleccionada no tiene datos descargados, con enlace directo para instalarla.
- Configuracion de velocidad de narracion, pausas entre parrafos, bitrate, calidad de Supertonic (pasos) y carpeta de salida.
- Selector de tema Claro / Oscuro / Sistema.
- Temporizador de sueno con el tiempo restante mostrado en pantalla y en la notificacion de medios.
- Reanudacion de conversiones interrumpidas con cache de chunks de audio.
- Guardado de audiolibros generados con portada y metadatos de capitulos cuando estan disponibles.
- Exportacion de los capitulos del libro como archivos .txt separados, o del audiolibro como video (MP4).
- Reproduccion de audio en la app: reanuda desde donde te quedaste, con indicador de "% escuchado" en la biblioteca, ademas de segundo plano y controles por notificacion.
- Salta al capitulo anterior o siguiente directamente desde la notificacion multimedia, controles Bluetooth o un dispositivo wearable, cuando el audiolibro tiene marcas de capitulo.
- Explora y reproduce tu biblioteca de audiolibros convertidos desde Android Auto.
- Alterna entre leer y escuchar el mismo libro: abre el audiolibro correspondiente desde el lector una vez convertido, o vuelve al texto de origen desde el reproductor — incluido el acceso directo "Leer original" justo despues de la conversion.
- Gestion de una biblioteca local de audiolibros (con busqueda por nombre) y una cola de conversion.
- Interfaz reorganizada en pestanas (Ajustes, menu de acciones del lector, menu de la pantalla principal), con titulo y descripcion claros en cada opcion.

## Arquitectura

```text
app/src/main/java/com/jonjonesbr/audiobookgen/
  ui/          Activities, adapters y view models (MainActivity, ReaderActivity, SettingsActivity, ...)
  service/     Servicios de reproduccion/conversion (AudioPlayerService, GuidedReadingService, ConversionWorker, SleepTimerManager)
  tts/         Motor TTS sin conexion (OnnxTtsEngine, OnnxSynthBridge, helpers de Supertonic)
  domain/      Casos de uso (PythonEngineUseCase, puente con Chaquopy, extraccion/exportacion)
  player/      Abstraccion del reproductor de audio (MediaPlayer/ExoPlayer)
  data/        Base de datos Room, repositorios y preferencias seguras
  util/        Utilidades (LanguageDetector, VoiceCatalog, registro de crashes, ...)

app/src/main/python/
  audiobook_android.py         Entrada Python llamada por Kotlin
  config_android.py            Configuracion en tiempo de ejecucion
  core_processor_android.py    Orquestacion de conversion
  tts.py                       Enrutamiento de motor (Edge/Gemini/puente ONNX)
  text_processor.py            Extraccion y limpieza de texto
  gemini_tts.py                Logica del cliente Gemini TTS
  audio_cache.py               Capa de reanudacion/cache para chunks generados
```

Las bibliotecas nativas ONNX y los modelos incluidos se distribuyen en el APK de la release y no se guardan en este repositorio.

## Seguridad Y Privacidad

- Las claves reales de API no se versionan en el repositorio.
- `.env.example` documenta placeholders para configuracion local.
- Las claves Gemini se almacenan en el dispositivo con `EncryptedSharedPreferences` y se migran desde la clave anterior en `SharedPreferences` en el primer acceso.
- El repositorio tiene un workflow de higiene en GitHub Actions que bloquea patrones comunes de secrets filtrados.
- Audio generado, libros importados, archivos de cache, claves de release, cuentas de servicio y archivos locales de firma son ignorados por Git.

## Stack Tecnico

- Kotlin
- Android Views, ViewBinding y Material Components
- WorkManager
- Foreground service y controles de notificacion multimedia
- Chaquopy con Python 3.11
- Bibliotecas Python: `edge-tts`, `aiohttp`, `mutagen`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `Pillow`
- Gradle Kotlin DSL

## Requisitos

- Android Studio con JDK 17
- Android SDK 36
- Android 8.0 o superior en el dispositivo objetivo
- Acceso a internet para motores TTS online
- Opcional: clave de API de Google AI Studio para Gemini TTS

## Ejecucion Local

```bash
git clone https://github.com/JonJonesBR/LylyReader-Android.git
cd LylyReader-Android
./gradlew :app:assembleDebug
```

Instala el APK debug en un dispositivo conectado:

```bash
./gradlew :app:installDebug
```

Para builds de release, crea un archivo local `release.properties` con los datos de firma. No versiones este archivo.

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=change-me-locally
keyAlias=release
keyPassword=change-me-locally
```

## Download

El APK publico mas reciente esta disponible en la [pagina de Releases de GitHub](https://github.com/JonJonesBR/LylyReader-Android/releases/latest). Este repositorio actualmente genera la version `1.8.0` de la app.

## Roadmap

- Agregar pruebas automatizadas para extraccion de documentos y casos extremos de archivos compartidos.
- Agregar pruebas instrumentadas para las principales pantallas de importacion y conversion.
- Mejorar la resiliencia de conversiones largas con cobertura mas profunda de WorkManager.
- Ampliar los metadatos de la ficha de Play Store para ingles y espanol.

## Créditos

LylyReader se apoya en el trabajo de varios proyectos y comunidades de código abierto:

- **Supertone – Supertonic** (voces neuronales offline): https://github.com/supertone-inc/supertonic
- **Kyutai Labs – Pocket TTS** (voces offline, CC BY 4.0) y el runtime **PocketTTS.cpp** de VolgaGerm (MIT): https://github.com/kyutai-labs/pocket-tts · https://github.com/VolgaGerm/PocketTTS.cpp
- **Kokoro** y **sherpa-onnx** de k2-fsa (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- **ONNX Runtime** de Microsoft (MIT): https://github.com/microsoft/onnxruntime
- **Voces Piper** de los proyectos rhasspy y de la comunidad (cada voz tiene su propia licencia): https://github.com/rhasspy/piper
- **MMS-TTS** de Meta (CC BY-NC 4.0, uso no comercial): https://huggingface.co/facebook/mms-tts-por
- **Chaquopy** – Python en Android (MIT): https://chaquo.com/chaquopy/
- **Voces online de Microsoft Edge** (mediante el cliente no oficial `edge-tts`, LGPL-3.0): https://github.com/rany2/edge-tts
- **Tipografía Literata** de The Literata Project (SIL OFL 1.1): https://github.com/googlefonts/literata
- **Proyecto Gutenberg**, **Wikisource** e **Internet Archive**, las fuentes de dominio público de la búsqueda de libros.
- Paquetes de Python: `aiohttp`, `mutagen`, `Pillow`, `pypdf`, `python-docx`, `ebooklib`, `beautifulsoup4`, `numpy`, `olefile`, `mobi`, `httpx`.
- Bibliotecas de Android de Google y AndroidX (Apache-2.0), y el lenguaje Kotlin de JetBrains (Apache-2.0).

Los detalles completos de atribución de los recursos y modelos incluidos están en [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Licencia

MIT. Consulta [LICENSE](LICENSE).
