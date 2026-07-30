import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials come from keystore.properties (gitignored) or, in CI, from
// environment variables. Never commit either. See README → Building a release.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Release builds get their version from the git tag (see .github/workflows/release.yml);
// local builds fall back to these defaults.
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.radiolauncher"
    // Google Play requires targeting API 35 now and API 36 from 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.radiolauncher"
        // Per-flavour minSdk below. A low minSdk doesn't conflict with a high
        // targetSdk — the app still installs on old devices.
        minSdk = 19
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        // Let the support library render vector drawables on pre-Lollipop devices.
        vectorDrawables.useSupportLibrary = true
    }

    // Google Play's installer requires minSdk 21+, but plenty of cheap head units
    // still run Android 4.4. Two flavours keep both audiences:
    //   legacy -> sideloaded APK on GitHub Releases, supports KitKat
    //   play   -> App Bundle for the Play Store, minSdk 21
    // Same applicationId, so a device only ever sees one of them as "the app".
    flavorDimensions += "distribution"
    productFlavors {
        create("legacy") {
            dimension = "distribution"
            minSdk = 19
        }
        create("play") {
            dimension = "distribution"
            minSdk = 21
        }
    }

    signingConfigs {
        // Only registered when credentials are actually available, so cloning the
        // repo and running assembleDebug works with no setup at all.
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Unsigned release builds still succeed; they just can't be installed.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // No applicationIdSuffix on debug: this app registers as HOME, and a
        // separate package would show up as a second launcher and lose settings.
    }

    lint {
        // A broken build should fail CI; style warnings shouldn't.
        abortOnError = false
        checkReleaseBuilds = false
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Keep deps minimal — many cheap units lack Play Services and are slow.
    // These versions are the newest that still support minSdk 19 (KitKat).
    // Do NOT bump to appcompat 1.6+ / core 1.10+ — they require minSdk 21 and
    // will break the KitKat build.
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.recyclerview:recyclerview:1.2.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // Weather uses the keyless Open-Meteo HTTP API + plain LocationManager, so no
    // Google Play Services / Maps SDK is needed — keeps the widest device support.
}
