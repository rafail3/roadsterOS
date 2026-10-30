plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ro.rdstr.os"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.rdstr.os"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "target"
    productFlavors {
        create("sim") { dimension = "target" }
        create("device") { dimension = "target" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core-car"))
    implementation(project(":core-ui"))
    "simImplementation"(project(":car-sim"))
    "deviceImplementation"(project(":car-vendor"))
    implementation(libs.coroutines.android)
}
