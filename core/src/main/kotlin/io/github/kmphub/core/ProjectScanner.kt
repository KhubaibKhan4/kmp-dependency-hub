package io.github.kmphub.core

/**
 * Discovers modules and their KMP targets by reading `settings.gradle.kts` and build files, without
 * running Gradle. The Gradle plugin uses the real project model instead; this is for the IDE, where the
 * model may be stale or unavailable before the first sync.
 */
object ProjectScanner {

    fun scan(files: ProjectFiles, catalogPath: String = VersionCatalogFile.DEFAULT_PATH): List<ModuleInfo> {
        val settings = files.read("settings.gradle.kts") ?: files.read("settings.gradle").orEmpty()
        val pluginIds = files.read(catalogPath)?.let(VersionCatalogFile::parse)?.plugins().orEmpty()
            .mapNotNull { p -> p.id?.let { VersionCatalogFile.accessor(p.alias, plugin = true) to it } }
            .toMap()

        val paths = listOf(":") + INCLUDE.findAll(settings).flatMap { m ->
            QUOTED.findAll(m.groupValues[1]).map { it.groupValues[1] }
        }.map { if (it.startsWith(":")) it else ":$it" }.distinct()

        return paths.mapNotNull { path ->
            val dir = path.removePrefix(":").replace(':', '/')
            val buildFile = if (dir.isEmpty()) "build.gradle.kts" else "$dir/build.gradle.kts"
            val text = files.read(buildFile) ?: return@mapNotNull null
            describe(path, buildFile, text, pluginIds)
        }
    }

    fun describe(path: String, buildFile: String, text: String, pluginIds: Map<String, String> = emptyMap()): ModuleInfo {
        val script = KtsBuildScript.parse(text)
        // Lines with `apply false` only put a plugin on the classpath (typical in the root build file).
        val plugins = script.topLevel("plugins")?.let { script.body(it) }.orEmpty()
            .lines().filterNot { it.contains("apply false") }.joinToString("\n")
        val applied = buildSet {
            Regex("""id\("([^"]+)"\)""").findAll(plugins).forEach { add(it.groupValues[1]) }
            Regex("""kotlin\("([^"]+)"\)""").findAll(plugins).forEach { add("org.jetbrains.kotlin." + it.groupValues[1]) }
            Regex("""alias\((libs\.plugins\.[\w.]+)\)""").findAll(plugins).forEach { m ->
                val accessor = m.groupValues[1]
                add(pluginIds[accessor] ?: accessor)
            }
            Regex("""`([\w-]+)`""").findAll(plugins).forEach { add(it.groupValues[1]) }
        }

        fun has(vararg needles: String) = applied.any { id -> needles.any { id.contains(it, ignoreCase = true) } }

        val kind = when {
            has("multiplatform") -> ModuleKind.KMP
            has("com.android.application", "com.android.library", "plugins.android.application", "plugins.android.library",
                "androidApplication", "androidLibrary") -> ModuleKind.ANDROID
            has("org.jetbrains.kotlin.jvm", "kotlin.jvm", "kotlinJvm", "java-library", "application", "java") -> ModuleKind.JVM
            else -> ModuleKind.UNKNOWN
        }
        val targets = if (kind == ModuleKind.KMP) script.topLevel("kotlin")?.let { targetsIn(script.body(it)) }.orEmpty() else emptyList()
        return ModuleInfo(path, buildFile, kind, targets)
    }

    private fun targetsIn(kotlinBody: String): List<Target> {
        val code = kotlinBody.lines().joinToString("\n") { it.substringBefore("//") }
        val found = linkedMapOf<String, Platform>()
        if (Regex("""\bandroidTarget\s*[({]|\bandroidLibrary\s*\{|^\s*android\s*\{""", RegexOption.MULTILINE).containsMatchIn(code)) {
            found["android"] = Platform.ANDROID
        }
        for (name in listOf("iosArm64", "iosX64", "iosSimulatorArm64")) {
            if (Regex("""\b$name\s*\(""").containsMatchIn(code)) found[name] = Platform.IOS
        }
        Regex("""\bjvm\s*(\(\s*(?:name\s*=\s*)?"(\w+)"\s*\)|\(\s*\)|\{)""").findAll(code).forEach {
            found[it.groupValues[2].ifEmpty { "jvm" }] = Platform.JVM
        }
        if (Regex("""\bwasmJs\s*[({]""").containsMatchIn(code)) found["wasmJs"] = Platform.WASM
        if (Regex("""\bjs\s*[({]""").containsMatchIn(code)) found["js"] = Platform.JS
        for (name in listOf("macosArm64", "macosX64", "linuxX64", "linuxArm64", "mingwX64", "tvosArm64", "watchosArm64")) {
            if (Regex("""\b$name\s*\(""").containsMatchIn(code)) found[name] = Platform.NATIVE
        }
        return found.map { (name, platform) -> Target(name, platform) }
    }

    private val INCLUDE = Regex("""include\s*\(([^)]*)\)""")
    private val QUOTED = Regex(""""([^"]+)"""")
}
