package io.github.kmphub.gradle

import io.github.kmphub.core.Doctor
import io.github.kmphub.core.InstallRequest
import io.github.kmphub.core.Installer
import io.github.kmphub.core.Library
import io.github.kmphub.core.LibraryCatalog
import io.github.kmphub.core.LiveCatalog
import io.github.kmphub.core.LiveSnapshot
import io.github.kmphub.core.MavenIndex
import io.github.kmphub.core.ModuleInfo
import io.github.kmphub.core.ModuleKind
import io.github.kmphub.core.ProjectFiles
import io.github.kmphub.core.RemoteRecipes
import io.github.kmphub.core.Severity
import io.github.kmphub.core.UnifiedDiff
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tasks that use live versions from Maven Central / Google Maven, cached in the Gradle user home. */
@DisableCachingByDefault(because = "Talks to remote repositories")
abstract class LiveHubTask : DefaultTask() {
    @get:Internal
    abstract val versionCache: RegularFileProperty

    /** Gradle's --offline: use cached or bundled versions only. */
    @get:Internal
    abstract val offline: Property<Boolean>

    @get:Internal
    @get:Option(option = "refresh", description = "Ignore the 24h version cache and fetch the latest versions now.")
    abstract val refresh: Property<Boolean>

    init {
        offline.convention(false)
        refresh.convention(false)
        outputs.upToDateWhen { false }
    }

    // Created per execution and never serialized into the configuration cache.
    @Transient
    private var cachedIndex: MavenIndex? = null

    @get:Internal
    protected val index: MavenIndex
        get() = cachedIndex ?: MavenIndex().also { cachedIndex = it }

    protected fun live(): LiveSnapshot {
        val snapshot = LiveCatalog(index = index, cacheFile = versionCache.get().asFile).load(refresh.get(), offline.get())
        logger.info("KMP Hub versions: ${describe(snapshot)}")
        return snapshot
    }

    protected fun describe(snapshot: LiveSnapshot): String = when {
        snapshot.fetchedAt == 0L -> "bundled with the plugin (no network yet)"
        else -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - snapshot.fetchedAt)
            "latest from Maven Central / Google Maven, checked " + when {
                minutes < 1 -> "just now"
                minutes < 120 -> "$minutes min ago"
                else -> "${minutes / 60} h ago"
            }
        }
    }

    /** Resolves `group:name` ids to on-the-fly recipes; other ids must be curated. */
    protected fun withRemote(catalog: LibraryCatalog, ids: List<String>): LibraryCatalog {
        val remote = ids.filter { ':' in it && catalog[it] == null }
        if (remote.isEmpty()) return catalog
        if (offline.get()) throw GradleException("Can't look up ${remote.joinToString()} while Gradle is offline.")
        val resolved = remote.map { id ->
            RemoteRecipes.resolve(index, id) ?: throw GradleException("$id isn't on Maven Central, Google Maven or the Plugin Portal.")
        }
        return LibraryCatalog(catalog.libraries + resolved)
    }
}

@DisableCachingByDefault(because = "Prints to the console")
abstract class KmpHubListTask : LiveHubTask() {
    @get:Input @get:Optional
    @get:Option(option = "search", description = "Filter by name, description or Maven coordinates.")
    abstract val search: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "category", description = "Only show one category, e.g. Networking.")
    abstract val category: Property<String>

    @TaskAction
    fun list() {
        val snapshot = live()
        val catalog = snapshot.catalog
        val libraries = catalog.search(search.getOrElse(""), category.orNull)
        if (libraries.isEmpty()) {
            logger.lifecycle("No curated libraries match. Categories: ${catalog.categories.joinToString()}")
            logger.lifecycle("Search every library on Maven Central and Google Maven: ./gradlew kmpHubSearch --query=<text>")
            return
        }
        libraries.groupBy { it.category }.toSortedMap().forEach { (category, libs) ->
            logger.lifecycle("\n$category")
            libs.forEach { lib -> logger.lifecycle("  " + row(lib.id, lib.primaryVersion, lib) + " ${lib.description}") }
        }
        logger.lifecycle("\nVersions: ${describe(snapshot)}.")
        logger.lifecycle("Add with: ./gradlew kmpHubAdd --lib=<id>[,<id>...] --module=:composeApp")
    }
}

@DisableCachingByDefault(because = "Talks to remote repositories")
abstract class KmpHubSearchTask : LiveHubTask() {
    @get:Input
    @get:Option(option = "query", description = "Text to search for, e.g. 'image loading' or 'io.github.xyz'.")
    abstract val query: Property<String>

    @get:Input
    @get:Option(option = "limit", description = "How many results to resolve (default 15).")
    abstract val limit: Property<String>

    init {
        limit.convention("15")
    }

    @TaskAction
    fun searchRemote() {
        if (offline.get()) throw GradleException("kmpHubSearch needs the network; run without --offline.")
        val q = query.get()
        val curated = live().catalog
        curated.search(q).takeIf { it.isNotEmpty() }?.let { matches ->
            logger.lifecycle("Curated recipes (artifacts, plugins and per-platform engines included):")
            matches.forEach { logger.lifecycle("  " + row(it.id, it.primaryVersion, it)) }
            logger.lifecycle("")
        }

        val search = index.search(q, limit.get().toIntOrNull() ?: 15)
        search.unavailable.forEach { logger.warn("$it didn't respond; its results are missing. Try again in a moment.") }
        val found = search.artifacts
        if (found.isEmpty()) {
            if (search.unavailable.size < 2) logger.lifecycle("Nothing found for '$q' on Maven Central or Google Maven.")
            return
        }
        val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "kmphub-search").apply { isDaemon = true } }
        val resolved = try {
            found.map { artifact -> pool.submit(Callable { artifact to RemoteRecipes.resolve(index, artifact.module, artifact.repository) }) }
                .map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        logger.lifecycle("Maven Central / Google Maven:")
        resolved.forEach { (artifact, lib) -> logger.lifecycle("  " + row(artifact.module, lib?.primaryVersion ?: "(no stable)", lib, width = 56)) }
        logger.lifecycle("\nAdd any of these with: ./gradlew kmpHubAdd --lib=<group:name> --module=:composeApp")
    }
}

@DisableCachingByDefault(because = "Prints to the console")
abstract class KmpHubModulesTask : DefaultTask() {
    @get:Input
    abstract val modules: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun print() {
        ModuleCodec.decode(modules.get()).forEach { m ->
            val targets = m.targets.joinToString { "${it.name} (${it.platform.name.lowercase()})" }
            logger.lifecycle("${m.path.padEnd(20)} ${m.kind.name.padEnd(8)} ${m.buildFile}${if (targets.isEmpty()) "" else "\n${" ".repeat(21)}$targets"}")
        }
    }
}

@DisableCachingByDefault(because = "Edits build files")
abstract class KmpHubAddTask : LiveHubTask() {
    @get:Input
    abstract val modules: Property<String>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    @get:Option(option = "lib", description = "Comma-separated ids from kmpHubList, or any group:name from kmpHubSearch.")
    abstract val libraries: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "module", description = "Target module path, e.g. :composeApp. Defaults to the only KMP module.")
    abstract val module: Property<String>

    @get:Input @get:Optional
    @get:Option(option = "source-set", description = "Put common artifacts in this source set instead of commonMain.")
    abstract val sourceSet: Property<String>

    @get:Input
    @get:Option(option = "preview", description = "Print the diff without writing files.")
    abstract val preview: Property<Boolean>

    @get:Input
    @get:Option(option = "force", description = "Install even when the library doesn't support every target.")
    abstract val force: Property<Boolean>

    init {
        preview.convention(false)
        force.convention(false)
    }

    @TaskAction
    fun add() {
        val root = rootDirectory.get().asFile
        val target = pickModule(ModuleCodec.decode(modules.get()))
        val ids = libraries.get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val catalog = withRemote(live().catalog, ids)
        val result = Installer(catalog).install(InstallRequest(ids, target, sourceSet.orNull, force.get()), files(root))

        result.messages.forEach { logger.lifecycle(it.toString()) }
        if (result.hasErrors) throw GradleException("Nothing was changed; fix the errors above or pass --force.")
        if (result.changes.isEmpty()) {
            logger.lifecycle("Nothing to change.")
            return
        }
        result.changes.forEach { logger.lifecycle("\n" + UnifiedDiff.render(it).trimEnd()) }
        if (preview.get()) {
            logger.lifecycle("\nPreview: ${result.changes.size} file(s) would change.")
            return
        }
        result.changes.forEach { change ->
            File(root, change.path).apply { parentFile.mkdirs() }.writeText(change.after)
        }
        logger.lifecycle("\nUpdated ${result.changes.joinToString { it.path }}. Sync Gradle to pick up the changes.")
    }

    private fun pickModule(all: List<ModuleInfo>): ModuleInfo {
        val usable = all.filter { it.kind != ModuleKind.UNKNOWN }
        val requested = module.orNull?.let { if (it.startsWith(":")) it else ":$it" }
        if (requested != null) {
            return all.firstOrNull { it.path == requested }
                ?: throw GradleException("No module $requested. Modules: ${usable.joinToString { it.path }}")
        }
        val kmp = usable.filter { it.kind == ModuleKind.KMP }
        return kmp.singleOrNull()
            ?: usable.firstOrNull { it.path == ":composeApp" }
            ?: usable.singleOrNull()
            ?: throw GradleException("Pick a module with --module. Modules: ${usable.joinToString { it.path }}")
    }
}

@DisableCachingByDefault(because = "Reads build files that change between runs")
abstract class KmpHubDoctorTask : LiveHubTask() {
    @get:Input
    abstract val modules: Property<String>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:Input
    @get:Option(option = "strict", description = "Fail the build when errors are found (for CI).")
    abstract val strict: Property<Boolean>

    init {
        strict.convention(false)
    }

    @TaskAction
    fun check() {
        val findings = Doctor(live().catalog).check(ModuleCodec.decode(modules.get()), files(rootDirectory.get().asFile))
        if (findings.isEmpty()) {
            logger.lifecycle("No problems found.")
            return
        }
        findings.forEach { logger.lifecycle(it.toString()) }
        val errors = findings.count { it.severity == Severity.ERROR }
        logger.lifecycle("\n$errors error(s), ${findings.count { it.severity == Severity.WARNING }} warning(s), " +
            "${findings.count { it.severity == Severity.INFO }} suggestion(s).")
        if (strict.get() && errors > 0) throw GradleException("kmpHubDoctor found $errors error(s).")
    }
}

private fun row(id: String, version: String?, lib: Library?, width: Int = 24): String {
    val platforms = lib?.platforms?.sorted()?.joinToString("/") { it.name.lowercase() } ?: "?"
    return "${id.padEnd(width)} ${(version ?: "").padEnd(12)} ${platforms.padEnd(34)}"
}

private fun files(root: File) = ProjectFiles { path -> File(root, path).takeIf { it.isFile }?.readText() }
