package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * #790 — [DuckDbDefaultCatalog] is DERIVED, the pinned engine is the oracle: every URL form is opened on a scratch
 * engine (files under a temp directory, nothing else attached, extension install and auto-load off) and its
 * `current_database()` is held EQUAL to what the function answers from the text alone. A form whose rule breaks is
 * named in the failure (the maps are keyed by form).
 *
 * The forms the function refuses to know are asserted separately and never opened here: the engine would load an
 * extension for them (`md:` reaches the network), or open a file relative to the working directory.
 */
class DuckDbDefaultCatalogTest {
    @TempDir
    lateinit var dir: Path

    private fun observed(url: String): String {
        val properties =
            Properties().apply {
                setProperty("autoinstall_known_extensions", "false")
                setProperty("autoload_known_extensions", "false")
            }
        return DriverManager.getConnection(url, properties).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT current_database()").use { rows ->
                    check(rows.next()) { "current_database() returned no row" }
                    rows.getString(1)
                }
            }
        }
    }

    @Test
    fun `the derived default catalog equals the engine's current_database for every known URL form`() {
        Files.createDirectories(dir.resolve("nested"))
        val forms =
            linkedMapOf(
                "anonymous" to "jdbc:duckdb:",
                "anonymous memory" to "jdbc:duckdb::memory:",
                "labelled memory" to "jdbc:duckdb:memory:lbl790",
                "named memory" to "jdbc:duckdb::memory:lbl790",
                "memory prefix, no label" to "jdbc:duckdb:memory:",
                "anonymous with properties" to "jdbc:duckdb::memory:;threads=1",
                "blank" to "jdbc:duckdb: ",
                "file" to "jdbc:duckdb:$dir/shop.db",
                "file, mixed case" to "jdbc:duckdb:$dir/Shop.DuckDB",
                "file, no extension" to "jdbc:duckdb:$dir/noext",
                "file, several dots" to "jdbc:duckdb:$dir/two.dots.db",
                "file, leading dot" to "jdbc:duckdb:$dir/.hidden",
                "file, trailing dot" to "jdbc:duckdb:$dir/x.",
                "file, doubled dots" to "jdbc:duckdb:$dir/x..y",
                "file, nested" to "jdbc:duckdb:$dir/nested/inner.db",
                "file, parent segment" to "jdbc:duckdb:$dir/nested/../up.db",
                "file, property tail" to "jdbc:duckdb:$dir/props.db;threads=1",
                "file, query tail" to "jdbc:duckdb:$dir/query.db?threads=1",
                "file, query tail before a dot" to "jdbc:duckdb:$dir/q?b.c",
                "file, colon in the name" to "jdbc:duckdb:$dir/a:b.db",
                "file, upper-case memory literal" to "jdbc:duckdb:$dir/:MEMORY:",
                "file, named memory" to "jdbc:duckdb:$dir/memory.db",
                "file, reserved main" to "jdbc:duckdb:$dir/main.db",
                "file, reserved temp" to "jdbc:duckdb:$dir/temp",
                "file, reserved system" to "jdbc:duckdb:$dir/system.db",
                "file, reserved name in another case" to "jdbc:duckdb:$dir/Main.db",
                "file, space in the name" to "jdbc:duckdb:$dir/sp ace.db",
            )

        val engine = forms.mapValues { (_, url) -> observed(url) }
        engine.forEach { (form, catalog) -> println("#790 observed current_database() for $form: $catalog") }

        forms.mapValues { (_, url) -> DuckDbDefaultCatalog.of(url) } shouldBe engine
    }

    @Test
    fun `the four forms the brief names - anonymous, anonymous memory and labelled memory are memory, a file its stem`() {
        DuckDbDefaultCatalog.of("jdbc:duckdb:") shouldBe "memory"
        DuckDbDefaultCatalog.of("jdbc:duckdb::memory:") shouldBe "memory"
        DuckDbDefaultCatalog.of("jdbc:duckdb:memory:lbl") shouldBe "memory"
        DuckDbDefaultCatalog.of("jdbc:duckdb:/var/lib/lake/shop.db") shouldBe "shop"
    }

    @Test
    fun `a shape the function does not know answers null, never a guess`() {
        listOf(
            "jdbc:duckdb:md:",
            "jdbc:duckdb:md:my_db",
            "jdbc:duckdb:MEMORY:lbl",
            "jdbc:duckdb:file:/var/lib/lake/shop.db",
            "jdbc:duckdb:s3://bucket/shop.db",
            "jdbc:duckdb:relative:shop.db",
            "jdbc:duckdb: /var/lib/lake/shop.db",
            "jdbc:duckdb:/var/lib/lake/shop.db ",
            "jdbc:duckdb:/",
            "jdbc:duckdb:/var/lib/lake/..",
            "jdbc:DUCKDB:",
            "jdbc:postgresql://host/db",
            "",
        ).forEach { url -> DuckDbDefaultCatalog.of(url) shouldBe null }
    }
}
