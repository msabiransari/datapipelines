package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import kotlin.io.path.absolutePathString

/**
 * The #186 classification — every form the registration gate and the file-roots rule read,
 * pinned per dialect. The gate's whole job depends on these: a `mem:` misread as a file (or a
 * file misread as a server) is the containment gone, so the mapping is asserted exhaustively
 * rather than sampled.
 */
class JdbcUrlFormTest {
    @Test
    fun `H2 - mem is in-process memory, file and bare paths are in-process files, tcp and ssl are servers`() {
        assertAll(
            { JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:mem:test") shouldBe JdbcUrlForm.Form.InProcessMemory },
            { JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:mem:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            // Driver properties never take part in the classification.
            { JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1") shouldBe JdbcUrlForm.Form.InProcessMemory },
            {
                JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:file:/data/app") shouldBe
                    JdbcUrlForm.Form.InProcessFile("/data/app")
            },
            // A bare path is H2's file form without the prefix.
            {
                JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:/data/app") shouldBe
                    JdbcUrlForm.Form.InProcessFile("/data/app")
            },
            {
                JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:./relative/app") shouldBe
                    JdbcUrlForm.Form.InProcessFile("./relative/app")
            },
            {
                JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:~/app") shouldBe
                    JdbcUrlForm.Form.InProcessFile("~/app")
            },
            { JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:tcp://db.internal:9092/app") shouldBe JdbcUrlForm.Form.Server },
            { JdbcUrlForm.classify(Dialect.H2, "jdbc:h2:ssl://db.internal/app") shouldBe JdbcUrlForm.Form.Server },
        )
    }

    @Test
    fun `H2 - the file-system pseudo-protocols are unknown, and unknown is refused-shaped`() {
        // The H2 2.3.232 "Database URL Overview" family — none of them may be waved through by
        // a rule that only knows mem/file/tcp/ssl. Fail-closed is the point: a form nobody
        // classified is not one the gate can reason about.
        listOf("zip", "split", "nio", "nioMapped", "nioMemFS", "nioMemLZF", "memFS", "memLZF", "async").forEach { prefix ->
            JdbcUrlForm
                .classify(Dialect.H2, "jdbc:h2:$prefix:~/x")
                .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
        }
    }

    @Test
    fun `H2 - the prefixes are case-sensitive exactly as the driver reads them - mixed case is unknown`() {
        // ConnectionInfo.parseName() (h2-2.3.232 ll. 197-219) matches the four prefixes
        // case-SENSITIVELY and treats anything else as a persistent file named literally —
        // `jdbc:h2:TCP://h/x` opens ./TCP:/h/x.mv.db (pinned against the real jar, 186b). A
        // lowercasing classifier would read that file shape as a network client and skip the
        // gate, the roots check AND the de-privileged pool; mixed case is therefore Unknown.
        assertAll(
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:TCP://h/x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:Tcp://h/x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:SSL://h/x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:MEM:x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:Mem:x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            {
                JdbcUrlForm
                    .classify(Dialect.H2, "jdbc:h2:FILE:/p/x")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
        )
    }

    @Test
    fun `DuckDB - memory is in-process memory, a file is an in-process file, MotherDuck is unknown`() {
        assertAll(
            { JdbcUrlForm.classify(Dialect.DUCKDB, "jdbc:duckdb::memory:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            // The driver itself matches :memory: case-INSENSITIVELY (probed against
            // duckdb_jdbc 1.5.5.1: :MEMORY: opened no file) — the classifier follows it.
            { JdbcUrlForm.classify(Dialect.DUCKDB, "jdbc:duckdb::MEMORY:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            { JdbcUrlForm.classify(Dialect.DUCKDB, "jdbc:duckdb:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            {
                JdbcUrlForm.classify(Dialect.DUCKDB, "jdbc:duckdb:/srv/sample/us_trade.duckdb") shouldBe
                    JdbcUrlForm.Form.InProcessFile("/srv/sample/us_trade.duckdb")
            },
            {
                JdbcUrlForm
                    .classify(Dialect.DUCKDB, "jdbc:duckdb:md:mydb")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
            // md: reaches the network on connect — refused in both cases, fail-closed.
            {
                JdbcUrlForm
                    .classify(Dialect.DUCKDB, "jdbc:duckdb:MD:mydb")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
        )
    }

    @Test
    fun `SQLite - memory is in-process memory, file and file-URI are in-process files, resource is unknown`() {
        assertAll(
            { JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite::memory:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            {
                JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite:/srv/sample/fx_rates.db") shouldBe
                    JdbcUrlForm.Form.InProcessFile("/srv/sample/fx_rates.db")
            },
            // The xerial `file:` URI form is a file.
            {
                JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite:file:/data/app.db") shouldBe
                    JdbcUrlForm.Form.InProcessFile("/data/app.db")
            },
            { JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite:file::memory:") shouldBe JdbcUrlForm.Form.InProcessMemory },
            {
                JdbcUrlForm
                    .classify(Dialect.SQLITE, "jdbc:sqlite::resource:seed.db")
                    .shouldBeInstanceOf<JdbcUrlForm.Form.Unknown>()
            },
        )
    }

    @Test
    fun `SQLite - the driver is case-sensitive - mixed case names a literal file, roots-vetted as such`() {
        // Probed against xerial sqlite-jdbc 3.49.1.0 (186b): `jdbc:sqlite::MEMORY:` creates a
        // literal file named `:MEMORY:`, `jdbc:sqlite:FILE:x` one named `FILE:x`. The driver
        // never strips a mixed-case prefix, so the classifier must not either — the raw path
        // the roots check vets IS the whole sub-name.
        assertAll(
            {
                JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite::MEMORY:") shouldBe
                    JdbcUrlForm.Form.InProcessFile(":MEMORY:")
            },
            {
                JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite:FILE:/p/x.db") shouldBe
                    JdbcUrlForm.Form.InProcessFile("FILE:/p/x.db")
            },
            {
                JdbcUrlForm.classify(Dialect.SQLITE, "jdbc:sqlite:file::MEMORY:") shouldBe
                    JdbcUrlForm.Form.InProcessFile(":MEMORY:")
            },
        )
    }

    @Test
    fun `the server dialects and LAKE never classify as in-process`() {
        assertAll(
            { JdbcUrlForm.classify(Dialect.POSTGRES, "jdbc:postgresql://db:5432/app") shouldBe JdbcUrlForm.Form.Server },
            { JdbcUrlForm.classify(Dialect.ORACLE, "jdbc:oracle:thin:@//db:1521/svc") shouldBe JdbcUrlForm.Form.Server },
            { JdbcUrlForm.classify(Dialect.MSSQL, "jdbc:sqlserver://db:1433;databaseName=app") shouldBe JdbcUrlForm.Form.Server },
            { JdbcUrlForm.classify(Dialect.MYSQL, "jdbc:mysql://db:3306/app") shouldBe JdbcUrlForm.Form.Server },
            // LAKE's engine is DuckDB, but its registration posture is the dp-lake one (§4.1) —
            // not this gate's.
            { JdbcUrlForm.classify(Dialect.LAKE, "jdbc:duckdb::memory:") shouldBe JdbcUrlForm.Form.Server },
        )
    }

    @Test
    fun `isInProcess is exactly the memory and file forms`() {
        JdbcUrlForm.Form.InProcessMemory.isInProcess shouldBe true
        JdbcUrlForm.Form.InProcessFile("/x").isInProcess shouldBe true
        JdbcUrlForm.Form.Server.isInProcess shouldBe false
        JdbcUrlForm.Form.Unknown("zip").isInProcess shouldBe false
    }
}
