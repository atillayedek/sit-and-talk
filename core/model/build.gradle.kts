plugins {
    alias(libs.plugins.sitandtalk.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
