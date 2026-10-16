package io.github.kmphub.core

import io.github.kmphub.core.Fixtures.apply
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerTest {
    private val catalog = LibraryCatalog.bundled()
    private val installer = Installer(catalog)

    private fun install(files: Map<String, String>, module: ModuleInfo, vararg ids: String, sourceSet: String? = null) =
        installer.install(InstallRequest(ids.toList(), module, sourceSet), files::get)

    @Test
    fun `ktor goes to common, android, ios and desktop source sets`() {
        val files = Fixtures.wizardProject()
        val result = install(files, Fixtures.composeAppModule, "ktor-client")
        assertFalse(result.hasErrors, result.messages.toString())
        files.apply(result)

        val build = files.getValue("composeApp/build.gradle.kts")
        val script = KtsBuildScript.parse(build)
        assertContains(script.body(script.sourceSetDependencies("commonMain")!!), "implementation(libs.ktor.client.core)")
        assertContains(script.body(script.sourceSetDependencies("androidMain")!!), "implementation(libs.ktor.client.okhttp)")
        assertContains(script.body(script.sourceSetDependencies("iosMain")!!), "implementation(libs.ktor.client.darwin)")
        assertContains(script.body(script.sourceSetDependencies("desktopMain")!!), "implementation(libs.ktor.client.java)")
        assertFalse(build.contains("ktor.client.js"), "no js target, so no js engine")

        // Inserted with the file's own indentation, after existing entries.
        assertContains(build, "            implementation(compose.material3)\n            implementation(libs.ktor.client.core)\n")

        // iosMain didn't exist: created inside sourceSets.
        assertContains(build, "        iosMain.dependencies {\n            implementation(libs.ktor.client.darwin)\n        }\n")

        val toml = files.getValue("gradle/libs.versions.toml")
        assertContains(toml, "ktor = \"")
        assertContains(toml, "ktor-client-core = { module = \"io.ktor:ktor-client-core\", version.ref = \"ktor\" }")
        // Serialization plugin reuses the project's Kotlin version key.
        assertContains(toml, "kotlinx-serialization = { id = \"org.jetbrains.kotlin.plugin.serialization\", version.ref = \"kotlin\" }")
        assertEquals(1, Regex("""^kotlin = """, RegexOption.MULTILINE).findAll(toml).count())

        assertContains(build, "    alias(libs.plugins.kotlinx.serialization)\n}")
        assertContains(files.getValue("build.gradle.kts"), "    alias(libs.plugins.kotlinx.serialization) apply false\n}")
    }

    @Test
    fun `installing twice changes nothing`() {
        val files = Fixtures.wizardProject()
        files.apply(install(files, Fixtures.composeAppModule, "ktor-client", "koin", "room"))
        val again = install(files, Fixtures.composeAppModule, "ktor-client", "koin", "room")
        assertEquals(emptyList(), again.changes)
        assertTrue(again.messages.any { it.text.contains("already in") })
    }

    @Test
    fun `reuses an existing catalog alias for the same module`() {
        val files = Fixtures.wizardProject()
        files["gradle/libs.versions.toml"] = files.getValue("gradle/libs.versions.toml")
            .replace("[libraries]\n", "[libraries]\nmy-dates = { module = \"org.jetbrains.kotlinx:kotlinx-datetime\", version = \"0.6.0\" }\n")
        files.apply(install(files, Fixtures.composeAppModule, "kotlinx-datetime"))
        assertContains(files.getValue("composeApp/build.gradle.kts"), "implementation(libs.my.dates)")
        assertFalse(files.getValue("gradle/libs.versions.toml").contains("kotlinx-datetime ="))
    }

    @Test
    fun `room adds ksp per target and both plugins`() {
        val files = Fixtures.wizardProject()
        val result = install(files, Fixtures.composeAppModule, "room")
        assertFalse(result.hasErrors, result.messages.toString())
        files.apply(result)
        val build = files.getValue("composeApp/build.gradle.kts")
        val script = KtsBuildScript.parse(build)
        val top = script.body(script.topLevel("dependencies")!!)
        for (config in listOf("kspAndroid", "kspIosArm64", "kspIosSimulatorArm64", "kspDesktop")) {
            assertContains(top, "add(\"$config\", libs.androidx.room.compiler)")
        }
        assertContains(build, "alias(libs.plugins.ksp)")
        assertContains(build, "alias(libs.plugins.androidx.room)")
        // Room's Gradle plugin fails configuration without a schema location, so the recipe adds it.
        assertContains(build, "\nroom {\n    schemaDirectory(\"\$projectDir/schemas\")\n}\n")
        assertEquals(1, Regex("""^room \{""", RegexOption.MULTILINE).findAll(script.text).count())
    }

    @Test
    fun `android-only library is refused in commonMain but allowed in androidMain`() {
        val files = Fixtures.wizardProject()
        val refused = install(files, Fixtures.composeAppModule, "retrofit")
        assertTrue(refused.hasErrors)
        assertEquals(emptyList(), refused.changes)

        val allowed = install(files, Fixtures.composeAppModule, "retrofit", sourceSet = "androidMain")
        assertFalse(allowed.hasErrors, allowed.messages.toString())
        files.apply(allowed)
        val script = KtsBuildScript.parse(files.getValue("composeApp/build.gradle.kts"))
        assertContains(script.body(script.sourceSetDependencies("androidMain")!!), "implementation(libs.retrofit)")
    }

    @Test
    fun `android module gets top-level dependencies with platform BOM`() {
        val files = Fixtures.files(
            "app/build.gradle.kts" to Fixtures.androidApp,
            "gradle/libs.versions.toml" to Fixtures.catalog,
        )
        files.apply(install(files, Fixtures.androidModule, "compose-bom", "hilt", "turbine"))
        val build = files.getValue("app/build.gradle.kts")
        assertContains(build, "    implementation(platform(libs.androidx.compose.bom))\n")
        assertContains(build, "    implementation(libs.androidx.compose.material3)\n")
        assertContains(build, "    ksp(libs.hilt.compiler)\n")
        assertContains(build, "    testImplementation(libs.turbine)\n")
        assertContains(build, "    alias(libs.plugins.hilt)\n")
        assertContains(files.getValue("gradle/libs.versions.toml"), "androidx-compose-material3 = { module = \"androidx.compose.material3:material3\" }")
    }

    @Test
    fun `koin BOM uses project dependencies platform inside source sets`() {
        val files = Fixtures.wizardProject()
        files.apply(install(files, Fixtures.composeAppModule, "koin"))
        val build = files.getValue("composeApp/build.gradle.kts")
        assertContains(build, "implementation(project.dependencies.platform(libs.koin.bom))")
        assertContains(build, "implementation(libs.koin.core)")
        assertContains(files.getValue("gradle/libs.versions.toml"), "koin-core = { module = \"io.insert-koin:koin-core\" }")
    }

    @Test
    fun `falls back to direct coordinates without a version catalog`() {
        val files = Fixtures.files("app/build.gradle.kts" to Fixtures.androidApp)
        val result = install(files, Fixtures.androidModule, "kotlinx-serialization")
        files.apply(result)
        val build = files.getValue("app/build.gradle.kts")
        assertTrue(Regex("""implementation\("org.jetbrains.kotlinx:kotlinx-serialization-json:[\d.]+"\)""").containsMatchIn(build), build)
        assertContains(build, "id(\"org.jetbrains.kotlin.plugin.serialization\") version")
        assertTrue(result.changes.none { it.path.endsWith(".toml") })
    }

    @Test
    fun `creates sourceSets and handles getting-style source sets`() {
        val build = """
            plugins {
                kotlin("multiplatform")
            }

            kotlin {
                jvm()
                iosArm64()
            }
        """.trimIndent() + "\n"
        val module = ModuleInfo(":shared", "shared/build.gradle.kts", ModuleKind.KMP,
            listOf(Target("jvm", Platform.JVM), Target("iosArm64", Platform.IOS)))
        val files = Fixtures.files("shared/build.gradle.kts" to build, "gradle/libs.versions.toml" to Fixtures.catalog)
        files.apply(install(files, module, "kotlinx-coroutines"))
        val out = files.getValue("shared/build.gradle.kts")
        assertContains(out, "    sourceSets {\n        commonMain.dependencies {\n            implementation(libs.kotlinx.coroutines.core)\n")
        assertContains(out, "jvmMain.dependencies {\n            implementation(libs.kotlinx.coroutines.swing)")
        assertContains(out, "commonTest.dependencies {\n            implementation(libs.kotlinx.coroutines.test)")
        assertFalse(out.contains("coroutines.android"))

        val gettingStyle = """
            kotlin {
                jvm()
                sourceSets {
                    val commonMain by getting {
                        dependencies { implementation(kotlin("stdlib")) }
                    }
                }
            }
        """.trimIndent()
        val files2 = Fixtures.files("shared/build.gradle.kts" to gettingStyle, "gradle/libs.versions.toml" to Fixtures.catalog)
        val jvmOnly = module.copy(targets = listOf(Target("jvm", Platform.JVM)))
        files2.apply(install(files2, jvmOnly, "kermit"))
        assertContains(
            files2.getValue("shared/build.gradle.kts"),
            "dependencies {\n                implementation(kotlin(\"stdlib\"))\n                implementation(libs.kermit)\n            }",
        )
    }

    @Test
    fun `custom-named targets use getByName because KGP generates no accessor`() {
        val build = "kotlin {\n    jvm(\"desktop\")\n    iosArm64()\n\n    sourceSets {\n        commonMain.dependencies {\n        }\n    }\n}\n"
        val module = ModuleInfo(":app", "app/build.gradle.kts", ModuleKind.KMP,
            listOf(Target("desktop", Platform.JVM), Target("iosArm64", Platform.IOS)))
        val files = Fixtures.files("app/build.gradle.kts" to build, "gradle/libs.versions.toml" to Fixtures.catalog)
        files.apply(install(files, module, "ktor-client"))
        val out = files.getValue("app/build.gradle.kts")
        assertContains(out, "        getByName(\"desktopMain\").dependencies {\n            implementation(libs.ktor.client.java)\n")
        assertContains(out, "        iosMain.dependencies {\n")
        assertFalse(out.contains("        desktopMain.dependencies"))

        // The next install finds the getByName block instead of adding a second one.
        files.apply(install(files, module, "kotlinx-coroutines"))
        val again = files.getValue("app/build.gradle.kts")
        assertEquals(1, Regex("""getByName\("desktopMain"\)""").findAll(again).count())
        assertContains(again, "implementation(libs.ktor.client.java)\n            implementation(libs.kotlinx.coroutines.swing)")
    }

    @Test
    fun `unknown library is reported`() {
        val result = install(Fixtures.wizardProject(), Fixtures.composeAppModule, "nope")
        assertTrue(result.hasErrors)
    }
}
