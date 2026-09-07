plugins {
    alias(libs.plugins.quietinbox.android.library)
    alias(libs.plugins.quietinbox.android.hilt)
}

android {
    namespace = "dev.quietinbox.platform.media"

}

dependencies {
    api(project(":core:model"))
    implementation(project(":platform:crypto"))
    implementation(project(":platform:storage"))
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.mockk)
    androidTestImplementation(project(":core:testing"))
}

// Kotest specs run on the JUnit Platform, as in the other platform modules.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}
