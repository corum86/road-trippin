import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

/** KEY=value lines of a properties-style file; empty when the file is absent. */
fun readProperties(path: String): Properties =
    Properties().apply {
        val file = rootProject.file(path)
        if (file.isFile) file.inputStream().use { load(it) }
    }

// The Gemini key comes from local.properties (gemini.apiKey), the GEMINI_API_KEY
// environment variable, or the web app's .env (VITE_GEMINI_API_KEY), in that
// order. Like the web build, it ends up inside the app.
val geminiApiKey: String =
    readProperties("local.properties").getProperty("gemini.apiKey")
        ?: System.getenv("GEMINI_API_KEY")
        ?: readProperties("../.env").getProperty("VITE_GEMINI_API_KEY")?.let(::unquoted)
        ?: ""

// Where /api/data lives: the deployed web app (see api/data.ts in the repo root).
val cloudApiBaseUrl: String =
    (findProperty("vacationmap.apiBaseUrl") as String?) ?: "https://road-trippin-six.vercel.app"

/** A .env value as dotenv reads it: surrounding quotes are not part of the value. */
fun unquoted(value: String): String {
    val trimmed = value.trim()
    val quote = trimmed.firstOrNull()
    return if (trimmed.length >= 2 && (quote == '"' || quote == '\'') && trimmed.last() == quote) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.corum86.vacationmap"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.corum86.vacationmap"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GEMINI_API_KEY", quoted(geminiApiKey.trim()))
        buildConfigField("String", "CLOUD_API_BASE_URL", quoted(cloudApiBaseUrl.trim().trimEnd('/')))
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // the seed data is the web app's: one copy for both
        getByName("main").assets.srcDir("../../public/data")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // Robolectric reaches into the JDK to emulate recent Android versions
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // the screenshot tests write their images to build/outputs/roborazzi
                it.systemProperty("roborazzi.test.record", "true")
                // -PrealTiles draws the screenshots over real OpenStreetMap tiles (needs network)
                it.systemProperty("vacationmap.realTiles", (findProperty("realTiles") != null).toString())
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
