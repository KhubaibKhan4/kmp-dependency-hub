package io.github.kmphub.core

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The catalog with live versions, and where they came from. */
data class LiveSnapshot(
    val catalog: LibraryCatalog,
    /** Epoch millis of the last successful network refresh; 0 when only the bundled versions are known. */
    val fetchedAt: Long,
    /** Version keys that couldn't be resolved this time (they keep their previous value). */
    val unresolved: List<String> = emptyList(),
)

/**
 * Keeps recipe versions current: looks up the latest stable release of every versionRef and caches the
 * result in [cacheFile] for [maxAgeMillis], so the IDE and Gradle don't hit the network on every use.
 */
class LiveCatalog(
    private val bundled: LibraryCatalog = LibraryCatalog.bundled(),
    private val index: MavenIndex = MavenIndex(),
    private val cacheFile: File? = null,
    private val maxAgeMillis: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Cached versions if fresh, otherwise a network refresh; never throws, falls back to cache, then bundled. */
    fun load(forceRefresh: Boolean = false, offline: Boolean = false): LiveSnapshot {
        val cached = readCache()
        if (offline || (!forceRefresh && cached != null && clock() - cached.first < maxAgeMillis)) {
            return cached?.let { LiveSnapshot(apply(it.second), it.first) } ?: LiveSnapshot(bundled, 0)
        }
        val (latest, unresolved) = fetch()
        if (latest.isEmpty()) {
            // Offline or blocked: keep whatever we had.
            return cached?.let { LiveSnapshot(apply(it.second), it.first, unresolved) } ?: LiveSnapshot(bundled, 0, unresolved)
        }
        val merged = cached?.second.orEmpty() + latest
        writeCache(merged)
        return LiveSnapshot(apply(merged), clock(), unresolved)
    }

    /** Lookup key per (library, versionRef): the artifact or plugin marker that defines that version. */
    private fun lookups(): Map<String, () -> String?> = buildMap {
        for (lib in bundled.libraries) {
            for (ref in lib.versions.keys) {
                val key = keyFor(lib, ref) ?: continue
                if (key in this) continue
                put(key) {
                    when {
                        key.startsWith(PLUGIN) -> index.latestPluginVersion(key.removePrefix(PLUGIN))
                        else -> index.latestStable(key)
                    }
                }
            }
        }
    }

    private fun keyFor(lib: Library, ref: String): String? = SPECIAL[ref]
        ?: lib.artifacts.firstOrNull { it.versionRef == ref }?.module
        ?: lib.plugins.firstOrNull { it.versionRef == ref }?.let { PLUGIN + it.id }

    private fun fetch(): Pair<Map<String, String>, List<String>> {
        val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "kmphub-versions").apply { isDaemon = true } }
        try {
            val futures = lookups().mapValues { (_, lookup) -> pool.submit(Callable { lookup() }) }
            val resolved = mutableMapOf<String, String>()
            val unresolved = mutableListOf<String>()
            for ((key, future) in futures) {
                val version = runCatching { future.get(60, TimeUnit.SECONDS) }.getOrNull()
                if (version != null) resolved[key] = version else unresolved += key
            }
            return resolved to unresolved
        } finally {
            pool.shutdownNow()
        }
    }

    private fun apply(latest: Map<String, String>): LibraryCatalog = LibraryCatalog(
        bundled.libraries.map { lib ->
            lib.copy(versions = lib.versions.mapValues { (ref, version) -> keyFor(lib, ref)?.let(latest::get) ?: version })
        },
    )

    private fun readCache(): Pair<Long, Map<String, String>>? {
        val file = cacheFile?.takeIf { it.isFile } ?: return null
        val root = runCatching { Json.parse(file.readText()) }.getOrNull() as? Map<*, *> ?: return null
        val fetchedAt = (root["fetchedAt"] as? Number)?.toLong() ?: return null
        val versions = (root["versions"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to v.toString() }
        return fetchedAt to versions
    }

    private fun writeCache(versions: Map<String, String>) {
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val body = versions.toSortedMap().entries.joinToString(",\n") { (k, v) -> "    ${quote(k)}: ${quote(v)}" }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText("{\n  \"fetchedAt\": ${clock()},\n  \"versions\": {\n$body\n  }\n}\n")
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    companion object {
        private const val PLUGIN = "plugin:"

        /** Versions that track a toolchain rather than one of the library's own artifacts. */
        private val SPECIAL = mapOf(
            "kotlin" to "org.jetbrains.kotlin:kotlin-stdlib",
            "ksp" to "com.google.devtools.ksp:symbol-processing-api",
        )

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}

/** Builds an install recipe on the fly for any artifact found by live search. */
object RemoteRecipes {
    /** Resolves `group:name` to a recipe with its latest stable version and real platform support. */
    fun resolve(index: MavenIndex, module: String, repository: String = "Maven"): Library? {
        val (group, name) = module.split(':').takeIf { it.size >= 2 }?.let { it[0] to it[1] } ?: return null
        val version = index.latestStable(module) ?: return null
        val platforms = index.platforms(module, version)?.ifEmpty { null } ?: setOf(Platform.JVM)
        return library(RemoteArtifact(group, name, repository), version, platforms)
    }

    fun library(artifact: RemoteArtifact, version: String, platforms: Set<Platform>): Library {
        val alias = aliasFor(artifact)
        return Library(
            id = artifact.module,
            name = artifact.module,
            category = artifact.repository,
            description = if (platforms.size > 2) "Kotlin Multiplatform library from ${artifact.repository}."
            else "${platforms.joinToString("/") { it.name.lowercase() }} library from ${artifact.repository}.",
            website = null,
            platforms = platforms,
            versions = mapOf(alias to version),
            artifacts = listOf(Artifact(alias, artifact.module, alias, bom = artifact.name.endsWith("-bom"))),
        )
    }

    /** `io.insert-koin:koin-core` -> `koin-core`; `app.cash.sqldelight:runtime` -> `sqldelight-runtime`. */
    fun aliasFor(artifact: RemoteArtifact): String {
        val name = artifact.name.lowercase().replace('_', '-')
        val owner = artifact.group.split('.').last().lowercase().replace('_', '-')
        return if (name.contains('-') || name.startsWith(owner)) name else "$owner-$name"
    }
}
