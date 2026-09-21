package co.datapipelines.datasources

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import kotlin.io.path.absolutePathString

/**
 * The file-roots rule (#186): the escape shapes are refused on the RAW text before
 * normalization is trusted, and an under-root path — including one that passes through a
 * symlinked parent — is admitted only against a declared, existing root.
 */
class DatasourceFileRootsTest {
    private val root = Files.createTempDirectory("dp-roots")

    @Test
    fun `an empty roots list refuses every file path, with the config key named`() {
        val refusal = DatasourceFileRoots.EMPTY.refusalFor("/anywhere/at/all.db")

        refusal shouldContain DatasourceFileRootsProperties.CONFIG_KEY
    }

    @Test
    fun `a path under a declared root is admitted - the file itself need not exist yet`() {
        val roots = DatasourceFileRoots(listOf(root))

        // H2/SQLite/DuckDB create the file on first connect; the PARENT must exist.
        roots.refusalFor(root.resolve("new-db.db").absolutePathString()) shouldBe null
    }

    @Test
    fun `a path outside every root is refused`() {
        val roots = DatasourceFileRoots(listOf(root))

        roots.refusalFor("/etc/passwd.db") shouldContain "not under any declared datasource file root"
    }

    @Test
    fun `the escape shapes are refused before normalization is trusted`() {
        val roots = DatasourceFileRoots(listOf(root))
        val inside = root.absolutePathString()

        assertAll(
            { roots.refusalFor("$inside/../../etc/passwd") shouldContain "'..' segments" },
            { roots.refusalFor("~/app.db") shouldContain "'~'" },
            { roots.refusalFor("$inside/%2Fescape.db") shouldContain "URL-encoded" },
            { roots.refusalFor("$inside/%5Cescape.db") shouldContain "URL-encoded" },
            { roots.refusalFor("relative/path.db") shouldContain "must be absolute" },
            { roots.refusalFor("$inside/no-such-dir/db.sqlite") shouldContain "does not exist" },
        )
    }

    @Test
    fun `a symlinked parent resolves to its target before the root comparison`() {
        val real = Files.createTempDirectory("dp-roots-real")
        val link = root.resolve("link")
        Files.createSymbolicLink(link, real)
        val roots = DatasourceFileRoots(listOf(real))

        // The URL names the SYMLINK; the resolved path lands under the real root.
        roots.refusalFor(link.resolve("app.db").absolutePathString()) shouldBe null
        // And a root entered through a symlink compares equal to the real parent.
        DatasourceFileRoots(listOf(link)).refusalFor(real.resolve("app.db").absolutePathString()) shouldBe null
    }

    @Test
    fun `the binding half refuses a bad root at boot, naming the key`() {
        val missing = root.resolve("not-there")

        val thrown =
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                DatasourceFileRootsProperties(fileRoots = listOf(missing.absolutePathString()))
            }

        thrown.message shouldContain DatasourceFileRootsProperties.CONFIG_KEY
        // The documented default: no roots, and the class default agrees (drift-pinned).
        DatasourceFileRootsProperties().fileRoots shouldBe emptyList()
        DatasourceFileRootsProperties().toFileRoots().roots shouldBe emptyList()
        // …and a REAL directory binds (the green half of the boot refusal).
        DatasourceFileRootsProperties(fileRoots = listOf(root.absolutePathString())).toFileRoots().roots.size shouldBe 1
    }

    @Test
    fun `the validator refuses an unknown H2 form and a file outside the roots, and admits one under a root`() {
        val underRoot = root.resolve("ok.db").absolutePathString()
        val withRoots =
            DatasourceValidator(
                driverAvailable = { true },
                fileRoots = DatasourceFileRoots(listOf(root)),
            )

        withRoots.validate(Fixtures.h2(name = "h2ok", jdbcUrl = "jdbc:h2:file:$underRoot"), isCreate = true).errors shouldBe emptyList()

        val unknown =
            withRoots.validate(Fixtures.h2(name = "h2zip", jdbcUrl = "jdbc:h2:zip:~/x.zip"), isCreate = true).errors
        unknown.map { it.code } shouldBe listOf(DatasourceErrorCodes.JDBC_URL_MALFORMED)
        unknown.single().message shouldContain "zip"

        // The same under-root file is refused the moment no roots are declared (the default).
        val noRoots =
            DatasourceValidator(driverAvailable = { true })
                .validate(Fixtures.h2(name = "h2noroots", jdbcUrl = "jdbc:h2:file:$underRoot"), isCreate = true)
                .errors
        noRoots.map { it.code } shouldBe listOf(DatasourceErrorCodes.JDBC_URL_MALFORMED)
        noRoots.single().message shouldContain DatasourceFileRootsProperties.CONFIG_KEY
    }
}
