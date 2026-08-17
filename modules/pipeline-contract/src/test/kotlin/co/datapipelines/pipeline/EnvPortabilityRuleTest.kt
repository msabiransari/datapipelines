package co.datapipelines.pipeline

import co.datapipelines.pipeline.EnvSpecificValueScanner.Heuristic
import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * pipeline-contract §11.4 / §12.1 — `forbidden_env_specific_value`, both the heuristics and
 * the fields they are applied to.
 *
 * The false-positive cases carry as much weight as the positives: a heuristic that rejects
 * `stg_orders` or `pg-prod` blocks a legitimate save, and §11.4 names both as things that
 * must pass.
 */
class EnvPortabilityRuleTest {
    @Test
    fun `each documented heuristic fires on its own shape`() {
        val cases =
            mapOf(
                "jdbc:postgresql://db.internal:5432/orders" to Heuristic.JDBC_URL,
                "reports.example.com" to Heuristic.HOSTNAME,
                "db-1.internal.corp:5432" to Heuristic.HOSTNAME,
                "10.0.1.15" to Heuristic.IP_ADDRESS,
                "[2001:db8::1]:5432" to Heuristic.IP_ADDRESS,
                "a1b2c3d4-1111-2222-3333-444455556666" to Heuristic.UUID,
                "user=app password=hunter2" to Heuristic.CREDENTIAL,
                "dpk_ABCDEFGHIJKL" to Heuristic.CREDENTIAL,
                "/var/lib/data" to Heuristic.ABSOLUTE_PATH,
                "C:\\data\\orders" to Heuristic.ABSOLUTE_PATH,
                "prod" to Heuristic.ENVIRONMENT_LITERAL,
                "production" to Heuristic.ENVIRONMENT_LITERAL,
                "staging" to Heuristic.ENVIRONMENT_LITERAL,
                "dev" to Heuristic.ENVIRONMENT_LITERAL,
            )

        cases.forEach { (value, expected) -> EnvSpecificValueScanner.detect(value) shouldBe expected }
    }

    @Test
    fun `the values §11-4 names as legitimate are not flagged`() {
        // "These heuristics are deliberately conservative — stg_orders containing 'stg' is fine;
        // a source of pg-prod is fine."
        listOf(
            "stg_orders",
            "int_revenue",
            "monthly_revenue_cache",
            "pg-prod",
            "mysql-prod",
            "pg-warehouse",
            "tempdb",
            "fetch_orders",
            "",
        ).forEach { EnvSpecificValueScanner.detect(it).shouldBeNull() }
    }

    @Test
    fun `an unregistered source that looks environment-specific is flagged`() {
        val pipeline =
            Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "jdbc:postgresql://db.internal:5432/orders")))

        val failure = validate(pipeline).withCode(Validation.FORBIDDEN_ENV_SPECIFIC_VALUE).single()

        failure.details["heuristic"] shouldBe Heuristic.JDBC_URL.name
        failure.path shouldBe "nodes[0].source"
    }

    @Test
    fun `a source the registry resolves is exempt, even when it looks like a hostname`() {
        // §11.4's closing rule: "the check applies to values that are not references into the
        // datasource registry". An operator who names a datasource `reports.example.com` has
        // made a naming choice, not embedded a connection.
        val registry = StubDatasources(mapOf("reports.example.com" to Dialect.POSTGRES))
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "reports.example.com")))

        validate(pipeline, registry).codes shouldNotContain Validation.FORBIDDEN_ENV_SPECIFIC_VALUE
    }

    @Test
    fun `an unregistered write-back datasource is scanned as well`() {
        val pipeline =
            Fixtures.pipeline(
                nodes = listOf(Fixtures.node(output = NodeOutput.Datasource("10.0.1.15", "cache", WriteMode.APPEND))),
            )

        validate(pipeline).withCode(Validation.FORBIDDEN_ENV_SPECIFIC_VALUE).single().path shouldBe
            "nodes[0].output.datasource"
    }

    @Test
    fun `settings keys and string values are scanned`() {
        val config = Fixtures.json("""{"prod": "jdbc:h2:mem:x"}""").properties().associate { it.key to it.value }
        val pipeline =
            Fixtures.pipeline(settings = PipelineSettings(TempdbSettings(StagingEngine.H2, config)))

        val heuristics =
            validate(pipeline).withCode(Validation.FORBIDDEN_ENV_SPECIFIC_VALUE).map { it.details["heuristic"] }

        heuristics shouldContain Heuristic.ENVIRONMENT_LITERAL.name
        heuristics shouldContain Heuristic.JDBC_URL.name
    }

    @Test
    fun `a value with thousands of dot-separated segments does not crash the scanner`() {
        // §12.2 crash-safety. java.util.regex recurses once per iteration of a `(...)*` group,
        // so `a.a.a.…` × 5000 drove the old unbounded HOSTNAME pattern into a StackOverflowError
        // — a save-time denial of service from a ~10 KB payload, and an Error escaping past
        // every handler. This is NOT a catastrophic-backtracking case: the input matches
        // linearly, it is the recursion depth that kills it.
        val segments = List(5000) { "a" }.joinToString(".")

        // Survived, and passed through un-flagged per the >512-char rule.
        EnvSpecificValueScanner.detect(segments).shouldBeNull()

        // …and through the rule, on the field that actually carries such a value.
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = segments)))
        validate(pipeline).codes shouldNotContain Validation.FORBIDDEN_ENV_SPECIFIC_VALUE
    }

    @Test
    fun `values longer than the scan bound are passed through un-flagged`() {
        // §12.2: "a scanned value longer than 512 chars cannot be a hostname/URL/UUID/credential
        // and is passed through un-flagged". The bound is checked before any regex — it is the
        // only check that is O(1) in the attacker's input.
        val justUnder = "jdbc:" + "x".repeat(EnvSpecificValueScanner.MAX_SCANNED_LENGTH - 5)
        val justOver = justUnder + "x"

        justUnder.length shouldBe EnvSpecificValueScanner.MAX_SCANNED_LENGTH
        EnvSpecificValueScanner.detect(justUnder) shouldBe Heuristic.JDBC_URL
        EnvSpecificValueScanner.detect(justOver).shouldBeNull()
    }

    @Test
    fun `a hostname within the label bound is still detected`() {
        // The repetition cap must not silently disable the heuristic for real hostnames.
        EnvSpecificValueScanner.detect("a.b.c.d.e.f.example.com") shouldBe Heuristic.HOSTNAME
    }

    @Test
    fun `the most specific heuristic is the one reported`() {
        // A connection string trips JDBC_URL, HOSTNAME and CREDENTIAL; naming it a hostname
        // would send the author looking in the wrong place.
        EnvSpecificValueScanner.detect("jdbc:postgresql://db.internal:5432/o?password=x") shouldBe Heuristic.JDBC_URL
    }

    @Test
    fun `a reflected value in the failure details is truncated (CF-2)`() {
        val pipeline = Fixtures.pipeline(nodes = listOf(Fixtures.node(source = "jdbc:" + "x".repeat(500))))

        val reflected =
            validate(pipeline).withCode(Validation.FORBIDDEN_ENV_SPECIFIC_VALUE).single().details["value"] as String

        reflected.length shouldBe MAX_REFLECTED_VALUE_LENGTH + 1
    }

    private fun validate(
        pipeline: Pipeline,
        datasources: DatasourceRegistry = StubDatasources(),
    ) = PipelineValidator(datasources, StubTemplates(), PipelineResolver { _, _ -> null }, 5).validate(pipeline)
}
