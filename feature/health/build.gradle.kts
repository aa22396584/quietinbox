plugins {
    alias(libs.plugins.quietinbox.android.feature)
}

android {
    namespace = "dev.quietinbox.feature.health"

}

dependencies {
    implementation(project(":platform:storage"))
    implementation(project(":platform:capture"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Kotest specs run on the JUnit Platform, as in the other feature modules.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
