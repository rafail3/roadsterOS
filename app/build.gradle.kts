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

    // The public AOSP platform key (build/make/target/product/security). Units built with test-keys, as the reference
    // unit, grant an app signed with it the system's signature permissions; on other units it installs as a normal app.
    signingConfigs {
        create("platform") {
            storeFile = rootProject.file("keys/platform.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("platform")
        }
        release {
            signingConfig = signingConfigs.getByName("platform")
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
    implementation(project(":core-map"))
    implementation(project(":core-ui"))
    "simImplementation"(project(":car-sim"))
    "deviceImplementation"(project(":car-vendor"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
