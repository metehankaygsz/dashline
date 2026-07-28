plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radiolauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.radiolauncher"
        // Old head units can run Android 4.4 (KitKat). API 19 is also the lowest
        // minSdk that the current Android Gradle Plugin (8.x) supports, so this is
        // as backwards-compatible as this toolchain allows. Covers ~99% of Android
        // head units in the wild.
        minSdk = 19
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Let the support library render vector drawables on pre-Lollipop devices.
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
