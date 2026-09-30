import java.util.Properties

plugins {
    alias(libs.plugins.sitandtalk.android.application)
    alias(libs.plugins.sitandtalk.android.compose)
    alias(libs.plugins.sitandtalk.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// Public client configuration comes from local.properties (developer machines) or
// environment variables (CI). Server secrets never belong here — see docs/security.md.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

// Public defaults for the project's own backend; never secrets (see the file header).
val publicDefaults = Properties().apply {
    val file = file("public-config.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun rawConfig(name: String): String =
    localProps.getProperty(name)?.trim().orEmpty()
        .ifEmpty { System.getenv(name)?.trim().orEmpty() }
        .ifEmpty { publicDefaults.getProperty(name)?.trim().orEmpty() }

// SUPABASE_ANON_KEY is accepted as a legacy alias of the publishable key.
fun configValue(name: String): String = when (name) {
    "SUPABASE_PUBLISHABLE_KEY" -> rawConfig(name).ifEmpty { rawConfig("SUPABASE_ANON_KEY") }
    else -> rawConfig(name)
}

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val publicConfigKeys = listOf(
    "SUPABASE_URL",
    "SUPABASE_PUBLISHABLE_KEY",
    "AGORA_APP_ID",
    "AUTH_REDIRECT_URL",
    "APP_LINK_HOST",
    "GOOGLE_WEB_CLIENT_ID",
    "FIREBASE_PROJECT_ID",
    "FIREBASE_APP_ID",
    "FIREBASE_API_KEY",
    "FIREBASE_SENDER_ID",
    "SUPPORT_URL",
    "PRIVACY_URL",
    "TERMS_URL",
    "COMMUNITY_URL",
)

val releaseStoreFile = configValue("SITANDTALK_KEYSTORE_FILE")
val hasReleaseSigning = releaseStoreFile.isNotEmpty() && file(releaseStoreFile).exists()

android {
    namespace = "com.sitandtalk.app"

    defaultConfig {
        applicationId = "com.sitandtalk.app"
        versionCode = (configValue("VERSION_CODE").ifEmpty { "1" }).toInt()
        versionName = configValue("VERSION_NAME").ifEmpty { "0.1.0" }

        publicConfigKeys.forEach { key ->
            buildConfigField("String", key, quoted(configValue(key)))
        }
        val redirect = configValue("AUTH_REDIRECT_URL").ifEmpty { "sitandtalk://auth-callback" }
        manifestPlaceholders["authScheme"] = redirect.substringBefore("://")
        manifestPlaceholders["authHost"] = redirect.substringAfter("://").substringBefore('/')
        manifestPlaceholders["appLinkHost"] = configValue("APP_LINK_HOST").ifEmpty { "invalid.sitandtalk.local" }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = configValue("SITANDTALK_KEYSTORE_PASSWORD")
                keyAlias = configValue("SITANDTALK_KEY_ALIAS")
                keyPassword = configValue("SITANDTALK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

// A release build without real configuration must fail loudly instead of shipping a broken app.
val verifyReleaseConfig by tasks.registering {
    doLast {
        val required = listOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY", "AGORA_APP_ID")
        val missing = required.filter { configValue(it).isEmpty() }
        if (missing.isNotEmpty()) {
            throw GradleException("Release build requires: ${missing.joinToString()} (see docs/release.md)")
        }
        if (!hasReleaseSigning) {
            throw GradleException("Release build requires SITANDTALK_KEYSTORE_FILE and signing variables (see docs/release.md)")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfig) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:rtc"))
    implementation(project(":core:security"))
    implementation(project(":core:network"))

    implementation(project(":feature:auth"))
    implementation(project(":feature:profile"))
    implementation(project(":feature:matching"))
    implementation(project(":feature:call"))
    implementation(project(":feature:rooms"))
    implementation(project(":feature:friends"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:feed"))
    implementation(project(":feature:notifications"))
    implementation(project(":feature:wallet"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:moderation"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
