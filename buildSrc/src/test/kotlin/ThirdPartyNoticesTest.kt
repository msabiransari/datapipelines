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
    fun `the upstream ThreeTen BSD spelling renders its recognised SPDX text`() {
        val coordinate = "org.threeten:threeten-extra:1.7.1"
        pom(coordinate, license("BSD 3-clause", "https://raw.githubusercontent.com/ThreeTen/threeten-extra/master/LICENSE.txt"))
        val names = ThirdPartyLicenseAllowlist.mappings(mysql = false, oracle = false).filterKeys { it == "BSD 3-clause" }
        val output = renderer().render(listOf(coordinate), names) { "BSD three-clause fixture text" }
        assertTrue(output.contains("$coordinate — BSD-3-Clause (BSD 3-clause)"))
        assertTrue(output.contains("===== BSD-3-Clause ====="))
        assertTrue(output.contains("BSD three-clause fixture text"))
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
        val rows =
            ThirdPartyNotices.duckdbExtensions(
                root().resolve("Dockerfile").readText(),
                root().resolve("deploy/duckdb-extensions.sha256").readText(),
                closures(),
                catalogDuckDbJdbc(),
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
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    ThirdPartyNotices.duckdbExtensions(it, checksums(), closures(), FIXTURE_JDBC)
                }
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
                ThirdPartyNotices.duckdbExtensions(
                    fixtureDockerfile,
                    checksums(),
                    closures(),
                    FIXTURE_JDBC,
                    DuckDbExtensionLicenses.inventory() - "httpfs",
                )
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
                ThirdPartyNotices.duckdbExtensions(fixtureDockerfile, checksums(), closures(), FIXTURE_JDBC, inventory)
            }
        assertEquals("Unknown DuckDB component licence: Unknown-ID", failure.message)
    }

    @Test
    fun `DuckDB output is byte stable with every combined and alternative licence text once`() {
        val first = renderer().render(emptyList(), emptyMap(), extensions()) { "Text for $it" }
        val reversed = extensions(checksums().lines().reversed().joinToString("\n"))
        val second = renderer().render(emptyList(), emptyMap(), reversed) { "Text for $it" }
        assertEquals(first, second)
        listOf(
            "0BSD",
            "Apache-2.0",
            "BSD-2-Clause",
            "BSD-3-Clause",
            "BSL-1.0",
            "GPL-2.0-or-later",
            "ISC",
            "LicenseRef-utf8proc-Unicode-data",
            "MIT",
            "PostgreSQL",
            "Zlib",
            "curl",
            "dtoa",
        ).forEach {
            assertEquals(1, Regex("===== $it =====").findAll(first).count(), it)
        }
        assertTrue(first.contains("curl AND ISC AND BSD-3-Clause"))
        assertTrue(first.contains("Apache-2.0 OR MIT"))
        assertEquals(4, first.lineSequence().count { it.startsWith("duckdb-extension:") })
        assertTrue(first.contains("Copyright 2018-2024 Stichting DuckDB Foundation"))
    }

    @Test
    fun `a closure file bound to another commit fails naming the extension`() {
        val failure =
            assertThrows(IllegalStateException::class.java) {
                extensions(closures = closure("httpfs") { it.replace("commit=827222f", "commit=aaaaaaa") })
            }
        assertTrue(failure.message.orEmpty().startsWith("DuckDB closure commit mismatch for httpfs"), failure.message)
    }

    @Test
    fun `a shipped closure entry without a reviewed licence fails naming the port`() {
        val mystery = "mystery\t1.0#0\tMIT\thttps://example.test\tlinked\n"
        val failure =
            assertThrows(IllegalStateException::class.java) {
                extensions(
                    closures =
                        closure("httpfs") {
                            it.replace(
                                "openssl\t",
                                mystery + "openssl\t",
                            )
                        },
                )
            }
        assertEquals("DuckDB closure entry without a reviewed licence for httpfs: mystery", failure.message)
    }

    @Test
    fun `an inventory component the closure lacks fails naming the port`() {
        val inventory = DuckDbExtensionLicenses.inventory().toMutableMap()
        val httpfs = inventory.getValue("httpfs")
        inventory["httpfs"] = httpfs.copy(components = httpfs.components + DuckDbComponent("mystery", "MIT", "https://example.test"))
        val failure =
            assertThrows(IllegalStateException::class.java) {
                ThirdPartyNotices.duckdbExtensions(fixtureDockerfile, checksums(), closures(), FIXTURE_JDBC, inventory)
            }
        assertEquals("DuckDB inventory component not in closure for httpfs: mystery", failure.message)
    }

    @Test
    fun `a closure row whose licence or source drifted from the review fails naming the port`() {
        listOf(
            closure("httpfs") { it.replace("\tcurl AND ISC AND BSD-3-Clause\t", "\tcurl\t") },
            closure("httpfs") { it.replace("curl/tree/curl-8_17_0", "curl/tree/curl-8_18_0") },
        ).forEach { closures ->
            val failure = assertThrows(IllegalStateException::class.java) { extensions(closures = closures) }
            assertTrue(
                failure.message.orEmpty().startsWith("DuckDB closure entry differs from its reviewed licence for httpfs: curl"),
                failure.message,
            )
        }
    }

    @Test
    fun `malformed unsorted unjustified and missing closure files fail closed`() {
        val row = "Invalid DuckDB closure row for httpfs"
        val header = "Invalid DuckDB closure header for httpfs"
        val order = "DuckDB closure rows must be sorted and unique for httpfs"
        listOf(
            closure("httpfs") { it.replace("\tlinked\n", "\tstatic\n") } to row,
            closure("httpfs") { it.replace("\tlinked\n", "\textra\tlinked\n") } to row,
            closure("httpfs") { it.replace(Regex("# why: vcpkg-cmake .*\n"), "") } to
                "DuckDB build-only row without a why comment for httpfs: vcpkg-cmake",
            closure("httpfs") { text ->
                text.lines().partition { it.startsWith("zlib\t") }.let { (zlib, rest) ->
                    (rest.take(1) + zlib + rest.drop(1)).joinToString("\n")
                }
            } to order,
            closure("httpfs") { it.replace(Regex("(?m)^(zlib\t.*\n)"), "$1$1") } to order,
            closure("httpfs") { it.replace("\n", "\r\n") } to header,
            closure("httpfs") { it.removeSuffix("\n") } to header,
            closures() - "httpfs" to "DuckDB closure files mismatch: missing=[httpfs]",
        ).forEach { (closures, message) ->
            val failure = assertThrows(IllegalStateException::class.java) { extensions(closures = closures) }
            assertTrue(failure.message.orEmpty().startsWith(message), failure.message)
        }
    }

    @Test
    fun `build-only rows are reported without licence rows or texts`() {
        val tool = "# why: mytool build helper\nmytool\t1.0#0\tLicenseRef-Never-Shipped\thttps://example.test\tbuild-only\n"
        val notices =
            renderer().render(
                emptyList(),
                emptyMap(),
                extensions(
                    closures =
                        closure("httpfs") {
                            it.replace(
                                "openssl\t",
                                tool + "openssl\t",
                            )
                        },
                ),
            ) { "Text for $it" }
        assertTrue(notices.contains("Build-only (not distributed): mytool 1.0#0, vcpkg-cmake 2025-08-07#0"), notices)
        assertFalse(notices.contains("LicenseRef-Never-Shipped"))
        assertFalse(notices.lineSequence().any { it.startsWith("mytool") })
    }

    @Test
    fun `component sections name the transitive path and the closure inputs`() {
        val notices = renderer().render(emptyList(), emptyMap(), extensions()) { "Text for $it" }
        listOf(
            "----- duckdb-extension:httpfs:v1.5.5+827222f: statically linked components (direct and transitive) -----",
            "zlib 1.3.1#0 — Zlib — https://github.com/madler/zlib/tree/v1.3.1 — via curl",
            "curl 8.17.0#0 — curl AND ISC AND BSD-3-Clause — https://github.com/curl/curl/tree/curl-8_17_0\n",
            "duckdb/re2 1.5.5 — BSD-3-Clause — https://github.com/duckdb/duckdb/tree/v1.5.5/third_party/re2 — via duckdb",
            "zlib 1.3.1#0 — Zlib — https://github.com/madler/zlib/tree/v1.3.1 — via avro-c, aws-sdk-cpp, curl",
            "Closure: vcpkg baseline 84bab45d415d22042bd0b9081aea57f362da3f35;",
        ).forEach { assertTrue(notices.contains(it), it) }
        assertFalse(notices.contains("direct vcpkg dependencies"))
    }

    @Test
    fun `grouped licence expressions are accepted only when balanced`() {
        assertEquals(listOf("BSD-2-Clause", "BSL-1.0", "Zlib"), DuckDbExtensionLicenses.licenseIds("(BSD-2-Clause OR BSL-1.0) AND Zlib"))
        listOf("(MIT", "MIT)", ")MIT(", "MIT AND").forEach {
            assertThrows(IllegalStateException::class.java) { DuckDbExtensionLicenses.licenseIds(it) }
        }
    }

    @Test
    fun `a closure directory with anything but tsv files fails`() {
        directory.resolve("closures").mkdirs()
        directory.resolve("closures/httpfs.tsv").writeText("# httpfs\n")
        directory.resolve("closures/notes.md").writeText("stray\n")
        assertThrows(IllegalStateException::class.java) { ThirdPartyNotices.closureFiles(directory.resolve("closures")) }
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
    fun `the Dockerfile core ARG is the catalog duckdb-jdbc's first three components`() {
        val real = root().resolve("Dockerfile").readText()
        val arg = requireNotNull(Regex("(?m)^ARG DUCKDB_CORE_VERSION=(\\S+)$").find(real)).groupValues[1]
        assertEquals(catalogDuckDbJdbc().split('.').take(3).joinToString("."), arg)
        listOf(
            extensionsFailure(jdbc = "1.5.6.0") to "(expected 1.5.6)",
            extensionsFailure(dockerfile = fixtureDockerfile.replace("=1.5.5\n", "=1.5.6\n")) to "DUCKDB_CORE_VERSION 1.5.6 ",
            extensionsFailure(jdbc = "2.0.0") to "(expected 2.0.0)",
        ).forEach { (failure, detail) ->
            assertTrue(failure.startsWith("DUCKDB_CORE_VERSION "), failure)
            assertTrue(failure.contains(" is not the core of the catalog's duckdb-jdbc "), failure)
            assertTrue(failure.contains(detail), failure)
        }
        listOf("1.5", "", "\${OTHER}", "1.5.5.1-SNAPSHOT").forEach {
            assertEquals("Invalid catalog duckdb-jdbc version: $it", extensionsFailure(jdbc = it))
        }
    }

    @Test
    fun `a reviewed core tag other than the Dockerfile's core fails naming the extension and component`() {
        listOf(
            inventoryWithCoreSource("httpfs") { it.replace("/tree/v1.5.5", "/tree/v1.5.6") } to "duckdb (",
            inventoryWithCoreSource("iceberg") { it.replace("/tree/v1.5.5/third_party/re2", "/tree/v1.5.55/third_party/re2") } to
                "duckdb/re2 (",
        ).forEach { (inventory, component) ->
            val failure = extensionsFailure(inventory = inventory)
            assertTrue(failure.startsWith("DuckDB reviewed licence tag is not core v1.5.5 for "), failure)
            assertTrue(failure.contains(component), failure)
        }
    }

    @Test
    fun `a closure row for DuckDB naming another core version fails naming the row`() {
        val failure = extensionsFailure(closures = closure("httpfs") { it.replace("duckdb/re2\t1.5.5\t", "duckdb/re2\t1.5.6\t") })
        assertEquals("DuckDB closure row names core 1.5.6, not v1.5.5, for httpfs: duckdb/re2", failure)
        assertEquals(
            "DuckDB closure row names core 1.5.4, not v1.5.5, for avro: duckdb",
            extensionsFailure(closures = closure("avro") { it.replace("\nduckdb\t1.5.5\t", "\nduckdb\t1.5.4\t") }),
        )
    }

    private fun extensionsFailure(
        closures: Map<String, String> = closures(),
        jdbc: String = FIXTURE_JDBC,
        dockerfile: String = fixtureDockerfile,
        inventory: Map<String, DuckDbExtensionLicense> = DuckDbExtensionLicenses.inventory(),
    ): String =
        assertThrows(IllegalStateException::class.java) {
            extensions(closures = closures, jdbc = jdbc, dockerfile = dockerfile, inventory = inventory)
        }.message.orEmpty()

    @Test
    fun `missing duplicate and interpolated DuckDB core versions fail closed`() {
        listOf(
            "",
            fixtureDockerfile + "ARG DUCKDB_CORE_VERSION=9.0\n",
            fixtureDockerfile.replace("=1.5.5", "=\${OTHER}"),
            fixtureDockerfile + "ARG DUCKDB_CORE_VERSION=\${OTHER}\n",
        ).forEach {
            assertThrows(IllegalStateException::class.java) {
                ThirdPartyNotices.duckdbExtensions(it, checksums(), closures(), FIXTURE_JDBC)
            }
        }
    }

    private val fixtureDockerfile = "ARG DUCKDB_CORE_VERSION=1.5.5\nfor ext in httpfs aws iceberg avro; do\n"

    /** The catalog's `duckdb-jdbc`, read the way the build reads it — the one hand-edited DuckDB version (#781). */
    private fun catalogDuckDbJdbc(): String =
        requireNotNull(Regex("""(?m)^duckdb-jdbc = "([^"]+)"""").find(root().resolve("gradle/libs.versions.toml").readText())) {
            "no duckdb-jdbc version in the catalog"
        }.groupValues[1]

    /** The inventory with every reviewed `duckdb`-tree source of [name] rewritten by [edit]. */
    private fun inventoryWithCoreSource(
        name: String,
        edit: (String) -> String,
    ): Map<String, DuckDbExtensionLicense> {
        val inventory = DuckDbExtensionLicenses.inventory().toMutableMap()
        val license = inventory.getValue(name)
        inventory[name] =
            license.copy(
                components =
                    license.components.map {
                        if (it.name == "duckdb" || it.name.startsWith("duckdb/")) it.copy(source = edit(it.source)) else it
                    },
            )
        check(inventory != DuckDbExtensionLicenses.inventory()) { "edit missed" }
        return inventory
    }

    private fun checksums(): String =
        buildString {
            mapOf("httpfs" to "827222f", "aws" to "efa54a9", "iceberg" to "45163a28", "avro" to "f9d5902").forEach { (name, commit) ->
                append("# $name commit=$commit\n")
                listOf("linux_amd64", "linux_arm64").forEach { append("${"a".repeat(64)}  v1.5.5/$it/$name.duckdb_extension\n") }
            }
        }

    private fun extensions(
        checksums: String = checksums(),
        closures: Map<String, String> = closures(),
        jdbc: String = FIXTURE_JDBC,
        dockerfile: String = fixtureDockerfile,
        inventory: Map<String, DuckDbExtensionLicense> = DuckDbExtensionLicenses.inventory(),
    ): List<DuckDbExtensionNotice> = ThirdPartyNotices.duckdbExtensions(dockerfile, checksums, closures, jdbc, inventory)

    private fun root(): File = File(requireNotNull(System.getProperty("repo.catalog"))).parentFile.parentFile

    private fun closures(): Map<String, String> = ThirdPartyNotices.closureFiles(root().resolve("deploy/duckdb-extension-ports"))

    private fun closure(
        name: String,
        edit: (String) -> String,
    ): Map<String, String> =
        closures().let {
            it +
                (name to edit(it.getValue(name)).also { edited -> check(edited != it[name]) { "edit missed" } })
        }

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

    private companion object {
        /** A catalog version whose core is the fixture Dockerfile's (and the committed closures') 1.5.5. */
        const val FIXTURE_JDBC = "1.5.5.1"
    }
}
