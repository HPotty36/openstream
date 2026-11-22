plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.openstream"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.openstream"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes {
        // A separate app id, so a debug build never touches the release install's data.
        debug { applicationIdSuffix = ".debug" }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses Java APIs that older Android versions lack.
        isCoreLibraryDesugaringEnabled = true
    }
    testOptions {
        unitTests.all {
            // -Plive=true also runs the checks that talk to the real services.
            it.systemProperty("live", providers.gradleProperty("live").orNull ?: "false")
            it.testLogging.showStandardStreams = true
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // GPL-3.0. Reads YouTube without an account or an API key.
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    testImplementation("junit:junit:4.13.2")
}
