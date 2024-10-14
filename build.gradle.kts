plugins {
    alias(libs.plugins.androidGradleApplication) apply false
    alias(libs.plugins.androidGradleLibrary) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kover) apply false
}

val clean by tasks.registering(Delete::class) {
    delete(rootProject.buildDir)
}
