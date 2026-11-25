plugins {
    alias(libs.plugins.android.lib)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "dev.kento.core.text"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(libs.junit)
}
