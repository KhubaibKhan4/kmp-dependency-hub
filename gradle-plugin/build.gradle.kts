import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.plugin.publish)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_1
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val owner = providers.gradleProperty("kmphub.githubOwner").get()
// Plugin ids are lowercase by convention; the Plugin Portal matches io.github.<login> case-insensitively.
val pluginId = "io.github.${owner.lowercase()}"
val repo = providers.gradleProperty("kmphub.githubRepo").get()

gradlePlugin {
    website = "https://github.com/$owner/$repo"
    vcsUrl = "https://github.com/$owner/$repo.git"
    plugins {
        create("kmpHub") {
            id = pluginId
            displayName = "KMP Dependency Hub"
            description = "Add, search, check and upgrade Kotlin Multiplatform dependencies from the command line: " +
                "places each artifact in the right source set, writes the version catalog, KSP and plugins, and " +
                "refuses libraries that don't support your targets."
            implementationClass = "io.github.kmphub.gradle.KmpHubPlugin"
            tags = listOf("kotlin-multiplatform", "kmp", "dependencies", "version-catalog", "compose-multiplatform", "android")
        }
    }
}

// The engine is bundled into the plugin jar, so the published plugin has no unpublished project dependency.
val bundled: Configuration by configurations.creating

dependencies {
    bundled(project(":core")) { isTransitive = false }
    compileOnly(project(":core"))

    testImplementation(project(":core"))
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    dependsOn(bundled)
    from({ bundled.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
    }
}

// TestKit's plugin classpath comes from the main source set; give it the engine too.
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(bundled)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("kmphub.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("kmphub.pluginId", pluginId)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        if (name == "pluginMaven") artifactId = "kmp-dependency-hub-gradle-plugin"
    }
}
