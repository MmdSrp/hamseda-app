plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the key comes from environment variables (GitHub Secrets), never from the repository.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore: Boolean = !keystorePath.isNullOrEmpty() && File(keystorePath).exists()

android {
    namespace = "ir.vibracenter.hamseda"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.vibracenter.hamseda"
        minSdk = 24
        targetSdk = 35
        // Stores need a bigger versionCode for every upload: the GitHub build number is used.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "2.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
}
