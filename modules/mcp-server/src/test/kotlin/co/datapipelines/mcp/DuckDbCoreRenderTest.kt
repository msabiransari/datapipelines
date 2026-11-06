package co.datapipelines.mcp

import co.datapipelines.application.datasources.LakeTableRegistryService
import co.datapipelines.datasources.DuckDbVersion
import co.datapipelines.mcp.docs.DocContext
import co.datapipelines.mcp.docs.DocRenderer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The two shipped statements of DuckDB's iceberg limitation are RENDERED from the pin, never typed (#829).
 *
 * A golden comparison at today's version cannot tell the token from the same version typed by hand: both render
 * identical text. A sentinel core the tree never contains can: rendered with it, the `lake_tables_register`
 * description and the skill's `lake` guide must carry the sentinel — a typed literal cannot. The default context
 * must then render the catalog's own three-part core, so the token is also the RIGHT token. The dated measurement
 * note in the same guide is history and must NOT follow the sentinel.
 *
 * Expected text is assembled from [SENTINEL] and the catalog, never written as a version literal, so the driver
 * drift guard (`DriverVersionSpecDriftTest`) has nothing in this file to own.
 */
class DuckDbCoreRenderTest {
    private fun registerTool(duckdbCore: String? = null): LakeTablesRegisterTool {
        val registry = FakeDatasourceRegistry(emptyList())
        val service = mockk<LakeTableRegistryService>()
        return if (duckdbCore == null) {
            LakeTablesRegisterTool(registry, service)
        } else {
            LakeTablesRegisterTool(registry, service, duckdbCore = duckdbCore)
        }
    }

    private fun lakeGuide(context: DocContext): String =
        DocRenderer(context, DocSetTestSupport.FixtureDocErrorCatalog(), realShippedTools()).render().get(LAKE_GUIDE).markdown

    @Test
    fun `the register tool's description states the core it is rendered with`() {
        withClue("a sentinel core must reach the description; a typed version cannot carry it") {
            registerTool(SENTINEL).definition.description() shouldContain "DuckDB $SENTINEL cannot scan a"
        }
        withClue("the shipped default renders the catalog's three-part core") {
            registerTool().definition.description() shouldContain "DuckDB ${catalogCore()} cannot scan a"
        }
    }

    @Test
    fun `the lake guide states the core its context renders, and its dated measurement note stays history`() {
        val rendered = lakeGuide(DocContext(duckdbCore = SENTINEL))
        withClue("a sentinel core must reach the guide's iceberg sentence; a typed version cannot carry it") {
            rendered shouldContain "DuckDB $SENTINEL cannot"
        }
        withClue("the dated measurement note is a literal of its own day, never substituted") {
            rendered.lines().single { MEASUREMENT_NOTE in it } shouldNotContain SENTINEL
        }
        withClue("the shipped default context renders the catalog's three-part core") {
            DocContext.DEFAULTS.duckdbCore shouldBe catalogCore()
            DuckDbVersion.CURRENT.core shouldBe catalogCore()
            lakeGuide(DocContext.DEFAULTS) shouldContain "DuckDB ${catalogCore()} cannot"
        }
    }

    /** The catalog's `duckdb-jdbc`, cut to the core as `DuckDbVersionTest` reads it. */
    private fun catalogCore(): String {
        val toml = SpecFiles.read("gradle/libs.versions.toml")
        val declared = requireNotNull(Regex("""(?m)^duckdb-jdbc = "([^"]+)"""").find(toml)) { "no duckdb-jdbc in the catalog" }
        return DuckDbVersion(declared.groupValues[1]).core
    }

    private companion object {
        /** A core no file in the tree contains: only a rendered statement can show it. */
        const val SENTINEL = "9.9.9"
        const val LAKE_GUIDE = "lake"
        const val MEASUREMENT_NOTE = "measured first-hand on DuckDB"
    }
}
