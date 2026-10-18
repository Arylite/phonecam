plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "re.isx.phonecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "re.isx.phonecam"
        // Android 11: encoder low-latency mode and the WindowInsets API.
        minSdk = 30
        targetSdk = 36
        // CI passes the release tag (v1.2.3 -> 1.2.3) and the run number.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    // Release builds are signed with the keystore given in the environment (CI), else with the
    // debug key, which the build creates when missing (forks and local builds).
    val keystore = System.getenv("PHONECAM_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("PHONECAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PHONECAM_KEY_ALIAS")
                keyPassword = System.getenv("PHONECAM_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        // Version nags would break the build whenever a new SDK or library ships.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        // Suggests androidx core-ktx helpers; not worth a dependency for one call.
        disable += "UseKtx"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
}
