package io.github.kmphub.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScannerAndDoctorTest {

    @Test
    fun `scanner finds modules, kinds and targets`() {
        val files = Fixtures.wizardProject() + mapOf(
            "shared/build.gradle.kts" to "plugins { kotlin(\"multiplatform\") }\nkotlin {\n    jvm()\n    wasmJs { browser() }\n}\n",
            "server/build.gradle.kts" to "plugins {\n    alias(libs.plugins.kotlinJvm)\n    application\n}\n",
        )
        val modules = ProjectScanner.scan(files::get).associateBy { it.path }

        assertEquals(ModuleKind.UNKNOWN, modules.getValue(":").kind, "root only declares plugins with apply false")
        assertEquals(Fixtures.composeAppModule.targets, modules.getValue(":composeApp").targets)
        assertEquals(ModuleKind.KMP, modules.getValue(":composeApp").kind)
        assertEquals(listOf("jvm", "wasmJs"), modules.getValue(":shared").targets.map { it.name })
        assertEquals(ModuleKind.JVM, modules.getValue(":server").kind)
    }

    @Test
    fun `doctor flags platform-incompatible commonMain dependencies and updates`() {
        val catalog = LibraryCatalog.bundled()
        val files = Fixtures.wizardProject()
        files["gradle/libs.versions.toml"] = Fixtures.catalog
            .replace("[libraries]\n", "[libraries]\nretrofit = { module = \"com.squareup.retrofit2:retrofit\", version = \"2.9.0\" }\n" +
                "okhttp-engine = \"io.ktor:ktor-client-okhttp:3.0.0\"\n")
        files["composeApp/build.gradle.kts"] = Fixtures.composeApp.replace(
            "implementation(compose.material3)",
            "implementation(compose.material3)\n            implementation(libs.retrofit)\n            implementation(libs.okhttp.engine)",
        )
        val findings = Doctor(catalog).check(listOf(Fixtures.composeAppModule), files::get)
        val text = findings.joinToString("\n")

        assertContains(text, "com.squareup.retrofit2:retrofit in commonMain doesn't support ios")
        assertContains(text, "io.ktor:ktor-client-okhttp is android-only but sits in commonMain")
        assertContains(text, "Retrofit: 2.9.0 ->")
        assertEquals(Severity.ERROR, findings.first().severity)
    }

    @Test
    fun `doctor catches legacy KSP built for another Kotlin`() {
        val toml = Fixtures.catalog.replace("[versions]\n", "[versions]\nksp = \"2.1.0-1.0.29\"\n") +
            "ksp = { id = \"com.google.devtools.ksp\", version.ref = \"ksp\" }\n"
        val findings = Doctor(LibraryCatalog.bundled()).check(emptyList(), mapOf("gradle/libs.versions.toml" to toml)::get)
        assertTrue(findings.any { it.text.contains("KSP 2.1.0-1.0.29 is built for Kotlin 2.1.0") }, findings.toString())
    }

    @Test
    fun `build script parser ignores braces in strings and comments`() {
        val script = KtsBuildScript.parse(Fixtures.composeApp)
        assertEquals(listOf("plugins", "kotlin", "android"), script.root.children.map { it.header })
        assertEquals("defaultConfig", script.topLevel("android")!!.children.single().header)
    }

    @Test
    fun `version comparison`() {
        assertTrue(Versions.compare("3.3.0", "3.10.0") < 0)
        assertTrue(Versions.compare("2.0.0-beta01", "2.0.0") < 0)
        assertTrue(Versions.compare("1.9", "1.9.1") < 0)
        assertEquals(0, Versions.compare("2.1.0", "2.1.0"))
    }

    @Test
    fun `unified diff shows added lines`() {
        val diff = UnifiedDiff.render(FileChange("a.kts", "a\nb\nc\n", "a\nb\nx\nc\n"))
        assertContains(diff, "@@ -1,4 +1,5 @@")
        assertContains(diff, "+x")
    }

    @Test
    fun `every bundled library parses with consistent versions`() {
        val catalog = LibraryCatalog.bundled()
        assertTrue(catalog.libraries.size >= 15)
        assertEquals(catalog.libraries.size, catalog.libraries.map { it.id }.toSet().size)
    }
}
