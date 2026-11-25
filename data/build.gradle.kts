plugins {
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
apply(from = rootProject.file("gradle/media-assets.gradle.kts"))
android {
    ndkVersion = "26.3.11579264"
    externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/mediaAssets"))
    androidResources.noCompress += "bin"
    namespace = "dev.kento.data"
    compileSdk = 36
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        minSdk = 26
        externalNativeBuild.cmake.arguments += listOf("-DANDROID_STL=c++_static")
        // Matches the ABIs the app packages; skips compiling whisper.cpp for 32-bit x86.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":core:text"))
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    api(libs.json)
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    api(libs.okhttp)
    api(libs.coroutines)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}
