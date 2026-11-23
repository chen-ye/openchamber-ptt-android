plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.openchamber.ptt"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openchamber.ptt"
        minSdk = 29
        targetSdk = 35
        val configuredVersionCode = providers.gradleProperty("versionCode")
            .orElse(providers.environmentVariable("OPENCHAMBER_VERSION_CODE"))
            .orNull
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
        val configuredVersionName = providers.gradleProperty("versionName")
            .orElse(providers.environmentVariable("OPENCHAMBER_VERSION_NAME"))
            .orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "0.1.0"
        versionCode = configuredVersionCode
        versionName = configuredVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseKeystorePath = providers.gradleProperty("keystorePath")
        .orElse(providers.environmentVariable("OPENCHAMBER_KEYSTORE_PATH"))
        .orNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val releaseKeystorePassword = providers.gradleProperty("keystorePassword")
        .orElse(providers.environmentVariable("OPENCHAMBER_KEYSTORE_PASSWORD"))
        .orNull
    val releaseKeyAlias = providers.gradleProperty("keyAlias")
        .orElse(providers.environmentVariable("OPENCHAMBER_KEY_ALIAS"))
        .orNull
    val releaseKeyPassword = providers.gradleProperty("keyPassword")
        .orElse(providers.environmentVariable("OPENCHAMBER_KEY_PASSWORD"))
        .orNull
    val hasReleaseSigning = releaseKeystorePath != null &&
        file(releaseKeystorePath).isFile &&
        !releaseKeystorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-telecom:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.media:media:1.7.0")
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("com.google.mlkit:genai-text-to-speech:1.0.0-alpha1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
