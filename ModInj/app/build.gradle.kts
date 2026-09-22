import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zack694.modinj"
    compileSdk = 36

    // Release signing priority:
    //  1. CI ("Android CI ModInj" workflow) injects -Pandroid.injected.signing.*
    //     pointing at the decoded movtery-key.jks (RELEASE_KEYSTORE + secrets) —
    //     AGP applies those to release builds automatically on top of this block.
    //  2. Inside the ZalithLauncher repo (temporary source inclusion), fall back
    //     to the launcher's committed debug.keystore so local/PR builds still sign.
    //  3. Standalone dev checkout: keystore/debug.keystore next to this project.
    signingConfigs {
        val injectedStore = (findProperty("android.injected.signing.store.file") as String?)?.let { File(it) }
        val fallbackStore = sequenceOf(
            rootProject.file("../ZalithLauncher/debug.keystore"),
            rootProject.file("keystore/debug.keystore")
        ).firstOrNull { it.isFile }

        val store = injectedStore?.takeIf { it.isFile } ?: fallbackStore
        if (store != null) {
            create("release") {
                storeFile = store
                storePassword = (findProperty("android.injected.signing.store.password") as String?)
                    ?: (if (injectedStore != null) null else "android")
                    ?: "android"
                keyAlias = (findProperty("android.injected.signing.key.alias") as String?)
                    ?: (if (injectedStore != null) null else "androiddebugkey")
                    ?: "androiddebugkey"
                keyPassword = (findProperty("android.injected.signing.key.password") as String?)
                    ?: storePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.zack694.modinj"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        getByName("debug") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
