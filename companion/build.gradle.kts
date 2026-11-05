plugins {
    alias(libs.plugins.android.application)
}

// same version as the unit app: one number for the whole project
val appVersion = providers.fileContents(rootProject.layout.projectDirectory.file("version.txt")).asText.get().trim()
val (major, minor, patch) = appVersion.split(".").map(String::toInt)

android {
    namespace = "ro.rdstr.cover"
    compileSdk = 36

    defaultConfig {
        applicationId = "ro.rdstr.cover"
        // runs on the driver's phone, not on the unit
        minSdk = 26
        targetSdk = 36
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    implementation(project(":core-car"))
}
