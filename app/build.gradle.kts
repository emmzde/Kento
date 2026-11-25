import java.util.Properties

plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
// Release signing lives outside the repository: KENTO_KEYSTORE or ~/.kento/keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword). Without it the release build stays unsigned.
val releaseKeys = Properties().apply {
    val file = System.getenv("KENTO_KEYSTORE")?.let(::File) ?: File(System.getProperty("user.home"), ".kento/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.kento.app"
    compileSdk = 36
    ndkVersion = "26.3.11579264"
    defaultConfig {
        minSdk = 26
        applicationId = "dev.kento.app"
        targetSdk = 36
        versionCode = 11
        versionName = "0.6.0"
        testInstrumentationRunner = "dev.kento.app.KentoTestRunner"
    }
    packaging {
        // PDFBox pulls BouncyCastle for encrypted PDFs; its post-quantum parameter tables are never used.
        resources.excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties")
        // Compressed native libraries keep the download small; Android extracts them once at install.
        jniLibs.useLegacyPackaging = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (releaseKeys.getProperty("storeFile") != null) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("data/schemas"))
    buildTypes {
        // Phones are ARM; x86_64 is kept only for emulator runs. 32-bit x86 is dropped everywhere.
        debug { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            matchingFallbacks += "debug"
        }
        release {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        create("preview") {
            initWith(getByName("release"))
            // The preview build is what Kento.cmd installs on the x86_64 emulator; it also runs on 64-bit phones.
            ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "x86_64") }
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("data/build/whisper-source/whisper.cpp-1.7.6/samples"))
    testBuildType = "qa"
}
tasks.matching { it.name == "mergeQaAndroidTestAssets" }.configureEach { dependsOn(":data:prepareOfflineMedia") }
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:text"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation("androidx.work:work-runtime-ktx:2.10.5")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(libs.activity)
    implementation(libs.lifecycle.compose)
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.savedstate)
    implementation(project(":feature:chat"))
    implementation(project(":feature:settings"))
}
