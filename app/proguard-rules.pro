# ──────────────────────────────────────────────────────────────────────────────
# ProGuard / R8 rules for LylyReader
# ──────────────────────────────────────────────────────────────────────────────

# ── Chaquopy (Python bridge) ─────────────────────────────────────────────────
# Chaquopy reflectively loads its runtime classes and needs them intact.
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# OnnxSynthBridge is called from Python via Chaquopy reflection.
-keep class com.jonjonesbr.audiobookgen.tts.OnnxSynthBridge {
    public static *;
}

# KokoroSynthBridge is called from Python via Chaquopy reflection (import por
# nome de pacote totalmente qualificado — minificacao renomeia a classe e quebra
# o import Python com "No module named 'com'", reproduzido em release v1.8.4).
-keep class com.jonjonesbr.audiobookgen.tts.KokoroSynthBridge {
    public static *;
}

# PocketSynthBridge is imported by Python/Chaquopy using its package and class names.
-keep class com.jonjonesbr.audiobookgen.tts.PocketSynthBridge {
    public static *;
}

# AndroidSynthBridge/ElevenLabsSynthBridge: mesmo padrao de import Python via
# Chaquopy que quebrou o KokoroSynthBridge acima (mesma causa raiz, faltavam
# aqui desde antes do ciclo do Kokoro) — sem -keep, o mesmo "No module named
# 'com'" ocorreria nos motores android/elevenlabs no pipeline de conversao em
# release.
-keep class com.jonjonesbr.audiobookgen.tts.AndroidSynthBridge {
    public static *;
}
-keep class com.jonjonesbr.audiobookgen.tts.ElevenLabsSynthBridge {
    public static *;
}

# ── AIDL (IPC - SupertonicSynthService isolado) ──────────────────────────────
-keep class com.jonjonesbr.audiobookgen.tts.ISupertonicSynthService { *; }
-keep class com.jonjonesbr.audiobookgen.tts.ISupertonicSynthService$* { *; }

# ── Supertonic TTS (JNI / Rust wrapper) ──────────────────────────────────────
-keep class com.brahmadeo.supertonic.tts.** { *; }
-dontwarn com.brahmadeo.supertonic.tts.**

# ── AIDL (IPC - SherpaSynthService isolado, motor Kokoro) ───────────────────
-keep class com.jonjonesbr.audiobookgen.tts.ISherpaSynthService { *; }
-keep class com.jonjonesbr.audiobookgen.tts.ISherpaSynthService$* { *; }

# ── AIDL (IPC - PocketSynthService isolado) ──────────────────────────────────
-keep class com.jonjonesbr.audiobookgen.tts.IPocketSynthService { *; }
-keep class com.jonjonesbr.audiobookgen.tts.IPocketSynthService$* { *; }
-keep class com.jonjonesbr.audiobookgen.tts.NativePocketTts { *; }

# ── Sherpa-ONNX (JNI / motor Kokoro local) ───────────────────────────────────
# As classes de config (OfflineTtsConfig, OfflineTtsKokoroModelConfig, GenerationConfig
# etc.) sao lidas campo a campo via JNI (GetFieldID/GetObjectField) pelo
# libsherpa-onnx-jni.so — minificacao renomeia/remove campos que o R8 acha "nao usados"
# do lado Kotlin e quebra a leitura nativa (SIGABRT: "JNI DETECTED ERROR ... fid == null
# ... OfflineTts.newFromFile"), reproduzido em release (v1.8.3) e ausente em debug.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ── Room Database ────────────────────────────────────────────────────────────
# Room entities and DAOs — annotation processor generates code that uses reflection.
-keep class com.jonjonesbr.audiobookgen.data.QueueItemEntity { *; }
-keep class com.jonjonesbr.audiobookgen.data.QueueItemDao { *; }
-keep class com.jonjonesbr.audiobookgen.data.LylyDatabase { *; }

# ── AndroidX / Jetpack ───────────────────────────────────────────────────────
# WorkManager
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Security-crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Kotlin Coroutines / Serialization ────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ── Native libraries ────────────────────────────────────────────────────────
# Prevent stripping of JNI-registered native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── Enums (used by Room, QueueStatus, etc.) ──────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── AppWidgetProvider (PlayerWidget - invocado pelo sistema via manifest) ─────
-keep class com.jonjonesbr.audiobookgen.ui.PlayerWidget { *; }

# ── Preserve line numbers for crash reports ──────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Suppress harmless warnings ───────────────────────────────────────────────
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
