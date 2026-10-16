package io.github.kmphub.core

/** Read access to project files by path relative to the root project. Returns null when missing. */
fun interface ProjectFiles {
    fun read(path: String): String?
}

data class InstallRequest(
    val libraryIds: List<String>,
    val module: ModuleInfo,
    /** Put the library's common artifacts here instead of `commonMain`, e.g. `androidMain`. */
    val sourceSet: String? = null,
    /** Install even when the library doesn't publish for every platform the module targets. */
    val force: Boolean = false,
    val catalogPath: String = VersionCatalogFile.DEFAULT_PATH,
    val rootBuildFile: String = "build.gradle.kts",
)

data class InstallResult(val changes: List<FileChange>, val messages: List<Message>) {
    val hasErrors: Boolean get() = messages.any { it.severity == Severity.ERROR }
}

/**
 * Turns "add these libraries to this module" into concrete file edits: version catalog entries,
 * source-set dependencies, KSP configurations and Gradle plugins. Nothing is written; callers apply
 * [InstallResult.changes] themselves (the Gradle task writes files, the IDE edits documents).
 */
class Installer(private val catalog: LibraryCatalog) {

    fun install(request: InstallRequest, files: ProjectFiles): InstallResult {
        val module = request.module
        val moduleBefore = files.read(module.buildFile)
            ?: return InstallResult(emptyList(), listOf(Message(Severity.ERROR, "${module.buildFile} not found")))
        if (module.kind == ModuleKind.UNKNOWN) {
            return InstallResult(
                emptyList(),
                listOf(Message(Severity.ERROR, "${module.path} doesn't apply a Kotlin Multiplatform, Android or JVM plugin")),
            )
        }
        val catalogBefore = files.read(request.catalogPath)
        val rootBefore = if (module.buildFile == request.rootBuildFile) null else files.read(request.rootBuildFile)

        val session = Session(
            request = request,
            script = KtsBuildScript.parse(moduleBefore),
            rootScript = rootBefore?.let(KtsBuildScript::parse),
            toml = catalogBefore?.let(VersionCatalogFile::parse),
        )
        if (catalogBefore == null) {
            session.info("No ${request.catalogPath} found, so dependencies are added with direct coordinates.")
        }

        for (id in request.libraryIds.distinct()) {
            val library = catalog[id]
            if (library == null) {
                session.messages += Message(Severity.ERROR, "Unknown library '$id'. Run kmpHubList to see what's available.")
                continue
            }
            session.install(library)
        }

        val changes = buildList {
            session.toml?.text()?.takeIf { it != catalogBefore }?.let { add(FileChange(request.catalogPath, catalogBefore, it)) }
            session.rootScript?.text?.takeIf { it != rootBefore }?.let { add(FileChange(request.rootBuildFile, rootBefore, it)) }
            session.script.text.takeIf { it != moduleBefore }?.let { add(FileChange(module.buildFile, moduleBefore, it)) }
        }
        return InstallResult(changes, session.messages)
    }

    private class Session(
        val request: InstallRequest,
        var script: KtsBuildScript,
        var rootScript: KtsBuildScript?,
        val toml: VersionCatalogFile?,
    ) {
        val messages = mutableListOf<Message>()
        private val module = request.module

        fun info(text: String) {
            messages += Message(Severity.INFO, text)
        }

        fun install(library: Library) {
            val unsupported = unsupportedPlatforms(library)
            if (unsupported.isNotEmpty()) {
                val where = request.sourceSet ?: if (module.kind == ModuleKind.KMP) "commonMain" else module.path
                val text = "${library.name} doesn't publish for ${unsupported.joinToString { it.name.lowercase() }}, " +
                    "which $where compiles for. Pick a platform source set (e.g. --source-set=androidMain) or force it."
                if (!request.force) {
                    messages += Message(Severity.ERROR, text)
                    return
                }
                messages += Message(Severity.WARNING, text)
            }

            var added = 0
            for (artifact in library.artifacts) {
                val placements = placementsFor(artifact)
                if (placements.isEmpty()) {
                    info("Skipped ${artifact.module}: ${module.path} has no ${artifact.placement.key} target.")
                    continue
                }
                val notation = notation(library, artifact)
                val perTargetKsp = module.kind == ModuleKind.KMP && artifact.configuration == "ksp"
                for ((sourceSet, configuration) in placements) {
                    val call = when {
                        perTargetKsp -> "add(\"$configuration\", $notation)"
                        !artifact.bom -> "$configuration($notation)"
                        sourceSet != null -> "$configuration(project.dependencies.platform($notation))"
                        else -> "$configuration(platform($notation))"
                    }
                    // Per-target KSP lines share one block, so they're deduplicated by the whole call.
                    if (addDependency(sourceSet, call, if (perTargetKsp) call else notation)) added++
                }
            }
            library.plugins.forEach { applyPlugin(library, it) }
            library.blocks.forEach(::addConfigBlock)
            library.notes.forEach { info("${library.name}: $it") }
            info(if (added > 0) "Added ${library.name} to ${module.path}." else "${library.name} is already in ${module.path}.")
        }

        /** (source set or null for top-level `dependencies {}`, configuration) pairs. */
        private fun placementsFor(artifact: Artifact): List<Pair<String?, String>> {
            val p = artifact.placement
            if (module.kind != ModuleKind.KMP) {
                val applies = p == Placement.COMMON || p == Placement.COMMON_TEST ||
                    (p == Placement.ANDROID && module.kind == ModuleKind.ANDROID) ||
                    (p == Placement.JVM && module.kind == ModuleKind.JVM)
                if (!applies) return emptyList()
                val configuration = when {
                    artifact.configuration == "ksp" -> if (p == Placement.COMMON_TEST) "kspTest" else "ksp"
                    p == Placement.COMMON_TEST -> "test" + artifact.configuration.cap()
                    else -> artifact.configuration
                }
                return listOf(null to configuration)
            }

            val targets = module.targets.filter { t ->
                when (p) {
                    Placement.COMMON, Placement.COMMON_TEST -> true
                    Placement.ANDROID -> t.platform == Platform.ANDROID
                    Placement.IOS -> t.platform == Platform.IOS
                    Placement.JVM -> t.platform == Platform.JVM
                    Placement.JS -> t.platform == Platform.JS
                    Placement.WASM -> t.platform == Platform.WASM
                }
            }
            if (artifact.configuration == "ksp") {
                // KSP in KMP is configured per target: kspAndroid, kspIosArm64, kspJvm, ...
                return targets.map { null to "ksp${it.name.cap()}" }
            }
            if (targets.isEmpty() && p != Placement.COMMON && p != Placement.COMMON_TEST) return emptyList()
            val sourceSets = when (p) {
                Placement.COMMON -> listOf(request.sourceSet ?: "commonMain")
                Placement.COMMON_TEST -> listOf("commonTest")
                Placement.ANDROID -> listOf("androidMain")
                Placement.IOS -> listOf("iosMain")
                Placement.JVM -> targets.map { "${it.name}Main" }
                Placement.JS -> listOf("jsMain")
                Placement.WASM -> targets.map { "${it.name}Main" }
            }
            return sourceSets.distinct().map { it to artifact.configuration }
        }

        private fun notation(library: Library, artifact: Artifact): String {
            if (toml == null) {
                val version = artifact.versionRef?.let { library.versions.getValue(it) }
                return if (version == null) "\"${artifact.module}\"" else "\"${artifact.module}:$version\""
            }
            val existing = toml.libraryByModule(artifact.module)
            if (existing != null) return VersionCatalogFile.accessor(existing.alias)

            var alias = artifact.alias
            if (toml.hasKey("libraries", alias)) alias = "$alias-kmphub"
            val versionKey = artifact.versionRef?.let { ensureVersion(library, it) }
            toml.addLibrary(alias, artifact.module, versionKey)
            return VersionCatalogFile.accessor(alias)
        }

        /** Returns the catalog version key to reference, adding it when the project doesn't have one. */
        private fun ensureVersion(library: Library, ref: String, pluginId: String? = null): String {
            val toml = toml!!
            val wanted = library.versions.getValue(ref)
            if (pluginId != null && pluginId.startsWith("org.jetbrains.kotlin.") && ref == "kotlin") {
                // Kotlin compiler plugins must match the project's Kotlin version, whatever its key is called.
                val kotlinRef = toml.plugins().firstOrNull { it.id in KOTLIN_PLUGINS && it.versionRef != null }?.versionRef
                if (kotlinRef != null) return kotlinRef
            }
            val existing = toml.versions()[ref]
            when {
                existing == null -> toml.addVersion(ref, wanted)
                existing != wanted -> info("Kept $ref = \"$existing\" from your catalog (hub recipe uses $wanted).")
            }
            return ref
        }

        private fun addDependency(sourceSet: String?, call: String, notation: String): Boolean {
            val block = if (sourceSet == null) topLevelDependencies() else sourceSetDependencies(sourceSet) ?: return false
            if (containsDependency(script.body(block), notation)) return false
            script = script.insertLine(block, call)
            return true
        }

        private fun topLevelDependencies(): KtsBuildScript.Block {
            script.topLevel("dependencies")?.let { return it }
            script = script.insertBlock(null, "dependencies")
            return script.topLevel("dependencies")!!
        }

        private fun sourceSetDependencies(sourceSet: String): KtsBuildScript.Block? {
            script.sourceSetDependencies(sourceSet)?.let { return it }
            val kotlin = script.topLevel("kotlin")
            if (kotlin == null) {
                messages += Message(Severity.ERROR, "${module.buildFile} has no kotlin { } block to add $sourceSet dependencies to.")
                return null
            }
            val owner = kotlin.descendants().firstOrNull { KtsBuildScript.sourceSetHeader(it.header, sourceSet) }
            if (owner != null) {
                script = script.insertBlock(owner, "dependencies")
            } else {
                if (kotlin.child("sourceSets") == null) script = script.insertBlock(kotlin, "sourceSets")
                val sourceSets = script.topLevel("kotlin")!!.child("sourceSets")!!
                val accessible = KtsBuildScript.hasGeneratedAccessor(sourceSet) || script.declaresSourceSetVal(sourceSet)
                val header = if (accessible) "$sourceSet.dependencies" else "getByName(\"$sourceSet\").dependencies"
                script = script.insertBlock(sourceSets, header)
            }
            return script.sourceSetDependencies(sourceSet)
        }

        private fun addConfigBlock(block: ConfigBlock) {
            if (script.topLevel(block.header) != null) {
                info("Kept your existing ${block.header} { } block.")
                return
            }
            script = script.insertBlock(null, block.header)
            block.lines.forEach { line -> script = script.insertLine(script.topLevel(block.header)!!, line) }
        }

        private fun applyPlugin(library: Library, spec: PluginSpec) {
            val shorthand = spec.id.removePrefix("org.jetbrains.kotlin.").takeIf { it != spec.id }?.let { "kotlin(\"$it\")" }
            fun alreadyApplied(body: String, accessor: String?) =
                (accessor != null && body.contains(accessor)) || body.contains("\"${spec.id}\"") ||
                    (shorthand != null && body.replace(" ", "").contains(shorthand))

            val accessor: String?
            val moduleLine: String
            if (toml != null) {
                val alias = toml.pluginById(spec.id)?.alias ?: spec.alias.also {
                    toml.addPlugin(it, spec.id, ensureVersion(library, spec.versionRef, spec.id))
                }
                accessor = VersionCatalogFile.accessor(alias, plugin = true)
                moduleLine = "alias($accessor)"
            } else {
                accessor = null
                moduleLine = "id(\"${spec.id}\") version \"${library.versions.getValue(spec.versionRef)}\""
            }

            if (script.topLevel("plugins") == null) script = script.prependBlock("plugins")
            val plugins = script.topLevel("plugins")!!
            if (!alreadyApplied(script.body(plugins), accessor)) {
                script = script.insertLine(plugins, moduleLine)
            }

            // Mirror the KMP wizard convention of declaring every plugin in the root with `apply false`.
            val root = rootScript ?: return
            val rootPlugins = root.topLevel("plugins") ?: return
            val body = root.body(rootPlugins)
            if (accessor != null && body.contains("apply false") && !alreadyApplied(body, accessor)) {
                rootScript = root.insertLine(rootPlugins, "$moduleLine apply false")
            }
        }

        private fun unsupportedPlatforms(library: Library): Set<Platform> {
            val needed = request.sourceSet?.let { platformsOfSourceSet(it) } ?: module.platforms
            return needed.filterNot { library.supports(it) }.toSet()
        }

        private fun platformsOfSourceSet(sourceSet: String): Set<Platform> {
            if (module.kind != ModuleKind.KMP) return module.platforms
            val name = sourceSet.removeSuffix("Main").removeSuffix("Test")
            return when {
                name == "common" -> module.platforms
                name == "native" || name == "apple" -> module.platforms intersect setOf(Platform.IOS, Platform.NATIVE)
                name.startsWith("ios") -> setOf(Platform.IOS)
                name.startsWith("android") -> setOf(Platform.ANDROID)
                else -> module.targets.filter { it.name == name }.map { it.platform }.toSet().ifEmpty { module.platforms }
            }
        }
    }

    companion object {
        private val KOTLIN_PLUGINS = setOf(
            "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.kotlin.android",
            "org.jetbrains.kotlin.jvm",
        )

        /** True when [body] already declares [notation], ignoring formatting. */
        fun containsDependency(body: String, notation: String): Boolean {
            val compact = body.replace(Regex("\\s+"), "")
            val target = notation.replace(Regex("\\s+"), "")
            if (target.startsWith("\"")) {
                // Direct coordinates: match on group:name regardless of version.
                val ga = target.trim('"').split(':').take(2).joinToString(":")
                return compact.contains("\"$ga:") || compact.contains("\"$ga\"")
            }
            // `libs.koin.core` must not match `libs.koin.core.ext`; a full call ending in `)` needs no boundary.
            val boundary = if (target.last().isLetterOrDigit() || target.last() == '_') "(?![A-Za-z0-9_.])" else ""
            return Regex(Regex.escape(target) + boundary).containsMatchIn(compact)
        }
    }
}

/** JVM-only libraries work on Android too. */
fun Library.supports(platform: Platform): Boolean =
    platform in platforms || (platform == Platform.ANDROID && Platform.JVM in platforms)

private fun String.cap() = replaceFirstChar { it.uppercaseChar() }
