package co.datapipelines.parameters

import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.datasources.DatasourceProperties
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/**
 * The REAL selector runtime against the module's Postgres container (record §4 steps 5–6, §6.2,
 * §6.3): a real `TemplateEngine` over real template rows, the customer registry's real HikariCP
 * pools, the §7D gate, and rows read through `ResultRowReader`. The recording probe stays for the
 * unit suite; here nothing between the validator and the database is a double.
 */
class SelectorRunnerIntegrationTest {
    private lateinit var customers: CustomerRegistry
    private lateinit var harness: ParametersHarness
    private val workspace = ParametersTestDb.WORKSPACE

    private val runner: SelectorRunner get() = checkNotNull(harness.runner)

    @BeforeEach
    fun seed() {
        ParametersTestDb.reset()
        CustomerDb.seed()
        customers = CustomerRegistry(CustomerDb.datasource())
        harness = ParametersHarness(customers = customers)
    }

    @AfterEach
    fun close() {
        customers.close()
        harness.engines.close()
    }

    private fun cascade(
        states: TemplateRef,
        cities: TemplateRef,
    ) = harness.document(
        ParameterSetFixtures.setJson(
            ParameterSetFixtures.countryJson(),
            ParameterSetFixtures
                .stateJson()
                .replace(
                    "acme/sales/states_of_country.sql",
                    states.id,
                ).replace("\"version\": 3", "\"version\": ${states.version}"),
            """
            { "name": "city", "label": "City", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE", "required": false,
              "source": { "template": { "id": "${cities.id}", "version": ${cities.version} }, "datasource": "warehouse" },
              "depends_on": ["state"] }
            """.trimIndent(),
        ),
    )

    private var templates = 0

    /** A fresh template per call — names are unique per workspace forever. */
    private fun statesTemplate(body: String = STATES) = harness.template("acme/sales/states_${++templates}.sql", body)

    private fun citiesTemplate(body: String = CITIES) = harness.template("acme/sales/cities_${++templates}.sql", body)

    /** Country (constants) → state (template) alone — the test's one template-backed source. */
    private fun countryAndState(states: TemplateRef) =
        harness.document(
            ParameterSetFixtures.setJson(
                ParameterSetFixtures.countryJson(),
                ParameterSetFixtures
                    .stateJson()
                    .replace(
                        "acme/sales/states_of_country.sql",
                        states.id,
                    ).replace("\"version\": 3", "\"version\": ${states.version}"),
            ),
        )

    private fun failuresOf(document: ParameterSetDocument) =
        harness.validator
            .validate(workspace, document)
            .shouldBeInstanceOf<ParameterSetValidation.Invalid>()
            .result.failures

    @Test
    fun `save-time steps 5 and 6 pass end to end for the country - state - city cascade through the REAL probe`() {
        val created = harness.create(cascade(statesTemplate(), citiesTemplate()))

        created.body.parameters.map { it.name } shouldContainExactly listOf("country", "state", "city")
        withClue("the dry run reached the database: one lease per template-backed source") { customers.leases.get() shouldBe 2 }
    }

    @Test
    fun `a selector without ORDER BY, with an extra column, or of the wrong value type is refused at save`() {
        failuresOf(countryAndState(statesTemplate(STATES.replace(" ORDER BY name", "")))).map { it.code } shouldContainExactly
            listOf(ParameterErrorCodes.SELECTOR_ORDER_BY_MISSING)
        failuresOf(countryAndState(statesTemplate(STATES.replace("FALSE AS is_default", "FALSE AS is_default, code AS extra"))))
            .single()
            .code shouldBe ParameterErrorCodes.SELECTOR_COLUMNS_INVALID
        failuresOf(countryAndState(statesTemplate(STATES.replace("code AS value", "length(code) AS value"))))
            .single()
            .code shouldBe ParameterErrorCodes.SELECTOR_VALUE_TYPE_MISMATCH
    }

    @Test
    fun `the read-only gate runs on the rendered text before any lease - a write is refused and nothing ran`() {
        val writes = statesTemplate("DELETE FROM sel.state WHERE country_code = :country")

        val failure = failuresOf(countryAndState(writes)).single()

        failure.code shouldBe ParameterErrorCodes.SELECTOR_QUERY_FAILED
        failure.details["datasource_code"] shouldBe PipelineErrorCodes.Node.QUERY_EXECUTION_FAILED
        customers.leases.get() shouldBe 0
        ParametersTestDb.jdbc.jdbcTemplate.queryForObject("SELECT count(*) FROM sel.state", Int::class.java) shouldBe 5
    }

    @Test
    fun `a render failure is template_render_failed with the engine's own detail`() {
        val broken =
            statesTemplate(
                "SELECT code AS value, name AS display_value, FALSE AS is_default FROM sel.state WHERE x = \${nope} ORDER BY name",
            )

        failuresOf(countryAndState(broken)).single().code shouldBe ParameterErrorCodes.TEMPLATE_RENDER_FAILED
    }

    @Test
    fun `an unreachable datasource refuses the save - datasource_unreachable, not a statement failure`() {
        customers.register(
            CustomerDb.datasource(jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nothing").copy(
                properties =
                    DatasourceProperties(
                        hikari =
                            mapOf("connectionTimeout" to 250),
                    ),
            ),
        )

        failuresOf(cascade(statesTemplate(), citiesTemplate())).map { it.code }.distinct() shouldContainExactly
            listOf(ParameterErrorCodes.DATASOURCE_UNREACHABLE)
    }

    @Test
    fun `a MULTI list binds one placeholder per member on the real engine, and an empty one matches nothing`() {
        val sql = "SELECT name AS value FROM sel.city WHERE state_code IN (:states) ORDER BY name"

        rows(
            runner.probe(workspace, CustomerDb.WAREHOUSE, sql, mapOf("states" to listOf("NY", "NJ")), 10),
        ).map { it.single() } shouldContainExactly
            listOf("Buffalo", "New York", "Newark")
        rows(runner.probe(workspace, CustomerDb.WAREHOUSE, sql, mapOf("states" to emptyList<String>()), 10)) shouldBe emptyList()
    }

    @Test
    fun `the in_list slices execute - the list dealt across the slices the rendered SQL names`() {
        val sql = "SELECT code AS value FROM sel.state WHERE (code IN (:codes__1) OR code IN (:codes__2)) ORDER BY code"

        rows(runner.probe(workspace, CustomerDb.WAREHOUSE, sql, mapOf("codes" to listOf("NY", "NJ", "CA", "ON", "XX")), 10)).map {
            it.single()
        } shouldContainExactly
            listOf("CA", "NJ", "NY", "ON")
    }

    @Test
    fun `rows arrive canonical and the schema from metadata - BIGINT as BigInteger, NUMERIC(6,2) exact, TIMESTAMPTZ as its Instant`() {
        val probed =
            runner
                .probe(
                    workspace,
                    CustomerDb.WAREHOUSE,
                    "SELECT population AS value FROM sel.city WHERE name = :n",
                    mapOf("n" to "Toronto"),
                    2,
                ).shouldBeInstanceOf<SelectorProbeOutcome.Probed>()
        probed.columns.single().type shouldBe LogicalType.BIGINTEGER
        probed.rows.single().single() shouldBe BigInteger.valueOf(2_800_000)

        val orders =
            runner
                .probe(workspace, CustomerDb.WAREHOUSE, "SELECT amount, placed_at FROM sel.orders WHERE region = :r", mapOf("r" to "NY"), 2)
                .shouldBeInstanceOf<SelectorProbeOutcome.Probed>()
        orders.columns.map { Triple(it.type, it.precision, it.scale) } shouldContainExactly
            listOf(Triple(LogicalType.DECIMAL, 6, 2), Triple(LogicalType.TIMESTAMP, orders.columns[1].precision, orders.columns[1].scale))
        orders.rows.single() shouldBe listOf(BigDecimal("12.50"), Instant.parse("2026-09-01T10:00:00Z"))
    }

    @Test
    fun `maxRows bounds what is read`() {
        rows(runner.probe(workspace, CustomerDb.WAREHOUSE, "SELECT name AS value FROM sel.city ORDER BY name", emptyMap(), 2)).size shouldBe
            2
    }

    @Test
    fun `a bind the context does not hold is sql_parameter_missing, never a null bind`() {
        runner.probe(workspace, CustomerDb.WAREHOUSE, "SELECT 1 AS value WHERE :ghost IS NULL", emptyMap(), 2) shouldBe
            SelectorProbeOutcome.StatementFailed(
                PipelineErrorCodes.Node.SQL_PARAMETER_MISSING,
                "The rendered selector binds :ghost, which is neither a parent in depends_on nor an org or platform key.",
            )
    }

    @Test
    fun `a datasource not visible from the workspace is datasource_not_found, read live on every call (P31)`() {
        runner.probe(workspace, CustomerDb.WAREHOUSE, "SELECT 1 AS value", emptyMap(), 2).shouldBeInstanceOf<SelectorProbeOutcome.Probed>()

        customers.revoke(CustomerDb.WAREHOUSE, workspace)

        runner
            .probe(workspace, CustomerDb.WAREHOUSE, "SELECT 1 AS value", emptyMap(), 2)
            .shouldBeInstanceOf<SelectorProbeOutcome.StatementFailed>()
            .datasourceCode shouldBe DatasourceErrorCodes.NOT_FOUND
        runner
            .probe(ParametersTestDb.OTHER_WORKSPACE, CustomerDb.WAREHOUSE, "SELECT 1 AS value", emptyMap(), 2)
            .shouldBeInstanceOf<SelectorProbeOutcome.StatementFailed>()
            .datasourceCode shouldBe DatasourceErrorCodes.NOT_FOUND
    }

    @Test
    fun `the statement timeout is clamped to selector-query-timeout-seconds - query_timeout, not unreachable`() {
        customers.register(CustomerDb.datasource(queryTimeoutSeconds = 60))
        val fast = ParametersHarness(customers = customers, config = ParametersConfig(selectorQueryTimeoutSeconds = 1))

        val outcome = checkNotNull(fast.runner).probe(workspace, CustomerDb.WAREHOUSE, "SELECT pg_sleep(5) AS value", emptyMap(), 2)

        outcome.shouldBeInstanceOf<SelectorProbeOutcome.StatementFailed>().datasourceCode shouldBe PipelineErrorCodes.Node.QUERY_TIMEOUT
        fast.engines.close()
    }

    private fun rows(outcome: SelectorProbeOutcome): List<List<Any?>> = outcome.shouldBeInstanceOf<SelectorProbeOutcome.Probed>().rows

    private companion object {
        const val STATES =
            "SELECT code AS value, name AS display_value, FALSE AS is_default FROM sel.state WHERE country_code = :country ORDER BY name"
        const val CITIES =
            "SELECT name AS value, name AS display_value, FALSE AS is_default FROM sel.city WHERE state_code = :state ORDER BY name"
    }
}
