plugins {
    alias(libs.plugins.sitandtalk.android.library)
    alias(libs.plugins.sitandtalk.android.compose)
}

dependencies {
    implementation(project(":core:model"))
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
