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
include(":core-netscan")
include(":core-devicecontrol")
include(":driver-haismart")
include(":driver-gree")
include(":driver-midea")
include(":app")

