// The unit's map: vector tile decoding, the shared style and the drawing of a tile, in plain Kotlin.
// The platform only supplies the canvas (android.graphics on the unit, java.awt for the previews in the tests).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    // the preview test draws real tiles when they were exported to build/preview-tiles; it is skipped otherwise
    systemProperty("preview.tiles", layout.buildDirectory.dir("preview-tiles").get().asFile.path)
    systemProperty("preview.out", layout.buildDirectory.file("preview").get().asFile.path)
}
