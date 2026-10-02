package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldHaveMaxLength
import io.kotest.matchers.string.shouldNotContain
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
                       "template":{"id":"test/t.sql","version":1},"depends_on":[]}""",
                    """{"id":"b","description":"d","type":"DDL","source":"pg-prod",
                       "template":{"id":"test/t.sql","version":1},"depends_on":[]}""",
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
        // §12.4's allowed-set, restated in the failure itself: the enum gaining `PIPELINE`, and
        // then `CALCULATOR` (five values now), must show up in what the author is told — a
        // pre-scan whose message lags the enum sends an author looking for a type that exists.
        val outcome = deserializer.read(pipelineJson(node(null, type = "SELECT")))

        val failure = (outcome as DeserializationOutcome.Rejected).result.failures.single()
        failure.message shouldContain "[DQL, DML, DDL, PIPELINE, CALCULATOR, TRANSFORM]"
        failure.details["allowed"] shouldBe NodeType.WIRE_VALUES
    }

    @Test
    fun `a PIPELINE node binds its pipeline reference and parameter map`() {
        val pipeline =
            parse(
                pipelineJson(
                    """{"id":"revenue","description":"d","type":"PIPELINE",
                       "pipeline":{"name":"test/monthly_revenue","version":4},
                       "parameters":{"start_date":"${'$'}{start_date}","region":"EU"},
                       "output":{"target":"tempdb","table":"stg_revenue"},"depends_on":[]}""",
                ),
            )
        val node = pipeline.nodes.single()

        node.type shouldBe NodeType.PIPELINE
        node.pipeline shouldBe PipelineNodeRef("test/monthly_revenue", 4)
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
                       "pipeline":{"name":"test/monthly_revenue","version":4},"depends_on":[]}""",
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

    /**
     * #333 — a JSON number or boolean where the contract declares a STRING is refused, never bound as text.
     *
     * `WireValueScan` type-checks only the enum-like wire values (engine, parameter type, output target and
     * mode, node type); every free-text field reached the bind unchecked, and Jackson's `StringDeserializer`
     * takes a number or a boolean as text unless the coercion config refuses it — `"display_name": 987654321`
     * bound as "987654321". The refusal is the family's malformed-body answer with `details.reason`
     * `wrong_type`, naming the PATH and never the value (a 9-digit sentinel is asserted ABSENT).
     */
    @Test
    fun `a number or a boolean where a string is declared is refused with its path, never bound as text`() {
        val base = pipelineJson(NODE_NO_OUTPUT, parameters = """{"p":{"type":"STRING","description":"d"}}""")
        val cases =
            listOf<Pair<String, (ObjectNode) -> ObjectNode>>(
                "display_name" to { it.also { t -> t.put("display_name", SENTINEL) } },
                "description" to { it.also { t -> t.put("description", SENTINEL) } },
                "name" to { it.also { t -> t.put("name", SENTINEL) } },
                "nodes[0].description" to { it.also { t -> (t.path("nodes").get(0) as ObjectNode).put("description", SENTINEL) } },
                "nodes[0].id" to { it.also { t -> (t.path("nodes").get(0) as ObjectNode).put("id", SENTINEL) } },
                "nodes[0].source" to { it.also { t -> (t.path("nodes").get(0) as ObjectNode).put("source", SENTINEL) } },
                "parameters.p.description" to {
                    it.also { t -> (t.path("parameters").get("p") as ObjectNode).put("description", SENTINEL) }
                },
            )

        cases.forEach { (path, mutate) ->
            withClue("a number at $path") {
                val outcome = deserializer.fromTree(mutate(JSON.readTree(base) as ObjectNode))

                outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
                val failure = outcome.result.failures.single()
                failure.code shouldBe Validation.SCHEMA_VERSION_UNSUPPORTED
                failure.path shouldBe path
                failure.details.keys shouldContainExactlyInAnyOrder setOf("reason", "expected")
                failure.details["reason"] shouldBe "wrong_type"
                failure.details["expected"] shouldBe "a string"
                failure.message shouldNotContain SENTINEL.toString()
            }
        }
        withClue("a boolean at display_name") {
            val outcome = deserializer.fromTree(JSON.readTree(base).also { (it as ObjectNode).put("display_name", true) })

            outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
            outcome.result.failures
                .single()
                .path shouldBe "display_name"
        }
    }

    @Test
    fun `a string or a float where an integer is declared is refused with its path, never coerced`() {
        val base = pipelineJson(NODE_NO_OUTPUT)

        listOf("1" to "schema_version", 1.5 to "schema_version").forEach { (value, path) ->
            withClue("$value at $path") {
                val tree = JSON.readTree(base) as ObjectNode
                when (value) {
                    is String -> tree.put(path, value)
                    is Double -> tree.put(path, value)
                }

                val outcome = deserializer.fromTree(tree)

                outcome.shouldBeInstanceOf<DeserializationOutcome.Rejected>()
                val failure = outcome.result.failures.single()
                failure.path shouldBe path
                failure.details["reason"] shouldBe "wrong_type"
                failure.details["expected"] shouldBe "an integer"
            }
        }
    }

    /**
     * The mapper alone — the belt under the reader's braces. Every caller that binds with
     * `PipelineJson.objectMapper()` WITHOUT the deserializer (a stored body read back, a peer's response)
     * meets the same refusal; [DerivedInputs] and the promotion client read trusted bodies through it.
     */
    @Test
    fun `the pipeline mapper alone refuses a number where a string is declared`() {
        val thrown =
            shouldThrow<MismatchedInputException> {
                PipelineJson.objectMapper().readValue("""{"name":$SENTINEL}""", Pipeline::class.java)
            }

        thrown.path.single().fieldName shouldBe "name"
        thrown.originalMessage shouldContain "Cannot coerce"
    }

    private fun parse(json: String): Pipeline = deserializer.readOrThrow(json)

    private companion object {
        /** A 9-digit value no refusal message may contain. */
        const val SENTINEL = 987654321

        val JSON = ObjectMapper()

        const val NODE_NO_OUTPUT =
            """{"id":"active_users","description":"d","type":"DQL","source":"pg-prod",
               "template":{"id":"test/t.sql","version":1},"depends_on":[]}"""

        fun node(
            output: String?,
            id: String = "a",
            type: String = "DQL",
        ): String =
            """{"id":"$id","description":"d","type":"$type","source":"pg-prod",
               "template":{"id":"test/t.sql","version":1},"depends_on":[]
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
