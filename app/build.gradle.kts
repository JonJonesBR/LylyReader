import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.chaquo.python")
    id("io.gitlab.arturbosch.detekt")
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword",
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}

val versionCodeOverrideValue = providers.gradleProperty("versionCodeOverride").orNull
val versionCodeOverride = versionCodeOverrideValue?.toIntOrNull()
    ?: versionCodeOverrideValue?.let {
        throw GradleException("versionCodeOverride must be a valid integer.")
    }
val versionNameOverride = providers.gradleProperty("versionNameOverride").orNull
val chaquopyBuildPythonOverride = providers.gradleProperty("chaquopyBuildPython").orNull

android {
    namespace = "com.jonjonesbr.audiobookgen"
    compileSdk = 36

    lint {
        checkReleaseBuilds = false
    }

    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.jonjonesbr.audiobookgen"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeOverride ?: 226
        versionName = versionNameOverride ?: "1.8.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += arrayOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                targets += listOf("pockettts_jni")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // O build de teste convive com a release instalada (outro id, outros dados): testar no aparelho
            // sem desinstalar a versão assinada de uso diário.
            applicationIdSuffix = ".debug"
        }
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            resources.excludes.add("META-INF/kotlinx_coroutines_core.version")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nonJvmMain/default/linkdata/module")
            resources.pickFirsts.add("nativeMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("nativeMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/root_package/0_.knm")
            resources.pickFirsts.add("commonMain/default/linkdata/module")
            resources.pickFirsts.add("commonMain/default/linkdata/package_androidx/0_androidx.knm")
            resources.pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")
            resources.merges.add("commonMain/default/manifest")
            resources.merges.add("nonJvmMain/default/manifest")
            resources.merges.add("nativeMain/default/manifest")
        }
    }

}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        chaquopyBuildPythonOverride?.let { buildPython(it) }
        pip {
            install("edge-tts")
            install("aiohttp")
            install("mutagen")
            install("Pillow")
            install("pypdf")
            install("python-docx")
            install("ebooklib")
            install("beautifulsoup4")
            install("httpx")
            install("numpy")                 // Requerido pelos motores ONNX locais
            install("olefile")               // Leitura do container OLE2 dos .doc (Word 97-2003)
            install("mobi")                  // Desempacotamento de .mobi/.azw3 (Kindle) em EPUB/HTML
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
    // Algumas dependências .aar trazem classes.jar compilado com Kotlin 2.1+. Permite ler
    // metadata mais nova que a versão do compilador Kotlin do projeto (1.9.0).
    kotlinOptions.freeCompilerArgs += listOf("-Xskip-metadata-version-check")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.interpolator:interpolator:1.0.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    kapt("androidx.room:room-compiler:2.7.0")

    // Motor TTS neural local Kokoro-82M via Sherpa-ONNX (aprovação explícita do usuário,
    // 2026-09-02 — ver PLANO_MELHORIAS_V6). Variante static-link-onnxruntime: onnxruntime
    // embutido no libsherpa-onnx-jni.so (sem libonnxruntime.so avulso — sem conflito de soname
    // com o ORT 1.26 dedicado do Supertonic; ver LylyApplication). AAR oficial k2-fsa v1.13.7,
    // baixado do GitHub Releases (não existe no Maven Central). abiFilters (arm64-v8a, x86_64)
    // já limitam o packaging.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.7.aar"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Versão alinhada com kotlinx-coroutines-android:1.7.3 (implementation acima).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.12")
    androidTestImplementation("androidx.room:room-testing:2.7.0")
}

// Analise estatica (guardrail p/ a refatoracao incremental em andamento). O baseline registra
// os achados existentes na hora da introducao para nao travar o build por debito ja conhecido;
// so falha em issues NOVAS a partir daqui. Rodar `./gradlew :app:detektBaseline` para regenerar
// o baseline apos absorver debito de proposito (nunca pra "silenciar" issue nova).
detekt {
    buildUponDefaultConfig = true
    allRules = false
    baseline = file("$rootDir/detekt-baseline.xml")
}
