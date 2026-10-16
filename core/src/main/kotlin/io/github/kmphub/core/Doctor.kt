package io.github.kmphub.core

data class Finding(val severity: Severity, val module: String?, val text: String) {
    override fun toString() = "${severity.name.padEnd(7)} ${module?.let { "$it: " }.orEmpty()}$text"
}

/** Static health checks over the version catalog and module build files. */
class Doctor(private val catalog: LibraryCatalog) {

    fun check(modules: List<ModuleInfo>, files: ProjectFiles, catalogPath: String = VersionCatalogFile.DEFAULT_PATH): List<Finding> {
        val toml = files.read(catalogPath)?.let(VersionCatalogFile::parse)
        val findings = mutableListOf<Finding>()
        if (toml != null) {
            findings += duplicates(toml)
            findings += updates(toml)
            findings += legacyKsp(toml)
        }
        for (module in modules.filter { it.kind == ModuleKind.KMP }) {
            val text = files.read(module.buildFile) ?: continue
            findings += commonMainCompatibility(module, KtsBuildScript.parse(text), toml)
        }
        return findings.sortedBy { -it.severity.ordinal }
    }

    private fun duplicates(toml: VersionCatalogFile): List<Finding> =
        toml.libraries().filter { it.module != null }.groupBy { it.module }.filterValues { it.size > 1 }.map { (module, entries) ->
            Finding(Severity.WARNING, null, "$module is declared ${entries.size} times in the catalog: ${entries.joinToString { it.alias }}")
        }

    private fun updates(toml: VersionCatalogFile): List<Finding> {
        val seen = mutableSetOf<String>()
        return toml.libraries().mapNotNull { entry ->
            val library = entry.module?.let(catalog::libraryForModule) ?: return@mapNotNull null
            val artifact = library.artifacts.first { it.module == entry.module }
            val latest = artifact.versionRef?.let(library.versions::get) ?: return@mapNotNull null
            val current = toml.resolvedVersion(entry) ?: return@mapNotNull null
            val key = entry.versionRef ?: entry.alias
            if (!seen.add(key) || Versions.compare(current, latest) >= 0) return@mapNotNull null
            Finding(Severity.INFO, null, "${library.name}: $current -> $latest available (${entry.versionRef?.let { "versions.$it" } ?: entry.alias})")
        }
    }

    private fun legacyKsp(toml: VersionCatalogFile): List<Finding> {
        val versions = toml.versions()
        val ksp = toml.pluginById("com.google.devtools.ksp")?.let { it.version ?: it.versionRef?.let(versions::get) } ?: return emptyList()
        val kotlin = toml.pluginById("org.jetbrains.kotlin.multiplatform")
            ?.let { it.version ?: it.versionRef?.let(versions::get) }
            ?: toml.pluginById("org.jetbrains.kotlin.android")?.let { it.version ?: it.versionRef?.let(versions::get) }
            ?: return emptyList()
        // Before KSP 2.3, versions were "<kotlin>-<ksp>" and had to match the Kotlin version exactly.
        val prefix = ksp.substringBefore("-1.0.", missingDelimiterValue = "")
        return if (prefix.isNotEmpty() && prefix != kotlin) {
            listOf(Finding(Severity.ERROR, null, "KSP $ksp is built for Kotlin $prefix but the project uses Kotlin $kotlin."))
        } else emptyList()
    }

    private fun commonMainCompatibility(module: ModuleInfo, script: KtsBuildScript, toml: VersionCatalogFile?): List<Finding> {
        val body = script.allSourceSetDependencies("commonMain").joinToString("\n") { script.body(it) }
        val coordinates = buildList {
            if (toml != null) {
                val byAccessor = toml.libraries().associateBy { VersionCatalogFile.accessor(it.alias) }
                Regex("""libs\.[\w.]+""").findAll(body).forEach { m -> byAccessor[m.value]?.module?.let(::add) }
            }
            Regex(""""([\w.\-]+:[\w.\-]+)(:[^"]*)?"""").findAll(body).forEach { add(it.groupValues[1]) }
        }
        return coordinates.distinct().mapNotNull { coordinate ->
            val library = catalog.libraryForModule(coordinate) ?: return@mapNotNull null
            val artifact = library.artifacts.first { it.module == coordinate }
            if (artifact.placement != Placement.COMMON) {
                return@mapNotNull Finding(
                    Severity.ERROR, module.path,
                    "$coordinate is ${artifact.placement.key}-only but sits in commonMain; move it to ${artifact.placement.key}Main.",
                )
            }
            val missing = module.platforms.filterNot { library.supports(it) }
            if (missing.isEmpty()) null
            else Finding(
                Severity.ERROR, module.path,
                "$coordinate in commonMain doesn't support ${missing.joinToString { it.name.lowercase() }}.",
            )
        }
    }
}

object Versions {
    private val PRE_RELEASE = Regex("(?i)(alpha|beta|rc|dev|eap|snapshot|-m\\d|compat)")

    fun isStable(version: String) = !PRE_RELEASE.containsMatchIn(version)

    /** Numeric comparison on dot/dash separated parts; a pre-release sorts before its release. */
    fun compare(a: String, b: String): Int {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)
            val y = pb.getOrNull(i)
            if (x == y) continue
            if (x == null) return if (y!!.toIntOrNull() != null) -1 else 1
            if (y == null) return if (x.toIntOrNull() != null) 1 else -1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            return when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> 1
                ny != null -> -1
                else -> x.compareTo(y)
            }
        }
        return 0
    }
}
