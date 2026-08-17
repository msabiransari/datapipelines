package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * [PipelineDeserializer] — §17.2 step 1, and the wire-value pre-scan that carries five §12
 * codes no typed model could report.
 */
class PipelineDeserializerTest {
    private val deserializer = PipelineDeserializer()

    @Test
    fun `an omitted output block on a DQL node deserializes to Caller (D1)`() {
        val pipeline = parse(pipelineJson(NODE_NO_OUTPUT))

        pipeline.nodes.single().output shouldBe NodeOutput.Caller
        pipeline.nodes.single().isCallerNode shouldBe true
    }

    @Test
    fun `an omitted output block on a DML or DDL node stays null`() {
        val pipeline =
            parse(
                pipelineJson(
                    """{"id":"a","description":"d","type":"DML","source":"pg-prod",
                       "template":{"id":"t.sql","version":1},"depends_on":[]}""",
                    """{"id":"b","description":"d","type":"DDL","source":"pg-prod",
                       "template":{"id":"t.sql","version":1},"depends_on":[]}""",
                ),
            )

        pipeline.nodes.map { it.output }.shouldContainExactly(listOf(null, null))
    }

    @Test
    fun `each output target binds to its own NodeOutput variant`() {
        val pipeline =
            parse(
                pipelineJson(
                    node("""{"target":"tempdb","table":"stg_orders"}"""),
                    node("""{"target":"caller"}""", id = "b"),
                    node(
                        """{"target":"datasource","datasource":"pg-warehouse","table":"cache","mode":"append"}""",
                        id = "c",
                    ),
                ),
            )

        pipeline.nodes.map { it.output } shouldContainExactly
            listOf(
                NodeOutput.Tempdb("stg_orders"),
                NodeOutput.Caller,
                NodeOutput.Datasource("pg-warehouse", "cache", WriteMode.APPEND),
            )
    }

    @Test
    fun `an out-of-catalog node type is rejected with type_invalid, not a Jackson exception`() {
        val outcome = deserializer.read(pipelineJson(node(null, type = "SELECT")))

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.TYPE_INVALID)
    }

    @Test
    fun `the type_invalid message names every catalogued wire value`() {
        // §12.4's allowed-set, restated in the failure itself: the enum gaining `PIPELINE`
        // (four values now) must show up in what the author is told.
        val outcome = deserializer.read(pipelineJson(node(null, type = "SELECT")))

        val failure = (outcome as DeserializationOutcome.Rejected).result.failures.single()
        failure.message shouldContain "[DQL, DML, DDL, PIPELINE]"
        failure.details["allowed"] shouldBe NodeType.WIRE_VALUES
    }

    @Test
    fun `a PIPELINE node binds its pipeline reference and parameter map`() {
        val pipeline =
            parse(
                pipelineJson(
                    """{"id":"revenue","description":"d","type":"PIPELINE",
                       "pipeline":{"name":"monthly_revenue","version":4},
                       "parameters":{"start_date":"${'$'}{start_date}","region":"EU"},
                       "output":{"target":"tempdb","table":"stg_revenue"},"depends_on":[]}""",
                ),
            )
        val node = pipeline.nodes.single()

        node.type shouldBe NodeType.PIPELINE
        node.pipeline shouldBe PipelineNodeRef("monthly_revenue", 4)
        node.parameters shouldBe
            mapOf("start_date" to Fixtures.json("\"\${start_date}\""), "region" to Fixtures.json("\"EU\""))
        node.output shouldBe NodeOutput.Tempdb("stg_revenue")
    }

    @Test
    fun `an omitted output block on a PIPELINE node stays null`() {
        // No D1 default: a zero-caller child is side-effect-only, and §12.9's
        // output_on_sideeffect_child check needs to see the block was never declared.
        val pipeline =
            parse(
                pipelineJson(
                    """{"id":"revenue","description":"d","type":"PIPELINE",
                       "pipeline":{"name":"monthly_revenue","version":4},"depends_on":[]}""",
                ),
            )

        pipeline.nodes.single().output shouldBe null
    }

    @Test
    fun `an out-of-catalog output target is rejected with output_target_invalid`() {
        val outcome = deserializer.read(pipelineJson(node("""{"target":"kafka","topic":"orders"}""")))

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.OUTPUT_TARGET_INVALID)
    }

    @Test
    fun `an out-of-catalog write mode is rejected with output_mode_invalid`() {
        val outcome =
            deserializer.read(
                pipelineJson(node("""{"target":"datasource","datasource":"pg-warehouse","table":"c","mode":"upsert"}""")),
            )

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.OUTPUT_MODE_INVALID)
    }

    @Test
    fun `an absent write mode on a datasource output is rejected rather than defaulted`() {
        // §4.7 lists mode among the required fields; guessing would mean guessing between
        // "append" and a mode that TRUNCATES the target table.
        val outcome =
            deserializer.read(
                pipelineJson(node("""{"target":"datasource","datasource":"pg-warehouse","table":"c"}""")),
            )

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.OUTPUT_MODE_INVALID)
        outcome.result.failures
            .single()
            .message shouldContain "requires 'mode'"
    }

    @Test
    fun `a NULL parameter type is rejected - the one canonical type parameters may not declare`() {
        val outcome = deserializer.read(pipelineJson(NODE_NO_OUTPUT, parameters = """{"p":{"type":"NULL"}}"""))

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.PARAMETER_TYPE_INVALID)
    }

    @Test
    fun `a reserved staging engine is rejected with tempdb_engine_unsupported`() {
        val outcome =
            deserializer.read(
                pipelineJson(NODE_NO_OUTPUT, settings = """{"tempdb":{"engine":"DUCKDB"}}"""),
            )

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactly listOf(Validation.TEMPDB_ENGINE_UNSUPPORTED)
    }

    @Test
    fun `the pre-scan is exhaustive - every wire-value failure comes back together`() {
        // §17.2: all checks run, all failures collected. One error per round trip is the cost
        // this rule exists to remove.
        val outcome =
            deserializer.read(
                pipelineJson(
                    node(null, type = "SELECT"),
                    node("""{"target":"kafka"}""", id = "b"),
                    node("""{"target":"datasource","datasource":"d","table":"t","mode":"upsert"}""", id = "c"),
                    parameters = """{"p":{"type":"MONEY"}}""",
                    settings = """{"tempdb":{"engine":"DUCKDB"}}""",
                ),
            )

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        outcome.result.codes shouldContainExactlyInAnyOrder
            listOf(
                Validation.TEMPDB_ENGINE_UNSUPPORTED,
                Validation.PARAMETER_TYPE_INVALID,
                Validation.TYPE_INVALID,
                Validation.OUTPUT_TARGET_INVALID,
                Validation.OUTPUT_MODE_INVALID,
            )
    }

    @Test
    fun `reflected inbound values are truncated before reaching a message (CF-2)`() {
        val outcome = deserializer.read(pipelineJson(node(null, type = "X".repeat(500))))

        outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
        val reflected =
            outcome.result.failures
                .single()
                .details["value"] as String
        reflected shouldHaveMaxLength MAX_REFLECTED_VALUE_LENGTH + 1
    }

    @Test
    fun `missing optional fields bind leniently so the validator - not Jackson - reports them`() {
        // A payload with an absent id, source and template must still bind, or §17.2's
        // "all failures together" becomes "the first field Jackson tripped on".
        val pipeline = parse(pipelineJson("""{"type":"DQL"}"""))
        val node = pipeline.nodes.single()

        node.id shouldBe ""
        node.source shouldBe ""
        node.template shouldBe TemplateRef()
        node.dependsOn shouldContainExactly emptyList()
    }

    @Test
    fun `an absent settings block defaults to H2 with no config`() {
        val settings = parse(pipelineJson(NODE_NO_OUTPUT)).settings

        settings.tempdb.engine shouldBe StagingEngine.H2
        settings.tempdb.config shouldBe emptyMap()
        settings.tempdb.maxMemoryMb.shouldBeNull()
    }

    @Test
    fun `readOrThrow raises PipelineValidationException carrying every failure`() {
        val thrown =
            shouldThrow<PipelineValidationException> {
                deserializer.readOrThrow(pipelineJson(node(null, type = "SELECT")))
            }

        thrown.code shouldBe Validation.TYPE_INVALID
        thrown.result.failures.size shouldBe 1
    }

    private fun parse(json: String): Pipeline = deserializer.readOrThrow(json)

    private companion object {
        const val NODE_NO_OUTPUT =
            """{"id":"active_users","description":"d","type":"DQL","source":"pg-prod",
               "template":{"id":"t.sql","version":1},"depends_on":[]}"""

        fun node(
            output: String?,
            id: String = "a",
            type: String = "DQL",
        ): String =
            """{"id":"$id","description":"d","type":"$type","source":"pg-prod",
               "template":{"id":"t.sql","version":1},"depends_on":[]
               ${output?.let { ",\"output\":$it" }.orEmpty()}}"""

        fun pipelineJson(
            vararg nodes: String,
            parameters: String = "{}",
            settings: String = """{"tempdb":{"engine":"H2"}}""",
        ): String =
            """
            {
              "schema_version": 1,
              "name": "p",
              "display_name": "P",
              "description": "d",
              "settings": $settings,
              "parameters": $parameters,
              "nodes": [${nodes.joinToString(",")}]
            }
            """.trimIndent()
    }
}
