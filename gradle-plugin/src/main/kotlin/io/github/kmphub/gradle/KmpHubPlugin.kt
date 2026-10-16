package io.github.kmphub.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Apply to the root `build.gradle.kts`:
 *
 * ```
 * plugins { id("io.github.khubaibkhan4") }
 * ```
 *
 * Tasks: `kmpHubList`, `kmpHubSearch`, `kmpHubModules`, `kmpHubAdd`, `kmpHubDoctor`.
 */
class KmpHubPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("Apply io.github.kmphub to the root build.gradle.kts, not ${project.path}.")
        }
        // Read once every project is configured; the result is a plain string, so it's configuration-cache safe.
        val modules = project.provider { ModuleCodec.encode(project.allprojects.map { ModelReader.read(it) }) }
        val rootDir = project.layout.projectDirectory
        val versionCache = project.layout.file(project.provider { File(project.gradle.gradleUserHomeDir, "kmphub/versions.json") })
        val offline = project.gradle.startParameter.isOffline
        val live: (LiveHubTask) -> Unit = {
            it.versionCache.set(versionCache)
            it.offline.set(offline)
        }

        project.tasks.register("kmpHubList", KmpHubListTask::class.java) {
            it.group = GROUP
            it.description = "Lists curated libraries with their latest versions."
            live(it)
        }
        project.tasks.register("kmpHubSearch", KmpHubSearchTask::class.java) {
            it.group = GROUP
            it.description = "Searches every library on Maven Central and Google Maven: ./gradlew kmpHubSearch --query=ktor"
            live(it)
        }
        project.tasks.register("kmpHubModules", KmpHubModulesTask::class.java) {
            it.group = GROUP
            it.description = "Shows each module's kind and Kotlin targets as the hub sees them."
            it.modules.set(modules)
        }
        project.tasks.register("kmpHubAdd", KmpHubAddTask::class.java) {
            it.group = GROUP
            it.description = "Adds libraries to a module: ./gradlew kmpHubAdd --lib=ktor-client,koin --module=:composeApp"
            it.modules.set(modules)
            it.rootDirectory.set(rootDir)
            live(it)
        }
        project.tasks.register("kmpHubDoctor", KmpHubDoctorTask::class.java) {
            it.group = GROUP
            it.description = "Checks for platform-incompatible commonMain dependencies, duplicates and updates."
            it.modules.set(modules)
            it.rootDirectory.set(rootDir)
            live(it)
        }
    }

    companion object {
        const val GROUP = "kmp hub"
    }
}
