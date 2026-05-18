plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.clawgui.ng"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clawgui.ng"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"

        vectorDrawables { useSupportLibrary = true }
    }

    // Reuse the debug keystore for release so `./gradlew assembleRelease`
    // produces a signed, installable APK without having to generate / commit a
    // real keystore. Fine for local builds where we just want release-mode
    // perf (no debuggable=true, R8-shrunk). DO NOT ship this to a store.
    signingConfigs {
        getByName("debug") {
            // Android creates ~/.android/debug.keystore on first build — no setup needed.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Shizuku UserService is registered in the manifest but doesn't extend
    // android.app.Service — that's how libshizuku does its UID-shell trick.
    // Lint's `Instantiatable` check doesn't understand this and fails the
    // release build; we disable lintVitalRelease entirely for local convenience.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/NOTICE"
            // BouncyCastle jdk18on jars all carry these — keep only one copy
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**/OSGI-INF/**"
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-graphics")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // Coroutines + serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // HTTP + SSE
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Image (Coil for avatars / attachments)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Wireless-debugging bootstrap (let ng start Shizuku server without USB ADB)
    // libadb-android transitively pulls bcprov-jdk15to18 which collides with
    // the jdk18on artifacts that actually expose `operator.jcajce.OperatorHelper`
    // at runtime on Android. Force-exclude the legacy one, use jdk18on directly.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk15to18")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk15to18")
    }
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    // Bundled Conscrypt — Android 14+ stripped `Conscrypt.exportKeyingMaterial`
    // off the system one, and libadb-android's SPAKE2-derived ADB session
    // key absolutely needs it. Bringing our own Conscrypt fully owns the TLS
    // stack and unblocks pairing. ~3 MB extra per ABI.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Token counting / cron / Feishu — kept compatible with legacy runtime port
    implementation("com.knuddels:jtokkit:0.6.1")
    implementation("com.cronutils:cron-utils:9.2.1")
    implementation("com.larksuite.oapi:oapi-sdk:2.4.16")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
