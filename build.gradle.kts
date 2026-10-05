// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("com.android.library") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.0" apply false
    id("com.chaquo.python") version "17.0.0" apply false
    id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
