plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.paparazzi) apply false
}

// the whole build-apk suite, as CI runs it: the browser capture (the emulator must be running), the strict logic check,
// the unit tests with the Android capture, the parity against core-ui/parity-baseline.json, and the release APK
tasks.register("build-apk") {
    group = "rdstr"
    description = "Runs the build-apk suite (logic check, tests, visual parity with the emulator) and builds the release APK."
    dependsOn(":core-ui:captureParity", ":core-ui:checkApkLogic", ":core-ui:compareParity", ":core-car:allTests", ":app:assembleDeviceRelease")
    // the unit tests and lint of every module, as `./gradlew test lint` runs them
    dependsOn(subprojects.map { p -> p.tasks.matching { it.name == "test" || it.name == "lint" } })
}
