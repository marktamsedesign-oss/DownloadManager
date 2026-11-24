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

// Release signing key: keystore/release.p12. The build workflow creates it on its first run on main,
// encrypted with the SIGNING_PASSWORD repository secret, which only GitHub knows. Without the password
// (e.g. a local build) the release APK is left unsigned; debug builds use Android's standard debug key.
val signingPassword = System.getenv("SIGNING_PASSWORD") ?: findProperty("signingPassword") as String?
val signingKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) } ?: rootProject.file("keystore/release.p12")

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

    // Same key for every CI build, so each new version (and each OTA update) installs over the last one.
    signingConfigs {
        if (!signingPassword.isNullOrEmpty() && signingKeystore.exists()) {
            create("release") {
                storeFile = signingKeystore
                storeType = "PKCS12"
                storePassword = signingPassword
                keyAlias = "dlmanager"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
