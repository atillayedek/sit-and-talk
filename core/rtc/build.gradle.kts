plugins {
    alias(libs.plugins.sitandtalk.android.library)
    alias(libs.plugins.sitandtalk.android.hilt)
}

dependencies {
    implementation(project(":core:model"))
    api(libs.agora.rtc)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
}
