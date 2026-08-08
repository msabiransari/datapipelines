package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The spec's own worked examples, as golden fixtures: deserialize → validate → assert.
 *
 * These are the acceptance criteria the document itself publishes (§3.1, §9.4, §16.1–16.5),
 * and every one of them is annotated in the spec with a ✓. A rule that rejects one of them
 * is wrong no matter how defensible it looks in isolation — which is exactly the trap D1 was
 * ratified to escape: §12.3's old `dql_sink_missing_caller_target` contradicted §9.3 and
 * §16.3, the doc's own examples (SPEC-REVIEW 2.1.2).
 */
class WorkedExamplesTest {
    private val deserializer = PipelineDeserializer()
    private val validator = Fixtures.validator(templates = StubTemplates(lookups = TEMPLATE_DIALECTS))

    @Test
    fun `§16-1 minimal pipeline - a DQL node with no output block IS the caller node`() {
        // The single most important deserialization behaviour in the module (D1, §4.7).
        val pipeline = parse("spec-16.1-minimal.json")

        pipeline.nodes.single().output shouldBe NodeOutput.Caller
        CallerNodeResolver.resolve(pipeline)?.id shouldBe "active_users"
        validator.validate(pipeline).failures shouldContainExactly emptyList()
    }

    @Test
    fun `§3-1 monthly revenue report validates, with one caller node among four other sinks`() {
        val pipeline = parse("spec-3.1-monthly-revenue-report.json")

        withClue(validator.validate(pipeline).failures.toString()) {
            validator.validate(pipeline).isValid shouldBe true
        }
        CallerNodeResolver.resolve(pipeline)?.id shouldBe "final_report"
        pipeline.node("cache_to_warehouse")?.output shouldBe
            NodeOutput.Datasource("pg-warehouse", "monthly_revenue_cache", WriteMode.REPLACE)
        pipeline.node("fetch_orders")?.output shouldBe NodeOutput.Tempdb("stg_orders")
    }

    @Test
    fun `§3-1 server-assigned fields in the document are not bound onto the body`() {
        // §3.1's example carries id / version / owner / created_at / updated_at. metadata-db
        // §4.5 defines body_json without them and §14 defines the create payload without them,
        // so they are ignored on read and cannot be over-posted (see Pipeline's KDoc).
        val serialized = PipelineSerializer().write(parse("spec-3.1-monthly-revenue-report.json"))
        val topLevelKeys = Fixtures.json(serialized).properties().map { it.key }

        // Exactly metadata-db §4.5's body_json field list — asserted as a whole set, so a
        // future field added to Pipeline shows up here rather than leaking silently.
        topLevelKeys shouldContainExactlyInAnyOrder
            listOf("schema_version", "name", "display_name", "description", "settings", "parameters", "nodes")
    }

    @Test
    fun `§3-1 parameter descriptors bind with their wire-encoded defaults intact`() {
        val parameters = parse("spec-3.1-monthly-revenue-report.json").parameters

        parameters.getValue("start_date").type shouldBe LogicalType.DATE
        parameters.getValue("start_date").required shouldBe true
        // BIGDECIMAL is string-on-wire, so its default is the STRING "0.00", not a number.
        parameters.getValue("min_total").default?.isTextual shouldBe true
        parameters.getValue("min_total").default?.asText() shouldBe "0.00"
        parameters.getValue("include_cancelled").default?.isBoolean shouldBe true
    }

    @Test
    fun `§16-3 write-back plus caller return is legal - two sinks, one caller`() {
        val pipeline = parse("spec-16.3-writeback.json")

        withClue(validator.validate(pipeline).failures.toString()) {
            validator.validate(pipeline).isValid shouldBe true
        }
        CallerNodeResolver.resolve(pipeline)?.id shouldBe "return_report"
    }

    @Test
    fun `§16-4 DDL and §16-5 DML nodes carry no output block and still validate`() {
        val pipeline = parse("spec-16.4-ddl-and-16.5-dml.json")

        pipeline.node("create_idx")?.output.shouldBeNull()
        pipeline.node("record_execution")?.output.shouldBeNull()
        withClue(validator.validate(pipeline).failures.toString()) {
            validator.validate(pipeline).isValid shouldBe true
        }
    }

    @Test
    fun `§9-4 zero caller nodes is legal`() {
        val pipeline = parse("spec-9.4-zero-caller.json")

        withClue(validator.validate(pipeline).failures.toString()) {
            validator.validate(pipeline).isValid shouldBe true
        }
        CallerNodeResolver.resolve(pipeline).shouldBeNull()
        CallerNodeResolver.hasCallerNode(pipeline) shouldBe false
    }

    @Test
    fun `every example survives a serialize-deserialize round trip`() {
        // Semantic, not byte-for-byte: §16.1's omitted output block comes back explicit,
        // because D1 resolves the default at deserialization and never re-derives it.
        EXAMPLES.forEach { file ->
            val original = parse(file)
            val roundTripped = deserializer.readOrThrow(PipelineSerializer().write(original))
            withClue(file) { roundTripped shouldBe original }
        }
    }

    private fun parse(file: String): Pipeline = deserializer.readOrThrow(Fixtures.example(file))

    private companion object {
        val EXAMPLES =
            listOf(
                "spec-3.1-monthly-revenue-report.json",
                "spec-16.1-minimal.json",
                "spec-16.3-writeback.json",
                "spec-16.4-ddl-and-16.5-dml.json",
                "spec-9.4-zero-caller.json",
            )

        /**
         * Each example's templates, at the dialect its node's source speaks — §12.6's
         * `template_dialect_mismatch` is a real check and the fixtures have to satisfy it.
         * `tempdb` sources take H2, the dialect of the declared staging engine (§5.1).
         */
        val TEMPLATE_DIALECTS: Map<String, TemplateLookup> =
            mapOf(
                "fetch_orders.sql" to TemplateLookup.Found(Dialect.POSTGRES),
                "active_users.sql" to TemplateLookup.Found(Dialect.POSTGRES),
                "record_execution.sql" to TemplateLookup.Found(Dialect.POSTGRES),
                "fetch_customers.sql" to TemplateLookup.Found(Dialect.MYSQL),
                "join_revenue.sql" to TemplateLookup.Found(Dialect.H2),
                "select_revenue.sql" to TemplateLookup.Found(Dialect.H2),
                "final_report.sql" to TemplateLookup.Found(Dialect.H2),
                "report.sql" to TemplateLookup.Found(Dialect.H2),
                "create_idx_revenue.sql" to TemplateLookup.Found(Dialect.H2),
                "transform.sql" to TemplateLookup.Found(Dialect.H2),
                "select_orders.sql" to TemplateLookup.Found(Dialect.H2),
            )
    }
}
