pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sit-and-talk"

include(":app")

include(":core:designsystem")
include(":core:model")
include(":core:network")
include(":core:database")
include(":core:security")
include(":core:rtc")
include(":core:data")

include(":feature:auth")
include(":feature:profile")
include(":feature:matching")
include(":feature:call")
include(":feature:rooms")
include(":feature:friends")
include(":feature:chat")
include(":feature:feed")
include(":feature:notifications")
include(":feature:wallet")
include(":feature:settings")
include(":feature:moderation")
