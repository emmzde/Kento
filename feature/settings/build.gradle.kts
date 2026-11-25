plugins {
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "dev.kento.feature.settings"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:ui"))
    implementation(project(":data"))
    implementation(libs.activity)
    implementation(libs.lifecycle.compose)
    implementation(libs.viewmodel)
    implementation(libs.viewmodel.savedstate)
}
