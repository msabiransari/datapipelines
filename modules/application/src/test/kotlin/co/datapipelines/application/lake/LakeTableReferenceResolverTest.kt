package co.datapipelines.application.lake

import co.datapipelines.application.lake.LakeTableReferenceResolver.Resolution
import co.datapipelines.datasources.DuckDbDefaultCatalog
import co.datapipelines.datasources.SqlTableReferenceExtractor
import co.datapipelines.datasources.WrittenTableReference
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The slice D-B resolution rules, one case per rule: what a written table position names on a LAKE connection, given
 * the datasource's registry and the engine's own facts.
 */
class LakeTableReferenceResolverTest {
    private val engine =
        SqlTableReferenceExtractor.EngineFacts(internalViews = setOf("duckdb_tables", "pragma_version"))

    private fun written(
        catalog: String?,
        schema: String?,
        table: String,
        cte: Boolean = false,
    ) = WrittenTableReference(catalog, schema, table, statementIndex = 0, timeTravel = false, cte = cte)

    private fun resolve(
        reference: WrittenTableReference,
        vararg registered: LakeTableIdentity,
        defaultCatalog: String? = "memory",
    ) = LakeTableReferenceResolver.resolve(reference, registered.toList(), engine, defaultCatalog)

    private fun resolved(
        vararg namespace: String,
        table: String,
    ) = Resolution.Resolved(LakeTableIdentity(namespace.toList(), table))

    @Test
    fun `three-part names a two-segment namespace`() {
        resolve(written("nyc", "mobility", "trips")) shouldBe resolved("nyc", "mobility", table = "trips")
    }

    @Test
    fun `three-part in the default catalog names a one-segment namespace`() {
        resolve(written("memory", "lk", "events")) shouldBe resolved("lk", table = "events")
        resolve(written("MEMORY", "lk", "events")) shouldBe resolved("lk", table = "events")
    }

    /**
     * #790, one case per URL form: the default catalog is the DATASOURCE's, derived from its URL as the engine names it
     * (`DuckDbDefaultCatalogTest` holds the derivation equal to the engine), so a three-part name in that catalog is
     * the one-segment namespace on every form.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("urlForms")
    fun `three-part in the datasource's OWN default catalog names a one-segment namespace, per URL form`(
        form: String,
        url: String,
        catalog: String,
    ) {
        val defaultCatalog = DuckDbDefaultCatalog.of(url)
        defaultCatalog shouldBe catalog
        resolve(written(catalog, "lk", "events"), defaultCatalog = defaultCatalog) shouldBe resolved("lk", table = "events")
        resolve(written(catalog.uppercase(), "lk", "events"), defaultCatalog = defaultCatalog) shouldBe resolved("lk", table = "events")
    }

    @Test
    fun `memory_s_t on a file-backed datasource names the two-segment namespace - memory is not its catalog`() {
        val defaultCatalog = DuckDbDefaultCatalog.of("jdbc:duckdb:/var/lib/lake/shop.db")
        resolve(written("memory", "lk", "events"), defaultCatalog = defaultCatalog) shouldBe resolved("memory", "lk", table = "events")
    }

    @Test
    fun `an unknown default catalog leaves a catalog-qualified name UNRESOLVED - no memory fallback`() {
        resolve(written("memory", "lk", "events"), defaultCatalog = null) shouldBe Resolution.Unresolved
        resolve(written("shop", "lk", "events"), defaultCatalog = null) shouldBe Resolution.Unresolved
    }

    @Test
    fun `an unknown default catalog changes nothing for two-part and bare names - they need no catalog`() {
        resolve(written(null, "lk", "events"), defaultCatalog = null) shouldBe resolved("lk", table = "events")
        resolve(written(null, null, "events"), LakeTableIdentity(listOf("lk"), "events"), defaultCatalog = null) shouldBe
            resolved("lk", table = "events")
        resolve(written(null, null, "events", cte = true), defaultCatalog = null) shouldBe Resolution.NotATable
    }

    @Test
    fun `two-part names a one-segment namespace`() {
        resolve(written(null, "lk", "events")) shouldBe resolved("lk", table = "events")
    }

    @Test
    fun `a bare name resolves under the ONE registered namespace, in the registry's spelling`() {
        val events = LakeTableIdentity(listOf("Lk"), "events")
        val other = LakeTableIdentity(listOf("lk"), "other")
        resolve(written(null, null, "events"), events, other) shouldBe Resolution.Resolved(LakeTableIdentity(listOf("Lk"), "events"))
    }

    @Test
    fun `a bare name of an unregistered table under the one namespace still resolves - the check then finds it absent`() {
        resolve(written(null, null, "missing"), LakeTableIdentity(listOf("lk"), "events")) shouldBe resolved("lk", table = "missing")
    }

    @Test
    fun `a bare name is unresolved under several namespaces, and under none`() {
        val a = LakeTableIdentity(listOf("a"), "t")
        val b = LakeTableIdentity(listOf("b"), "t")
        resolve(written(null, null, "t"), a, b) shouldBe Resolution.Unresolved
        resolve(written(null, null, "t")) shouldBe Resolution.Unresolved
    }

    @Test
    fun `a registered table is spelled as the registry spells it`() {
        resolve(written(null, "LK", "EVENTS"), LakeTableIdentity(listOf("lk"), "events")) shouldBe resolved("lk", table = "events")
    }

    @Test
    fun `CTE names, system catalogs and schemas and internal views are not table positions`() {
        resolve(written(null, null, "events", cte = true)) shouldBe Resolution.NotATable
        resolve(written(null, "information_schema", "tables")) shouldBe Resolution.NotATable
        resolve(written("memory", "pg_catalog", "pg_class")) shouldBe Resolution.NotATable
        resolve(written("system", "main", "anything")) shouldBe Resolution.NotATable
        resolve(written("temp", "main", "anything")) shouldBe Resolution.NotATable
        resolve(written(null, null, "duckdb_tables")) shouldBe Resolution.NotATable
        resolve(written(null, "main", "pragma_version")) shouldBe Resolution.NotATable
    }

    @Test
    fun `an internal view's name in a lake namespace is a table`() {
        resolve(written(null, "lk", "duckdb_tables")) shouldBe resolved("lk", table = "duckdb_tables")
    }

    companion object {
        @JvmStatic
        fun urlForms(): List<Arguments> =
            listOf(
                Arguments.of("anonymous", "jdbc:duckdb:", "memory"),
                Arguments.of("anonymous memory", "jdbc:duckdb::memory:", "memory"),
                Arguments.of("labelled memory", "jdbc:duckdb:memory:lbl790", "memory"),
                Arguments.of("file", "jdbc:duckdb:/var/lib/lake/shop.db", "shop"),
            )
    }
}
