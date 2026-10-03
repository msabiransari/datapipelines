package co.datapipelines.parameters

import co.datapipelines.datasources.DatasourceErrorCodes
import co.datapipelines.parameters.EvaluatorFixtures.attempt
import co.datapipelines.parameters.EvaluatorFixtures.selections
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * The evaluator end to end over the REAL runtime (record §5, §6, §12's container rows): the set saved
 * through the service (the real save-time probe), released, then evaluated through [SelectorRunner] +
 * [SelectorPool] against the module's Postgres as the customer database. Prints the query count one
 * evaluate costs (record §11 — the number that decides whether an options cache is ever needed).
 */
class ParameterEvaluatorIntegrationTest {
    private lateinit var customers: CustomerRegistry
    private lateinit var harness: ParametersHarness
    private val workspace = ParametersTestDb.WORKSPACE
    private val pool = SelectorPool(4, 64)

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
        admin("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(30)%' AND pid <> pg_backend_pid()")
    }

    /** The evaluator and its runner from ONE config — the runner owns the statement-level limits (binds, timeout). */
    private fun evaluator(config: ParametersConfig = ParametersConfig()) =
        ParameterEvaluator(SelectorRunner(harness.engines, customers, config), pool, config)

    private fun template(
        name: String,
        body: String,
    ): TemplateRef = harness.template("acme/sales/$name.sql", body)

    private fun select(
        name: String,
        ref: TemplateRef,
        dependsOn: List<String> = emptyList(),
        required: Boolean = false,
        cardinality: String = "SINGLE",
    ): String =
        """
        { "name": "$name", "label": "${name.replaceFirstChar { it.uppercase() }}", "type": "STRING", "kind": "SELECT",
          "cardinality": "$cardinality", "required": $required,
          "source": { "template": { "id": "${ref.id}", "version": ${ref.version} }, "datasource": "warehouse" },
          "depends_on": [ ${dependsOn.joinToString(",") { "\"$it\"" }} ] }
        """.trimIndent()

    /** Country (constants) → state → city, SAVED through the service with the real probe, then RELEASED. */
    private fun cascade(): ParameterSetVersion {
        val states = template("states", STATES)
        val cities = template("cities", CITIES)
        val created =
            harness.create(
                harness.document(
                    ParameterSetFixtures.setJson(
                        ParameterSetFixtures.countryJson(),
                        select("state", states, listOf("country"), required = true),
                        select("city", cities, listOf("state")),
                    ),
                ),
            )
        return harness.service.release(workspace, created.record.id, created.detail.bodyHash, ParametersTestDb.AUTHOR).version
    }

    private fun EvaluateResponse.state(name: String): ParameterState = parameters.single { it.definition.name == name }.state

    @Test
    fun `the cascade end to end on a real database - first render, then a parent change walks the children - and the query count`() {
        val released = cascade()
        val leasesBefore = customers.leases.get()
        val readsBefore = customers.liveReads.get()

        val first = evaluator().evaluateBlocking(workspace, released, emptyMap(), attempt())

        first.valid shouldBe true
        first.values shouldBe mapOf("country" to "USA", "state" to "CA", "city" to "Los Angeles")
        val statements = customers.leases.get() - leasesBefore
        val reads = customers.liveReads.get() - readsBefore
        println(
            "QUERY COUNT per evaluate (country constants → state → city templates): " +
                "customer statements=$statements, datasource live reads=$reads",
        )
        withClue("one statement per template-backed parameter; the datasource read ONCE per evaluate (the resolver's memo)") {
            (statements to reads) shouldBe (2 to 1)
        }

        val walked =
            evaluator().evaluateBlocking(
                workspace,
                released,
                selections("country" to "CAN", "state" to "NJ", "city" to "Newark"),
                attempt(),
            )

        walked.values shouldBe mapOf("country" to "CAN", "state" to "ON", "city" to "Toronto")
        (walked.state("state").reset to walked.state("city").reset) shouldBe (true to true)
        walked.valid shouldBe true
    }

    @Test
    fun `a grant revoked between release and evaluate is datasource_not_found on the parameters that use it - the form still whole`() {
        val released = cascade()

        customers.revoke(CustomerDb.WAREHOUSE, workspace)
        val response = evaluator().evaluateBlocking(workspace, released, emptyMap(), attempt())

        response
            .state("state")
            .errors
            .first()
            .code shouldBe DatasourceErrorCodes.NOT_FOUND
        response
            .state("city")
            .errors
            .single()
            .code shouldBe DatasourceErrorCodes.NOT_FOUND
        response.state("country").value shouldBe "USA"
        response.valid shouldBe false
    }

    @Test
    fun `row invariants on real SQL - a duplicate value, a null value, an empty label, two defaults - each with its reason`() {
        fun reasonOf(body: String): Any? {
            val ref = template("inv_${body.hashCode().toUInt()}", body)
            val set =
                EvaluatorFixtures.version(
                    ParameterSetFixtures.countryJson(),
                    select("state", ref, listOf("country")),
                    workspace = workspace,
                )
            val error =
                evaluator()
                    .evaluateBlocking(workspace, set, emptyMap(), attempt())
                    .state("state")
                    .errors
                    .single()
            error.code shouldBe ParameterErrorCodes.EVALUATE_SELECTOR_ROWS_INVALID
            return error.details["reason"]
        }

        reasonOf("SELECT country_code AS value, name AS display_value, FALSE AS is_default FROM sel.state ORDER BY name") shouldBe
            "duplicate_value"
        reasonOf(
            "SELECT NULLIF(code, 'NY') AS value, name AS display_value, FALSE AS is_default " +
                "FROM sel.state WHERE country_code = :country ORDER BY name",
        ) shouldBe
            "null_value"
        reasonOf(
            "SELECT code AS value, CASE WHEN code = 'NJ' THEN '' ELSE name END AS display_value, FALSE AS is_default " +
                "FROM sel.state WHERE country_code = :country ORDER BY name",
        ) shouldBe "empty_label"
        reasonOf(
            "SELECT code AS value, name AS display_value, code IN ('NY', 'NJ') AS is_default " +
                "FROM sel.state WHERE country_code = :country ORDER BY name",
        ) shouldBe
            "multiple_defaults"
    }

    @Test
    fun `a MULTI parent binds into IN on the real engine - and over max-binds-per-statement it is too_many_binds on the child`() {
        val cities =
            template(
                "cities_of_states",
                "SELECT name AS value, name AS display_value, FALSE AS is_default FROM sel.city " +
                    "WHERE state_code IN (:states) ORDER BY name",
            )
        val states = List(25) { "S%02d".format(it) } + listOf("NY", "NJ")
        val set =
            EvaluatorFixtures.version(
                ParameterSetFixtures.constantsSelect("states", states, cardinality = "MULTI"),
                select("city", cities, listOf("states")),
                workspace = workspace,
            )

        evaluator().evaluateBlocking(workspace, set, selections("states" to listOf("NY", "NJ")), attempt()).state("city").options!!.map {
            it.value
        } shouldContainExactly
            listOf("Buffalo", "New York", "Newark")
        val tight = ParametersConfig(maxBindsPerStatement = 20)
        evaluator(tight).evaluateBlocking(workspace, set, selections("states" to states.take(20)), attempt()).state("city").errors shouldBe
            emptyList()
        evaluator(
            tight,
        ).evaluateBlocking(workspace, set, selections("states" to states.take(21)), attempt()).state("city").errors.single().let {
            it.code shouldBe ParameterErrorCodes.EVALUATE_TOO_MANY_BINDS
            it.details["binds"] shouldBe 21
        }
    }

    @Test
    fun `a statement still running at the deadline is abandoned - the WHOLE request times out, the server-side sleep is cancelled`() {
        val slow = template("slow_parent", "SELECT 'A' AS value, 'A' AS display_value, TRUE AS is_default FROM pg_sleep(0.6) ORDER BY 1")
        val stuck =
            template(
                "stuck_child",
                "SELECT 'B' AS value, 'B' AS display_value, TRUE AS is_default FROM pg_sleep(30) WHERE :a IS NOT NULL ORDER BY 1",
            )
        val set = EvaluatorFixtures.version(select("a", slow), select("b", stuck, listOf("a")), workspace = workspace)
        // The deadline must land INSIDE the child's statement for there to be anything to abandon: the
        // parent's 0.6 s sleep plus this test's cold start (a fresh harness per test - the first
        // connection of a new pool, the first render of two templates) has to fit before it. At 1 s the
        // budget was 0.4 s and a loaded gate (12 workers, six forks) spent it: the deadline fell between
        // the two statements, the request timed out correctly and `abandoned` was 0. Two seconds leaves
        // 1.4 s; the answer still arrives at the deadline, not after the 30 s statement.
        val twoSeconds = ParametersConfig(evaluateTimeoutSeconds = 2, selectorQueryTimeoutSeconds = 2)
        val begun = System.nanoTime()

        val refused = shouldThrow<DatapipelinesException> { evaluator(twoSeconds).evaluateBlocking(workspace, set, emptyMap(), attempt()) }

        refused.code shouldBe ParameterErrorCodes.EVALUATE_TIMEOUT
        withClue("answered at the deadline, not after the 30 s statement") {
            (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun) < ANSWERED_WITHIN_MS) shouldBe true
        }
        pool.abandoned.sum() shouldBe 1L
        waitUntil("the cancel reached the server and the 30 s sleep stopped") {
            admin(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query LIKE '%pg_sleep(30)%' AND pid <> pg_backend_pid()",
            ) ==
                0
        }
        waitUntil("the worker returned its slot") { pool.admitted() == 0 && pool.abandonedThreadsAlive() == 0 }
    }

    /** One autocommit read on its OWN connection: the first column of the first row as an Int (0 when none, or not a number). */
    private fun admin(sql: String): Int =
        DriverManager.getConnection(ParametersTestDb.jdbcUrl, ParametersTestDb.username, ParametersTestDb.password).use { c ->
            c.createStatement().use { s -> firstInt(s.executeQuery(sql)) }
        }

    private fun firstInt(rows: java.sql.ResultSet): Int = rows.use { if (it.next()) runCatching { it.getInt(1) }.getOrDefault(0) else 0 }

    private fun waitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_S)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val STATES =
            "SELECT code AS value, name AS display_value, FALSE AS is_default FROM sel.state WHERE country_code = :country ORDER BY name"
        const val CITIES =
            "SELECT name AS value, name AS display_value, FALSE AS is_default FROM sel.city WHERE state_code = :state ORDER BY name"
        const val ANSWERED_WITHIN_MS = 5_000L
        const val WAIT_S = 10L
        const val POLL_MS = 20L
    }
}
