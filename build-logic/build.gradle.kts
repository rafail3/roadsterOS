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
            id = "rdstr.display"
            implementationClass = "ro.rdstr.gradle.display.DisplayPlugin"
        }
    }
}
