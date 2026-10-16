pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kmp-dependency-hub"

include(":core")
include(":gradle-plugin")

// The IDE plugin builds against a local Android Studio / IntelliJ install (see gradle.properties).
// Pass -Pkmphub.skipIdea=true to build only the engine and the Gradle plugin.
if (providers.gradleProperty("kmphub.skipIdea").orNull != "true") {
    include(":idea-plugin")
}
