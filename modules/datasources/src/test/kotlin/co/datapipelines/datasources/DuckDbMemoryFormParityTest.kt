package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * #941 — [JdbcUrlForm]'s DuckDB verdict is held EQUAL to what the pinned driver does with the same URL: the real
 * driver opens each form in a FORKED JVM whose working directory is a fresh JUnit temporary directory (DuckDB
 * resolves a relative database name against the process working directory, which a test cannot change in its own
 * JVM), and the files it leaves there are the observation. A form the classifier calls in-process MEMORY must leave
 * no file; a form it calls an in-process FILE must leave exactly the file the classifier named, so the file-roots
 * rule vets the very path the driver opens. A driver upgrade that changes either reading turns this red, keyed by form.
 *
 * The labelled memory forms are pinned separately: the driver opens them in memory, the classifier calls them files
 * (and the roots rule refuses them) — a stricter verdict than the driver's, recorded so a change on either side is seen.
 */
class DuckDbMemoryFormParityTest {
    @TempDir
    lateinit var dir: Path

    /** What one form did in a forked JVM: the files its working directory holds afterwards, and its catalog. */
    private data class Observed(
        val files: List<String>,
        val catalog: String,
    )

    private fun observe(
        label: String,
        url: String,
    ): Observed {
        val workDir = Files.createDirectories(dir.resolve("form-${label.replace(Regex("[^a-z0-9]+"), "-")}"))
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow { IllegalStateException("no java command") }
        val process =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Probe::class.java.name, url)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start()
        check(process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "the probe for $label did not finish in $PROBE_TIMEOUT_SECONDS s"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "the probe for $label exited ${process.exitValue()}: $output" }
        val catalog =
            output.lineSequence().firstOrNull { it.startsWith(CATALOG_PREFIX) }?.removePrefix(CATALOG_PREFIX)
                ?: error("the probe for $label printed no catalog: $output")
        return Observed(names(workDir), catalog)
    }

    private fun names(directory: Path): List<String> =
        directory
            .listDirectoryEntries()
            .map { it.name }
            .sorted()

    @Test
    fun `the classifier's memory-or-file verdict equals what the pinned driver opens, for every case variant`() {
        val forms =
            linkedMapOf(
                "anonymous" to "jdbc:duckdb:",
                "memory, lower case" to "jdbc:duckdb::memory:",
                "memory, upper case" to "jdbc:duckdb::MEMORY:",
                "memory, mixed case" to "jdbc:duckdb::Memory:",
                "memory, mixed case with a tail" to "jdbc:duckdb::Memory:x941",
                "relative file" to "jdbc:duckdb:rel941.db",
            )
        val workingDirBefore = names(Path.of("").toAbsolutePath())

        val driver = forms.mapValues { (label, url) -> observe(label, url) }
        driver.forEach { (form, seen) -> println("#941 observed for $form: files=${seen.files} catalog=${seen.catalog}") }

        // The verdict per form, both sides written as the file the form opens (none = memory): the classifier's
        // InProcessFile names the raw path, the driver's is the database file itself (its `.wal` never outlives close).
        val classifier =
            forms.mapValues { (_, url) ->
                when (val form = JdbcUrlForm.classify(Dialect.DUCKDB, url)) {
                    JdbcUrlForm.Form.InProcessMemory -> emptyList()
                    is JdbcUrlForm.Form.InProcessFile -> listOf(form.rawPath)
                    else -> listOf("unexpected form $form")
                }
            }
        classifier shouldBe driver.mapValues { (_, seen) -> seen.files }

        // The probe wrote only under the JUnit temporary directory, never into the test's own working directory.
        names(Path.of("").toAbsolutePath()) shouldBe workingDirBefore
    }

    @Test
    fun `a labelled memory form is memory to the driver and a file to the classifier - the stricter verdict`() {
        val forms =
            linkedMapOf(
                "named memory" to "jdbc:duckdb::memory:lbl941",
                "labelled memory" to "jdbc:duckdb:memory:lbl941",
            )
        val driver = forms.mapValues { (label, url) -> observe(label, url).files }
        driver shouldBe forms.mapValues { emptyList<String>() }
        forms.mapValues { (_, url) -> JdbcUrlForm.classify(Dialect.DUCKDB, url) } shouldBe
            forms.mapValues { (_, url) -> JdbcUrlForm.Form.InProcessFile(url.removePrefix("jdbc:duckdb:")) }
    }

    /** Opens one URL on the real driver (extension install and auto-load off) and prints its catalog; run forked. */
    object Probe {
        @JvmStatic
        fun main(args: Array<String>) {
            val properties =
                Properties().apply {
                    setProperty("autoinstall_known_extensions", "false")
                    setProperty("autoload_known_extensions", "false")
                }
            DriverManager.getConnection(args.single(), properties).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_database()").use { rows ->
                        check(rows.next()) { "current_database() returned no row" }
                        println(CATALOG_PREFIX + rows.getString(1))
                    }
                }
            }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 60L
        const val CATALOG_PREFIX = "CATALOG="
    }
}
