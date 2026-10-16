package io.github.kmphub.core

/** A platform family a library publishes for, or a module compiles for. */
enum class Platform { ANDROID, IOS, JVM, JS, WASM, NATIVE }

/**
 * Where an artifact goes, independent of how a particular module names its source sets.
 * [COMMON] lands in `commonMain`, [JVM] lands in every non-Android JVM target (`jvmMain`, `desktopMain`, ...).
 */
enum class Placement(val key: String) {
    COMMON("common"),
    COMMON_TEST("commonTest"),
    ANDROID("android"),
    IOS("ios"),
    JVM("jvm"),
    JS("js"),
    WASM("wasm");

    companion object {
        fun of(key: String): Placement =
            entries.firstOrNull { it.key == key } ?: error("Unknown placement '$key'")
    }
}

data class Artifact(
    /** Catalog alias used when the project has no entry for this module yet, e.g. `ktor-client-core`. */
    val alias: String,
    /** `group:name` */
    val module: String,
    /** Key into [Library.versions]; null when the version comes from a BOM. */
    val versionRef: String?,
    val placement: Placement = Placement.COMMON,
    /** `implementation`, `api` or `ksp`. */
    val configuration: String = "implementation",
    /** True for BOMs, which are added with `platform(...)`. */
    val bom: Boolean = false,
)

data class PluginSpec(
    val alias: String,
    val id: String,
    val versionRef: String,
)

/** A top-level configuration block a library needs, e.g. `room { schemaDirectory(...) }`. */
data class ConfigBlock(val header: String, val lines: List<String>)

data class Library(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val website: String?,
    val platforms: Set<Platform>,
    /** versionRef -> version */
    val versions: Map<String, String>,
    val artifacts: List<Artifact>,
    val plugins: List<PluginSpec> = emptyList(),
    /** Added to the module's build file when no block with the same header exists yet. */
    val blocks: List<ConfigBlock> = emptyList(),
    /** Manual follow-up steps the installer can't do for you yet. */
    val notes: List<String> = emptyList(),
    /** klibs.io project key (`owner/name`) this recipe covers, so browsing that project installs the full recipe. */
    val klibs: String? = null,
) {
    val primaryVersion: String? get() = artifacts.firstNotNullOfOrNull { a -> a.versionRef?.let(versions::get) }
}

enum class ModuleKind { KMP, ANDROID, JVM, UNKNOWN }

data class Target(val name: String, val platform: Platform)

data class ModuleInfo(
    /** Gradle path, e.g. `:composeApp`. */
    val path: String,
    /** Build file path relative to the root project, e.g. `composeApp/build.gradle.kts`. */
    val buildFile: String,
    val kind: ModuleKind,
    val targets: List<Target> = emptyList(),
) {
    val platforms: Set<Platform>
        get() = when (kind) {
            ModuleKind.KMP -> targets.map { it.platform }.toSet()
            ModuleKind.ANDROID -> setOf(Platform.ANDROID)
            ModuleKind.JVM -> setOf(Platform.JVM)
            ModuleKind.UNKNOWN -> emptySet()
        }
}

enum class Severity { INFO, WARNING, ERROR }

data class Message(val severity: Severity, val text: String) {
    override fun toString() = "${severity.name.lowercase()}: $text"
}

/** A full-file rewrite. [before] is null when the file is created. */
data class FileChange(val path: String, val before: String?, val after: String)
