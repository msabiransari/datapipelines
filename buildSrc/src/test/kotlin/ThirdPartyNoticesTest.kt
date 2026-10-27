import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Offline POM fixtures attack the licence guard independently of the real Maven graph. */
class ThirdPartyNoticesTest {
    @TempDir
    lateinit var directory: File

    private val mapping = mapOf("Apache License, Version 2.0" to "Apache-2.0")

    @Test
    fun `an artifact without licences fails with coordinates and the raw absence`() {
        pom("example:unlicensed:1", "")
        val failure = assertThrows(IllegalStateException::class.java) { render("example:unlicensed:1") }
        assertTrue(failure.message.orEmpty().contains("example:unlicensed:1"))
        assertTrue(failure.message.orEmpty().contains("raw POM licenses=<none>"))
    }

    @Test
    fun `an unknown licence fails with coordinates and the raw name URL and comments`() {
        pom("example:unknown:1", license("Mystery Licence", "https://example.test/mystery", "Do not redistribute"))
        val failure = assertThrows(IllegalStateException::class.java) { render("example:unknown:1") }
        listOf("example:unknown:1", "Mystery Licence", "https://example.test/mystery", "Do not redistribute").forEach {
            assertTrue(failure.message.orEmpty().contains(it), failure.message)
        }
    }

    @Test
    fun `an inherited licence resolves through more than one parent`() {
        pom("example:child:1", parent("example", "parent", "1"))
        pom("example:parent:1", parent("example", "grandparent", "1"))
        pom("example:grandparent:1", license("Apache License, Version 2.0"))
        val renderer = renderer()
        val output = renderer.render(listOf("example:child:1"), mapping) { "Apache licence text" }
        assertTrue(output.contains("example:child:1 — Apache-2.0 (Apache License, Version 2.0)"))
        assertEquals(2, renderer.parentWalks)
    }

    @Test
    fun `an unused mapping fails as STALE`() {
        pom("example:licensed:1", license("Apache License, Version 2.0"))
        val failure =
            assertThrows(IllegalStateException::class.java) {
                renderer().render(listOf("example:licensed:1"), mapping + ("Unused" to "MIT")) { "Text" }
            }
        assertTrue(failure.message.orEmpty().contains("STALE"))
        assertTrue(failure.message.orEmpty().contains("Unused"))
    }

    @Test
    fun `POMs without an XML namespace are supported and output is sorted and byte stable`() {
        pom("z:last:1", license("Apache License, Version 2.0"), namespace = false)
        pom("a:first:1", license("Apache License, Version 2.0"))
        val first = renderer().render(listOf("z:last:1", "a:first:1", "z:last:1"), mapping) { "Text\n" }
        val second = renderer().render(listOf("a:first:1", "z:last:1"), mapping) { "Text\n" }
        assertEquals(first, second)
        assertTrue(first.indexOf("a:first:1") < first.indexOf("z:last:1"))
        assertEquals(1, Regex("===== Apache-2.0 =====").findAll(first).count())
    }

    @Test
    fun `a child's declared licence overrides its parent's licence`() {
        pom("example:child:1", parent("example", "parent", "1") + license("Apache License, Version 2.0"))
        pom("example:parent:1", license("Unknown parent licence"))
        assertTrue(render("example:child:1").contains("Apache-2.0"))
    }

    @Test
    fun `cyclic parents fail naming the original artifact`() {
        pom("example:child:1", parent("example", "parent", "1"))
        pom("example:parent:1", parent("example", "child", "1"))
        val failure = assertThrows(IllegalStateException::class.java) { render("example:child:1") }
        assertTrue(failure.message.orEmpty().contains("Cyclic POM parents for example:child:1"))
    }

    @Test
    fun `external entities are refused before they can read a local file`() {
        val secret = directory.resolve("secret.txt").apply { writeText("PRIVATE") }
        directory.resolve("example_xxe_1.pom").writeText(
            """<!DOCTYPE project [<!ENTITY steal SYSTEM "${secret.toURI()}">]><project><name>&steal;</name></project>""",
        )
        assertThrows(org.xml.sax.SAXParseException::class.java) { render("example:xxe:1") }
    }

    @Test
    fun `a missing parent does not silently produce a notice`() {
        pom("example:child:1", parent("example", "missing", "1"))
        val failure = assertThrows(IllegalStateException::class.java) { render("example:child:1") }
        assertTrue(failure.message.orEmpty().contains("Missing fixture example:missing:1"))
    }

    @Test
    fun `declared alternatives and exceptions retain every text section`() {
        pom(
            "example:dual:1",
            """
            <licenses><license><name>Apache License, Version 2.0</name></license>
            <license><name>GPL2 w/ CPE</name></license></licenses>
            """.trimIndent(),
        )
        val output =
            renderer().render(
                listOf("example:dual:1"),
                mapping + ("GPL2 w/ CPE" to "GPL-2.0-only WITH Classpath-exception-2.0"),
            ) { "Text for $it" }
        listOf("Apache-2.0", "GPL-2.0-only", "Classpath-exception-2.0").forEach {
            assertTrue(output.contains("===== $it =====\n"))
            assertTrue(output.contains("Text for $it"))
        }
    }

    @Test
    fun `both isolate coordinates use the Dockerfile ARG and the pin equals the catalog`() {
        val catalog = File(requireNotNull(System.getProperty("repo.catalog")))
        val root = catalog.parentFile.parentFile
        val version = requireNotNull(Regex("""(?m)^graalvm = "([^"]+)"""").find(catalog.readText())).groupValues[1]
        assertEquals(
            listOf(
                "org.graalvm.js:js-isolate-linux-amd64-community:$version",
                "org.graalvm.js:js-isolate-linux-aarch64-community:$version",
            ),
            ThirdPartyNotices.isolates(root.resolve("Dockerfile").readText()),
        )
        assertEquals(
            listOf("org.graalvm.js:js-isolate-linux-amd64-community:99.1", "org.graalvm.js:js-isolate-linux-aarch64-community:99.1"),
            ThirdPartyNotices.isolates("ARG GRAALJS_VERSION=99.1\n"),
        )
    }

    @Test
    fun `missing duplicate or interpolated isolate pins fail closed`() {
        listOf("", "ARG GRAALJS_VERSION=1.0\nARG GRAALJS_VERSION=2.0\n", "ARG GRAALJS_VERSION=\${OTHER}\n").forEach {
            assertThrows(IllegalStateException::class.java) { ThirdPartyNotices.isolates(it) }
        }
    }

    @Test
    fun `driver allowances are present only when their flag is enabled`() {
        val mysql = "The GNU General Public License, v2 with Universal FOSS Exception, v1.0"
        val oracle = "Oracle Free Use Terms and Conditions (FUTC)"
        assertFalse(ThirdPartyLicenseAllowlist.mappings(mysql = false, oracle = false).containsKey(mysql))
        assertFalse(ThirdPartyLicenseAllowlist.mappings(mysql = false, oracle = false).containsKey(oracle))
        assertEquals(
            "GPL-2.0-only WITH Universal-FOSS-exception-1.0",
            ThirdPartyLicenseAllowlist.mappings(mysql = true, oracle = false)[mysql],
        )
        assertEquals("LicenseRef-Oracle-FUTC", ThirdPartyLicenseAllowlist.mappings(mysql = false, oracle = true)[oracle])
    }

    @Test
    fun `the real DuckDB pins yield four exact non-POM coordinates`() {
        val root = File(requireNotNull(System.getProperty("repo.catalog"))).parentFile.parentFile
        val rows =
            ThirdPartyNotices.duckdbExtensions(
                root.resolve("Dockerfile").readText(),
                root.resolve("deploy/duckdb-extensions.sha256").readText(),
            )
        assertEquals(
            listOf("avro:v1.5.5+f9d5902", "aws:v1.5.5+efa54a9", "httpfs:v1.5.5+827222f", "iceberg:v1.5.5+45163a28"),
            rows.map { it.coordinate.removePrefix("duckdb-extension:") },
        )
    }

    @Test
    fun `missing extra and duplicate checksum entries fail naming the extension`() {
        val line = checksums().lineSequence().single { it.contains("linux_amd64/httpfs.") }
        listOf(checksums().replace("$line\n", ""), checksums() + "$line\n", checksums() + line.replace("httpfs.", "mystery.")).forEach {
            val failure = assertThrows(IllegalStateException::class.java) { extensions(it) }
            assertTrue(failure.message.orEmpty().contains("httpfs") || failure.message.orEmpty().contains("mystery"), failure.message)
        }
    }

    @Test
    fun `a Dockerfile name without checksum coverage and an unlisted name both fail`() {
        listOf(fixtureDockerfile.replace("httpfs", "mystery"), fixtureDockerfile.replace("httpfs ", "")).forEach {
            val failure = assertThrows(IllegalStateException::class.java) { ThirdPartyNotices.duckdbExtensions(it, checksums()) }
            assertTrue(failure.message.orEmpty().contains("httpfs"), failure.message)
        }
    }

    @Test
    fun `missing duplicate and changed footer commit annotations fail naming the extension`() {
        listOf(
            checksums().replace("# httpfs commit=827222f\n", ""),
            checksums() + "# httpfs commit=827222f\n",
            checksums().replace("commit=827222f", "commit=aaaaaaa"),
        ).forEach {
            val failure = assertThrows(IllegalStateException::class.java) { extensions(it) }
            assertTrue(failure.message.orEmpty().contains("httpfs"), failure.message)
        }
    }

    @Test
    fun `a missing extension licence entry fails naming the extension`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                ThirdPartyNotices.duckdbExtensions(fixtureDockerfile, checksums(), DuckDbExtensionLicenses.inventory() - "httpfs")
            }
        assertTrue(failure.message.orEmpty().contains("DuckDB licence inventory mismatch: missing=[httpfs]"), failure.message)
    }

    @Test
    fun `an unknown component licence fails before rendering even with a permissive text resolver`() {
        val inventory = DuckDbExtensionLicenses.inventory().toMutableMap()
        inventory["httpfs"] =
            inventory.getValue("httpfs").copy(
                components = listOf(DuckDbComponent("unknown", "Unknown-ID", "https://example.test")),
            )
        val failure =
            assertThrows(IllegalStateException::class.java) {
                ThirdPartyNotices.duckdbExtensions(fixtureDockerfile, checksums(), inventory)
            }
        assertEquals("Unknown DuckDB component licence: Unknown-ID", failure.message)
    }

    @Test
    fun `DuckDB output is byte stable with every combined and alternative licence text once`() {
        val first = renderer().render(emptyList(), emptyMap(), extensions()) { "Text for $it" }
        val reversed = extensions(checksums().lines().reversed().joinToString("\n"))
        val second = renderer().render(emptyList(), emptyMap(), reversed) { "Text for $it" }
        assertEquals(first, second)
        listOf("Apache-2.0", "MIT", "curl", "ISC", "BSD-3-Clause", "Zlib").forEach {
            assertEquals(1, Regex("===== $it =====").findAll(first).count())
        }
        assertTrue(first.contains("curl AND ISC AND BSD-3-Clause"))
        assertTrue(first.contains("Apache-2.0 OR MIT"))
        assertEquals(4, first.lineSequence().count { it.startsWith("duckdb-extension:") })
        assertTrue(first.contains("Copyright 2018-2024 Stichting DuckDB Foundation"))
    }

    @Test
    fun `malformed sums wrong core paths and absent platform pins fail closed`() {
        listOf(
            checksums().replace("a".repeat(64), "xyz"),
            checksums().replace("v1.5.5/", "v9.9/"),
            checksums().lines().filterNot { it.contains("linux_arm64") }.joinToString("\n"),
        ).forEach {
            assertThrows(IllegalStateException::class.java) { extensions(it) }
        }
    }

    @Test
    fun `missing duplicate and interpolated DuckDB core versions fail closed`() {
        listOf(
            "",
            fixtureDockerfile + "ARG DUCKDB_CORE_VERSION=9.0\n",
            fixtureDockerfile.replace("=1.5.5", "=\${OTHER}"),
            fixtureDockerfile + "ARG DUCKDB_CORE_VERSION=\${OTHER}\n",
        ).forEach {
            assertThrows(IllegalStateException::class.java) { ThirdPartyNotices.duckdbExtensions(it, checksums()) }
        }
    }

    private val fixtureDockerfile = "ARG DUCKDB_CORE_VERSION=1.5.5\nfor ext in httpfs aws iceberg avro; do\n"

    private fun checksums(): String =
        buildString {
            mapOf("httpfs" to "827222f", "aws" to "efa54a9", "iceberg" to "45163a28", "avro" to "f9d5902").forEach { (name, commit) ->
                append("# $name commit=$commit\n")
                listOf("linux_amd64", "linux_arm64").forEach { append("${"a".repeat(64)}  v1.5.5/$it/$name.duckdb_extension\n") }
            }
        }

    private fun extensions(checksums: String = checksums()): List<DuckDbExtensionNotice> =
        ThirdPartyNotices.duckdbExtensions(fixtureDockerfile, checksums)

    private fun render(coordinate: String): String = renderer().render(listOf(coordinate), mapping) { "Licence text" }

    private fun renderer(): ThirdPartyNotices =
        ThirdPartyNotices { coordinate ->
            directory.resolve(coordinate.replace(':', '_') + ".pom").also { check(it.isFile) { "Missing fixture $coordinate" } }
        }

    private fun pom(
        coordinate: String,
        body: String,
        namespace: Boolean = true,
    ) {
        val ns = if (namespace) " xmlns=\"http://maven.apache.org/POM/4.0.0\"" else ""
        directory.resolve(coordinate.replace(':', '_') + ".pom").writeText("<project$ns><url>https://example.test</url>$body</project>")
    }

    private fun license(
        name: String,
        url: String = "https://example.test/license",
        comments: String = "",
    ): String = "<licenses><license><name>$name</name><url>$url</url><comments>$comments</comments></license></licenses>"

    private fun parent(
        group: String,
        name: String,
        version: String,
    ): String = "<parent><groupId>$group</groupId><artifactId>$name</artifactId><version>$version</version></parent>"
}
