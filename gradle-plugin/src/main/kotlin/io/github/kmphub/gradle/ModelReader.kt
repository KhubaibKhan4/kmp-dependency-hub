package io.github.kmphub.gradle

import io.github.kmphub.core.ModuleInfo
import io.github.kmphub.core.ModuleKind
import io.github.kmphub.core.Platform
import io.github.kmphub.core.Target
import org.gradle.api.Project

/**
 * Reads a module's real Kotlin model. KGP lives in the build script classloader, which this plugin
 * can't see, so the `kotlin` extension is read reflectively through its public getters.
 */
internal object ModelReader {

    fun read(project: Project): ModuleInfo {
        val buildFile = project.buildFile.relativeTo(project.rootDir).invariantSeparatorsPath
        val plugins = project.plugins
        val kind = when {
            plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") -> ModuleKind.KMP
            plugins.hasPlugin("com.android.application") || plugins.hasPlugin("com.android.library") -> ModuleKind.ANDROID
            plugins.hasPlugin("org.jetbrains.kotlin.jvm") || plugins.hasPlugin("java") -> ModuleKind.JVM
            else -> ModuleKind.UNKNOWN
        }
        val targets = if (kind == ModuleKind.KMP) project.extensions.findByName("kotlin")?.let(::targets).orEmpty() else emptyList()
        return ModuleInfo(project.path, buildFile, kind, targets)
    }

    private fun targets(kotlin: Any): List<Target> {
        val all = kotlin.call("getTargets") as? Iterable<*> ?: return emptyList()
        return all.mapNotNull { target ->
            target ?: return@mapNotNull null
            val name = target.call("getName") as String
            val platform = when (target.call("getPlatformType").toString()) {
                "androidJvm" -> Platform.ANDROID
                "jvm" -> Platform.JVM
                "js" -> Platform.JS
                "wasm" -> Platform.WASM
                "native" -> if (name.startsWith("ios")) Platform.IOS else Platform.NATIVE
                else -> null // "common" is the metadata target
            }
            platform?.let { Target(name, it) }
        }
    }

    private fun Any.call(getter: String): Any? = javaClass.getMethod(getter).invoke(this)
}

/** Line-based encoding so the module model can be a plain `Property<String>` task input. */
internal object ModuleCodec {
    fun encode(modules: List<ModuleInfo>): String = modules.joinToString("\n") { m ->
        listOf(m.path, m.buildFile, m.kind.name, m.targets.joinToString(",") { "${it.name}=${it.platform}" }).joinToString("|")
    }

    fun decode(text: String): List<ModuleInfo> = text.lines().filter { it.isNotBlank() }.map { line ->
        val (path, buildFile, kind, targets) = line.split("|")
        ModuleInfo(
            path, buildFile, ModuleKind.valueOf(kind),
            targets.split(",").filter { it.isNotEmpty() }.map { t ->
                val (name, platform) = t.split("=")
                Target(name, Platform.valueOf(platform))
            },
        )
    }
}
