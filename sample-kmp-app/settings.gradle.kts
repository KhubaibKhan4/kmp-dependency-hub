pluginManagement {
    // Uses the hub straight from this repo, so `./gradlew kmpHub...` works here too.
    includeBuild("..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SampleKmpApp"
include(":composeApp")
