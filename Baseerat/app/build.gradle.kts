import java.net.HttpURLConnection
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


// ---------------------------------------------------------------------------------------------
// Optional native libraries, auto-detected at build time:
//   * Tesseract4Android  -> Urdu / Arabic OCR
//   * MediaPipe tasks-genai -> offline chat models (.task)
// The app talks to both through reflection, so it still compiles and runs if one is missing.
// Kill switch:  optionalLibs=false  in gradle.properties.
// Force a coordinate:  tesseractDep=group:artifact:version   /   genaiDep=group:artifact:version
// ---------------------------------------------------------------------------------------------
fun urlExists(url: String, timeoutMs: Int = 8000): Boolean = try {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "HEAD"
    c.connectTimeout = timeoutMs
    c.readTimeout = timeoutMs
    c.instanceFollowRedirects = true
    val ok = c.responseCode in 200..299
    c.disconnect()
    ok
} catch (e: Exception) {
    false
}

fun pomUrl(base: String, group: String, artifact: String, version: String): String =
    "$base/${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom"

fun pickFrom(base: String, group: String, artifact: String, versions: List<String>, timeoutMs: Int = 8000): String? {
    for (v in versions) {
        if (urlExists(pomUrl(base, group, artifact, v), timeoutMs)) return "$group:$artifact:$v"
    }
    return null
}

val optionalLibs: Boolean = providers.gradleProperty("optionalLibs").orNull != "false"
val mavenCentralBase = "https://repo.maven.apache.org/maven2"
val googleMavenBase = "https://dl.google.com/dl/android/maven2"
val jitpackBase = "https://jitpack.io"

val tesseractDep: String? = providers.gradleProperty("tesseractDep").orNull
    ?: if (optionalLibs) {
        pickFrom(mavenCentralBase, "cz.adaptech.tesseract4android", "tesseract4android", listOf("4.9.0", "4.8.1", "4.8.0", "4.7.0", "4.6.0"))
            ?: pickFrom(mavenCentralBase, "io.github.adaptech-cz", "tesseract4android", listOf("4.9.0", "4.8.0", "4.7.0"))
            ?: pickFrom(jitpackBase, "com.github.adaptech-cz.Tesseract4Android", "tesseract4android", listOf("4.9.0", "4.8.0", "4.7.0"), 60000)
    } else null

val genaiDep: String? = providers.gradleProperty("genaiDep").orNull
    ?: if (optionalLibs) {
        pickFrom(mavenCentralBase, "com.google.mediapipe", "tasks-genai", listOf("0.10.24", "0.10.22", "0.10.21", "0.10.20", "0.10.14"))
            ?: pickFrom(googleMavenBase, "com.google.mediapipe", "tasks-genai", listOf("0.10.24", "0.10.22", "0.10.21", "0.10.20", "0.10.14"))
    } else null

logger.lifecycle("Baseerat optional lib - Tesseract (Urdu OCR): ${tesseractDep ?: "NOT FOUND -> Urdu OCR disabled"}")
logger.lifecycle("Baseerat optional lib - MediaPipe GenAI (chat models): ${genaiDep ?: "NOT FOUND -> chat models disabled"}")


// ---- Optional release signing (set these env vars in CI / locally) ----
// BASEERAT_KEYSTORE (path), BASEERAT_KS_PASS, BASEERAT_KEY_ALIAS, BASEERAT_KEY_PASS
// If they are absent, the "stable" release APK is signed with the debug key so it is still installable.
val releaseKeystorePath: String? = System.getenv("BASEERAT_KEYSTORE")
val hasReleaseKeystore: Boolean = releaseKeystorePath != null && File(releaseKeystorePath).exists()

android {
    namespace = "com.baseerat.vision"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baseerat.vision"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = System.getenv("BASEERAT_KS_PASS")
                keyAlias = System.getenv("BASEERAT_KEY_ALIAS")
                keyPassword = System.getenv("BASEERAT_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            // Minification is intentionally OFF: ML Kit / Tesseract use reflection + JNI and
            // an untested R8 config is the most common cause of release-only crashes.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Camera
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    // EXIF rotation when decoding gallery / shared images
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ML Kit (all bundled models => fully offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("com.google.mlkit:image-labeling-custom:17.0.2") // imported .tflite classifiers
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Optional libraries chosen by the probing code at the top of this file
    if (!tesseractDep.isNullOrBlank()) {
        implementation(tesseractDep)
    }
    if (!genaiDep.isNullOrBlank()) {
        implementation(genaiDep)
    }

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
