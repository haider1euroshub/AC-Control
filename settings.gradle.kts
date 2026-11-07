pluginManagement {
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

rootProject.name = "RoomAcController"

include(":core-domain")
include(":core-protocol")
include(":driver-haismart")
include(":driver-smartair2")
include(":driver-hon")
include(":app")

