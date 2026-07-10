plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.reverb.source.universal"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(libs.versions.jdk.get().toInt())
    }
}

dependencies {
    implementation(project(":source-api"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(project(":core:html"))
    implementation(project(":core:video"))
    implementation(project(":adblock"))
    implementation(libs.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
