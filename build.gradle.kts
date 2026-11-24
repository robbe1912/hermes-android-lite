// AGP 9 has built-in Kotlin support. Do not add the kotlin-android plugin.
plugins {
    id("com.android.application") version "9.5.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
