import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Versioning: major.minor.build
//   major = current year - 2025
//   minor = ISO week of the year
//   build = increments per build, resets to 1 each new week
// The build number is derived from the count of commits in the current ISO
// week (so it resets automatically every Monday and grows with each build),
// overridable via the BUILD_VERSION env var. CI must checkout with
// fetch-depth: 0 for the commit count to be accurate.
// ---------------------------------------------------------------------------
val buildDate: LocalDate = LocalDate.now()
val verMajor: Int = (buildDate.year - 2025).coerceAtLeast(0)
val verMinor: Int = buildDate.get(WeekFields.ISO.weekOfWeekBasedYear())

fun commitsThisWeek(): Int = try {
    val monday = buildDate.with(DayOfWeek.MONDAY)
    val process = ProcessBuilder(
        "git", "rev-list", "--count", "--since=${monday}T00:00:00", "HEAD",
    ).directory(rootDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    process.waitFor()
    output.toIntOrNull() ?: 0
} catch (e: Exception) {
    0
}

val verBuild: Int = System.getenv("BUILD_VERSION")?.toIntOrNull() ?: (commitsThisWeek() + 1)

// On a release build, RELEASE_VERSION_NAME (set from the git tag in release.yml)
// pins the APK's version to exactly the tag, so the in-app updater — which
// compares the release tag against the installed version — always agrees with
// what the app reports about itself. Otherwise fall back to the date-based
// computation above.
val releaseOverride: String? = System.getenv("RELEASE_VERSION_NAME")?.trim()?.removePrefix("v")
val computedVersionName: String
val computedVersionCode: Int
if (!releaseOverride.isNullOrEmpty()) {
    computedVersionName = releaseOverride
    val parts = releaseOverride.split(".")
    val maj = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val bld = parts.getOrNull(2)?.toIntOrNull() ?: 0
    computedVersionCode = (maj * 100 + min) * 1000 + bld
} else {
    computedVersionName = "$verMajor.$verMinor.$verBuild"
    // Monotonic across weeks/years as long as build < 1000 and week <= 53.
    computedVersionCode = (verMajor * 100 + verMinor) * 1000 + verBuild
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(envKey: String, propKey: String): String? =
    System.getenv(envKey) ?: keystoreProperties.getProperty(propKey)

android {
    namespace = "com.pokerstats.odds"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pokerstats.odds"
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Where the app looks for updates (owner/repo of the GitHub releases).
        buildConfigField("String", "UPDATE_REPO", "\"vtlinh/Poker-Stats\"")
    }

    val storeFilePath = secret("KEYSTORE_FILE", "storeFile")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = secret("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("KEY_ALIAS", "keyAlias")
                keyPassword = secret("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (storeFilePath != null) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
