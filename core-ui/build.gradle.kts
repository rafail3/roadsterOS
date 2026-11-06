plugins {
    alias(libs.plugins.android.library)
    id("rdstr.display")
}

android {
    namespace = "ro.rdstr.ui"
    compileSdk = 36
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// the generator's own tests run with this module's
tasks.matching { it.name == "test" }.configureEach { dependsOn(gradle.includedBuild("build-logic").task(":test")) }
