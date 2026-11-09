plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the key comes from environment variables (GitHub Secrets), never from the repository.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore: Boolean = !keystorePath.isNullOrEmpty() && File(keystorePath).exists()

// Safety net: on GitHub only the signed release may be built. A debug apk is signed with a random
// debug key, so stores (Cafe Bazaar, Myket, Google Play) reject it and phones refuse to update it.
if (System.getenv("GITHUB_ACTIONS") == "true") {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.name == "assembleDebug" || it.name == "bundleDebug" }) {
            throw GradleException(
                "A DEBUG build was requested on GitHub. Stores reject debug-signed apps. " +
                    "Replace .github/workflows/build-apk.yml in your repository with the latest one " +
                    "(GitHub > Add file > Create new file > name it .github/workflows/build-apk.yml) " +
                    "and make sure the 4 signing secrets are set."
            )
        }
    }
}

android {
    namespace = "ir.vibracenter.hamseda"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.vibracenter.hamseda"
        minSdk = 24
        targetSdk = 35
        // Stores and phones need a bigger versionCode for every build (never lower than an installed one):
        // minutes since 1970 always grows, whichever repository / machine builds it.
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
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
    // Cafe Bazaar in-app billing
    implementation("com.github.cafebazaar.Poolakey:poolakey:2.2.0")
}
