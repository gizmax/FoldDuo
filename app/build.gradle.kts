plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningVariables = listOf(
    "DUO_RELEASE_STORE_FILE",
    "DUO_RELEASE_STORE_PASSWORD",
    "DUO_RELEASE_KEY_ALIAS",
    "DUO_RELEASE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}
val suppliedReleaseSigningVariables = releaseSigningValues.filterValues { it != null }.keys
check(suppliedReleaseSigningVariables.isEmpty() || suppliedReleaseSigningVariables.size == releaseSigningVariables.size) {
    val missing = releaseSigningVariables.filterNot(suppliedReleaseSigningVariables::contains)
    "Release signing is only configured when all four DUO_RELEASE_* variables are set. Missing: ${missing.joinToString()}"
}

val releaseStoreFile = releaseSigningValues["DUO_RELEASE_STORE_FILE"]?.let { configuredPath ->
    rootProject.file(configuredPath).canonicalFile.also { storeFile ->
        val repositoryRoot = rootProject.projectDir.canonicalFile.toPath()
        check(!storeFile.toPath().startsWith(repositoryRoot)) {
            "DUO_RELEASE_STORE_FILE must point outside the repository."
        }
        check(storeFile.isFile && storeFile.canRead()) {
            "DUO_RELEASE_STORE_FILE does not point to a readable file."
        }
    }
}

android {
    // JVM unit tests: android.util.Log etc. return defaults instead of throwing (MorphController logs at info level on paths the tests now reach).
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "cz.pflanzer.foldduo"
    compileSdk = 36
    defaultConfig {
        applicationId = "cz.pflanzer.foldduo"
        minSdk = 33 // RuntimeShader (continuum); Fold 8 runs SDK 37
        targetSdk = 36
        versionCode = 31
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseSigningValues.getValue("DUO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("DUO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("DUO_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":pose"))
    implementation(project(":continuum"))
    implementation("androidx.window:window:1.5.1")
    // B43 "Výkon jako feature": ships app/src/main/baseline-prof.txt to the installed APK and
    // installs it as a compiler profile at first opportunity (background thread, no measurable
    // startup cost of its own) so ART AOT-compiles the hot startup/morph path instead of
    // interpreting it on the first few runs. Debug builds are unaffected: profileinstaller only
    // matters for a build ART actually speed-profiles/dexopts, not the always-interpreted
    // debuggable one, and this dependency does not touch minification (release-only, see below).
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    // Shizuku research spike (docs/research/shizuku.md, shizuku/ package): compile-only today,
    // nothing calls into it yet. api = ShizukuBridge's Shizuku.pingBinder/newProcess calls;
    // provider = ShizukuProvider in the manifest below, how a third-party app is discovered by
    // the Shizuku app to receive the privileged binder.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests of the saved-state / backup encoding (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
