plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Tailscale tunnel library is built from source in ../tunnel with gomobile.
// Property tunnelTargets overrides the Android targets, for example:
//   gradlew assembleRelease -PtunnelTargets=android/arm64
val tunnelAar = layout.buildDirectory.file("tunnel/tunnel.aar")

val buildTunnelAar = tasks.register<Exec>("buildTunnelAar") {
    group = "build"
    description = "Builds the Tailscale tunnel library from source with gomobile."
    val tunnelDir = rootProject.layout.projectDirectory.dir("tunnel")
    inputs.dir(tunnelDir)
    outputs.file(tunnelAar)
    workingDir(tunnelDir)
    val targets = providers.gradleProperty("tunnelTargets").orNull ?: "android/arm64,android/amd64"
    val aar = tunnelAar.get().asFile
    doFirst {
        aar.parentFile.mkdirs()
    }
    commandLine("gomobile", "bind", "-target=$targets", "-androidapi", "26", "-o", aar.absolutePath, ".")
}

android {
    namespace = "com.robbe1912.hermeslite"
    // The Android libraries of 2026 need version 37.1 of the Android APIs.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.robbe1912.hermeslite"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The tunnel library ships these two ABIs: phones and emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(files(tunnelAar))
    "implementation"(platform("androidx.compose:compose-bom:2026.11.01"))
    "implementation"("androidx.compose.ui:ui")
    "implementation"("androidx.compose.material3:material3")
    // Material3 no longer brings the base icons. The app needs them for its bars and buttons.
    "implementation"("androidx.compose.material:material-icons-core")
    "implementation"("androidx.compose.ui:ui-tooling-preview")
    "implementation"("androidx.activity:activity-compose:1.13.0")
    "implementation"("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
    "implementation"("androidx.core:core-ktx:1.19.1")
    "implementation"("androidx.datastore:datastore-preferences:1.3.0")
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    "implementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    "implementation"("com.squareup.okhttp3:okhttp:5.5.1")
    // Push needs Firebase. The app starts it with the project of the user.
    "implementation"("com.google.firebase:firebase-messaging:26.0.2")
    "debugImplementation"("androidx.compose.ui:ui-tooling")
    "testImplementation"("junit:junit:4.13.2")
}

// The tunnel library is an input of every compile step.
tasks.named("preBuild") { dependsOn(buildTunnelAar) }
