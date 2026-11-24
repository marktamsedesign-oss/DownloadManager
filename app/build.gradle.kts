plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionCode / -PversionName (from the run number) so every build can update the last one.
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull()
val ciVersionName = findProperty("versionName") as String?
// Where the app looks for OTA updates: the latest GitHub release of this repository.
val updateRepo = (findProperty("updateRepo") as String?)
    ?: System.getenv("GITHUB_REPOSITORY")
    ?: "marktamsedesign-oss/DownloadManager"

android {
    namespace = "com.markt.dlmanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.markt.dlmanager"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0"
        buildConfigField("String", "UPDATE_URL", "\"https://github.com/$updateRepo/releases/latest/download/update.json\"")
    }

    // Fixed key so every build (local or GitHub Actions) can update the installed app.
    signingConfigs {
        create("dlm") {
            storeFile = rootProject.file("keystore/dlmanager.jks")
            storePassword = "dlmanager"
            keyAlias = "dlmanager"
            keyPassword = "dlmanager"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("dlm")
        }
        debug {
            signingConfig = signingConfigs.getByName("dlm")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
