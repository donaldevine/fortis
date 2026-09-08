import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.atomicfu)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
}

// Release signing: put storeFile / storePassword / keyAlias / keyPassword in
// android/keystore.properties (git-ignored). Absent → the release build falls
// back to the debug key, which Play rejects on upload — a loud, safe failure.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    // namespace stays com.fortis.wallet (the source package + generated R/BuildConfig);
    // applicationId is the permanent Play/device identity, reverse-DNS of fortis.rest.
    namespace = "com.fortis.wallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "rest.fortis.wallet"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKeystore) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
    }
}

// Gobley: cross-compile wallet-ffi into jniLibs (ABIs from ndk.abiFilters),
// then generate the Kotlin bindings (package uniffi.wallet_ffi) in library mode.
cargo {
    packageDirectory = layout.projectDirectory.dir("../../crates/wallet-ffi")
}

uniffi {
    generateFromLibrary {
        packageName = "uniffi.wallet_ffi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.biometric)
    // Force fragment forward off biometric-alpha's stale 1.2.5 — that
    // FragmentActivity crashes the Activity Result API (QR scanner) launch with
    // "Can only use lower 16 bits for requestCode".
    implementation(libs.androidx.fragment)
    implementation(libs.okhttp)
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.compose.ui.tooling)
}
