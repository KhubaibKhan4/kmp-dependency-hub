plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.plugin.publish) apply false
}

allprojects {
    // Plugin Portal and Maven require a group you own; io.github.<owner> is verified through GitHub.
    group = "io.github." + providers.gradleProperty("kmphub.githubOwner").get().lowercase()
    version = providers.gradleProperty("kmphub.version").get()
}
