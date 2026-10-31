plugins {
    alias(libs.plugins.android.application)
}

// version.txt is rewritten by release-please; versionCode follows it so there is one number to keep.
val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("version.txt")).asText.get().trim()
val (major, minor, patch) = appVersion.split(".").map(String::toInt)

android {
    namespace = "ro.rdstr.os"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.rdstr.os"
        minSdk = 27
        targetSdk = 27
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
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
        disable += listOf(
            "ExpiredTargetSdkVersion", "OldTargetApi",
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
        )
    }
}

dependencies {
    implementation(project(":core-car"))
    implementation(project(":core-ui"))
    "simImplementation"(project(":car-sim"))
    "deviceImplementation"(project(":car-vendor"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
