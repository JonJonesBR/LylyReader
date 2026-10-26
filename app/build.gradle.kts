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

// Caminho (variável de ambiente) de um .properties com storeFile/storePassword/keyAlias/keyPassword
// da chave fixa do debug. No CI é montado a partir de segredos; localmente fica em ~/.lylyreader-keys.
val debugSigningProps = Properties()
val hasDebugSigningProps = System.getenv("LYLY_DEBUG_SIGNING_PROPS")
    ?.let { File(it) }
    ?.takeIf { it.isFile }
    ?.also { arquivo -> FileInputStream(arquivo).use { debugSigningProps.load(it) } }
    ?.let {
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { chave ->
            debugSigningProps[chave] != null && File(debugSigningProps["storeFile"] as String).isFile
        }
    } ?: false

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
        // Chave fixa do APK debug (fora do Git): mesma assinatura em toda versão, para o usuário
        // instalar a nova por cima da antiga. Sem o arquivo, o Gradle usa a chave debug padrão.
        if (hasDebugSigningProps) {
            create("debugFixa") {
                storeFile = File(debugSigningProps["storeFile"] as String)
                storePassword = debugSigningProps["storePassword"] as String
                keyAlias = debugSigningProps["keyAlias"] as String
                keyPassword = debugSigningProps["keyPassword"] as String
            }
        }
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
            if (hasDebugSigningProps) {
                signingConfig = signingConfigs.getByName("debugFixa")
            }
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
        // Pacotes Python puros com versão fixa: cada release embute exatamente o mesmo código, e uma mudança
        // do upstream (ex.: edge-tts, que segue um serviço não oficial) só entra quando a versão for trocada aqui.
        // aiohttp, Pillow e numpy têm partes nativas e vêm do repositório do Chaquopy, que só oferece algumas
        // versões por Python: ficam sem fixar para o Chaquopy escolher a compatível.
        pip {
            install("edge-tts==7.3.1")
            install("aiohttp")
            install("mutagen==1.48.1")
            install("Pillow")
            install("pypdf==6.21.2")
            install("python-docx==1.2.0")
            install("ebooklib==0.20")
            install("beautifulsoup4==4.15.0")
            install("httpx==0.28.1")
            install("numpy")                 // Requerido pelos motores ONNX locais
            install("olefile==0.47")         // Leitura do container OLE2 dos .doc (Word 97-2003)
            install("mobi==0.4.1")           // Desempacotamento de .mobi/.azw3 (Kindle) em EPUB/HTML
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
    // Algumas dependências .aar trazem classes.jar compilado com Kotlin 2.1+. Permite ler
    // metadata mais nova que a versão do compilador Kotlin do projeto (1.9.25).
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
