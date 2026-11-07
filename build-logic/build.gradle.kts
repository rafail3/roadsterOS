plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        register("display") {
            id = "rdstr.apk"
            implementationClass = "ro.rdstr.gradle.apk.ApkPlugin"
        }
    }
}
