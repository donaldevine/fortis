plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.rust.android)
}

// Absolute path to `cargo` — the Gradle daemon often lacks ~/.cargo/bin on PATH.
// Override in gradle.properties:  fortis.cargo=C:/Users/you/.cargo/bin/cargo.exe
val cargoBin = (project.findProperty("fortis.cargo") as String?) ?: "cargo"
val repoRoot = rootDir.parentFile!!

android {
    namespace = "com.fortis.wallet"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fortis.wallet"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }

    // rust-android drops the .so files here; UniFFI Kotlin is generated here
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs/android"))
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi"))

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources.excludes += setOf("/META-INF/AL2.0", "/META-INF/LGPL2.1")
    }
}

cargo {
    module = "../../crates/wallet-ffi"
    libname = "wallet_ffi"
    targets = listOf("arm64", "x86_64")
    profile = "release"
    prebuiltToolchains = true
    pythonCommand = "python"
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
    implementation(libs.okhttp)
    implementation(libs.jna) { artifact { type = "aar" } }
    debugImplementation(libs.compose.ui.tooling)
}

// ---- UniFFI: generate the Kotlin binding from the freshly-built .so ----
val uniffiOut = layout.buildDirectory.dir("generated/uniffi")
val generateUniffi = tasks.register<Exec>("generateUniffiBindings") {
    group = "rust"
    dependsOn("cargoBuild")
    val so = layout.buildDirectory.file("rustJniLibs/android/arm64-v8a/libwallet_ffi.so")
    inputs.file(so).optional()
    outputs.dir(uniffiOut)
    workingDir = repoRoot
    doFirst { uniffiOut.get().asFile.mkdirs() }
    commandLine(
        cargoBin, "run", "-q", "-p", "wallet-ffi", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", so.get().asFile.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiOut.get().asFile.absolutePath,
    )
}

tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    inputs.dir(layout.buildDirectory.dir("rustJniLibs/android"))
    dependsOn("cargoBuild")
}
tasks.named("preBuild").configure { dependsOn(generateUniffi) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateUniffi)
}
