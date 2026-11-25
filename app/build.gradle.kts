import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/** Copies the NOTICE file of the repository into the assets of the app. */
abstract class CopyNotices : DefaultTask() {
    @get:InputFile
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        outputDir.get().asFile.resolve("NOTICE").writeText(notice.get().asFile.readText())
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Tailscale tunnel library is built from source in ../tunnel with gomobile.
// Property tunnelTargets overrides the Android targets, for example:
//   gradlew assembleRelease -PtunnelTargets=android/arm64
val tunnelAar = layout.buildDirectory.file("tunnel/tunnel.aar")
val tunnelTargets = providers.gradleProperty("tunnelTargets").orNull ?: "android/arm64,android/amd64"
// The APK lists only the ABIs of the tunnel library. An ABI without the library starts the app and crashes it at the first use of the tunnel.
val tunnelAbis = tunnelTargets.split(',').map { target ->
    when (target.trim()) {
        "android/arm64" -> "arm64-v8a"
        "android/amd64" -> "x86_64"
        else -> error("Unknown tunnel target: $target")
    }
}

val buildTunnelAar = tasks.register<Exec>("buildTunnelAar") {
    group = "build"
    description = "Builds the Tailscale tunnel library from source with gomobile."
    val tunnelDir = rootProject.layout.projectDirectory.dir("tunnel")
    inputs.dir(tunnelDir)
    outputs.file(tunnelAar)
    workingDir(tunnelDir)
    val targets = tunnelTargets
    val aar = tunnelAar.get().asFile
    doFirst {
        aar.parentFile.mkdirs()
    }
    commandLine(
        "gomobile", "bind", "-target=$targets", "-androidapi", "26",
        // No symbol tables in the library. They are not needed on a phone.
        // No log upload: without the logtail feature, the library sends no debug logs to Tailscale.
        "-ldflags=-s -w", "-tags=ts_omit_logtail", "-o", aar.absolutePath, ".",
    )
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
        versionCode = 3
        versionName = "0.1.2"
        ndk {
            abiFilters += tunnelAbis
        }
    }

    signingConfigs {
        // The key stays outside the repository. The owner and CI set these four variables.
        // Without them, the release build makes an APK without a signature.
        create("release") {
            storeFile = System.getenv("HERMES_LITE_KEYSTORE")?.let { file(it) }
            storePassword = System.getenv("HERMES_LITE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HERMES_LITE_KEY_ALIAS")
            keyPassword = System.getenv("HERMES_LITE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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

// The NOTICE of the repository ships in the app as an asset. Settings shows it.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            tasks.register<CopyNotices>("${variant.name}CopyNotices") {
                notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
            },
            CopyNotices::outputDir,
        )
    }
}

dependencies {
    implementation(files(tunnelAar))
    "implementation"(platform("androidx.compose:compose-bom:2026.11.01"))
    "implementation"("androidx.compose.ui:ui")
    "implementation"("androidx.compose.material3:material3")
    // Material3 no longer brings the base icons. The app needs them for its bars and buttons.
    "implementation"("androidx.compose.material:material-icons-core")
    "implementation"("androidx.compose.material:material-icons-extended")
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
