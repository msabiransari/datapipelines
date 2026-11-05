package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Real strict SCRIPT validation beside an append; a native aggregate keeps the invalid barrier's race observable. */
internal class ManagedLakeBarrierFixture(
    private val port: Int,
    private val session: String,
    private val lake: String,
) {
    private val json = ObjectMapper()

    @Suppress("LongMethod") // The complete four-node REST fixture is one explicit publication-barrier scenario.
    fun create(barrier: Boolean): Pair<Int, String?> {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val ids = listOf("land", "slow", "append", "reject").associateWith { "p4delivery/${it}_$suffix.sql" }
        val sql =
            mapOf(
                "land" to "SELECT * FROM delivery",
                "slow" to "SELECT sum(i)::BIGINT AS checked FROM range(1000000000) t(i)",
                "append" to "SELECT * FROM staged",
            )
        sql.forEach { (node, body) ->
            given()
                .port(port)
                .asSession(session)
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "id" to ids.getValue(node),
                            "dialect" to "DUCKDB",
                            "display_name" to node,
                            "description" to "P4 barrier acceptance",
                            "imports" to emptyList<String>(),
                            "body" to body,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
        }
        val columns = listOf(mapOf("name" to "id", "type" to "BIGINTEGER"))
        given()
            .port(port)
            .asSession(session)
            .contentType(ContentType.JSON)
            .body(
                json.writeValueAsString(
                    mapOf(
                        "id" to ids.getValue("reject"),
                        "display_name" to "Strict validation",
                        "description" to "P4 barrier acceptance",
                        "type" to "jsonata",
                        "body" to """{"rows":[],"rejects":[rows.{"row":$,"reason":"validation refused"}]}""",
                        "contract" to
                            mapOf(
                                "mode" to "row",
                                "inputs" to mapOf("orders" to mapOf("kind" to "table", "columns" to columns)),
                                "output" to mapOf("kind" to "table", "columns" to columns),
                                "rejects" to true,
                            ),
                        "invariants" to emptyList<Any>(),
                        "tests" to
                            listOf(
                                mapOf(
                                    "name" to "empty input",
                                    "input" to mapOf("rows" to emptyList<Any>(), "inputs" to emptyMap<String, Any>()),
                                    "expect" to mapOf("output" to mapOf("rows" to emptyList<Any>(), "rejects" to emptyList<Any>())),
                                ),
                            ),
                    ),
                ),
            ).post("/api/v1/templates")
            .then()
            .statusCode(201)
        val nodes =
            listOf(
                mapOf(
                    "id" to "land",
                    "description" to "Land",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "delivery" to mapOf("feed" to "orders", "id" to "${'$'}batch_id"),
                    "template" to ref(ids.getValue("land")),
                    "output" to mapOf("target" to "tempdb", "table" to "staged"),
                    "depends_on" to emptyList<String>(),
                ),
                mapOf(
                    "id" to "slow",
                    "description" to "Slow native validation",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to ref(ids.getValue("slow")),
                    "output" to mapOf("target" to "tempdb", "table" to "slow_check"),
                    "depends_on" to listOf("land"),
                ),
                mapOf(
                    "id" to "reject",
                    "description" to "Strict rejects",
                    "type" to "SCRIPT",
                    "template" to ref(ids.getValue("reject")),
                    "inputs" to mapOf("orders" to "staged"),
                    "output" to mapOf("target" to "tempdb", "table" to "checked", "rejects" to "rejected"),
                    "strict" to true,
                    "depends_on" to listOf("slow"),
                ),
                mapOf(
                    "id" to "append",
                    "description" to "Publication",
                    "type" to "DQL",
                    "source" to "tempdb",
                    "template" to ref(ids.getValue("append")),
                    "output" to mapOf("target" to "datasource", "datasource" to lake, "table" to "p4delivery.orders", "mode" to "append"),
                    "depends_on" to if (barrier) listOf("reject") else listOf("land"),
                ),
            )
        val response =
            given()
                .port(port)
                .asSession(session)
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "schema_version" to 1,
                            "name" to "p4delivery/barrier_$suffix",
                            "display_name" to "P4 barrier",
                            "description" to "P4 barrier acceptance",
                            "settings" to mapOf("tempdb" to mapOf("engine" to "DUCKDB")),
                            "parameters" to mapOf("batch_id" to mapOf("type" to "STRING", "required" to true)),
                            "nodes" to nodes,
                        ),
                    ),
                ).post("/api/v1/pipelines")
        return response.statusCode() to response.jsonPath().getString("data.id")
    }

    fun verify(
        jdbc: JdbcTemplate,
        batch: String,
        execute: (String, String) -> String,
    ) {
        val (badStatus, badId) = create(barrier = false)
        val selected =
            if (badStatus == 201) {
                requireNotNull(badId)
            } else {
                val (status, id) = create(barrier = true)
                status shouldBe 201
                requireNotNull(id)
            }
        val before =
            jdbc.queryForObject(
                "SELECT count(*) FROM lake_table_revisions WHERE namespace = 'p4delivery'",
                Int::class.java,
            )
        val events = E2eSse.parseEvents(execute(selected, batch), json)
        // With the barrier guard planted out, execute the admitted bad graph: this assertion observes the race itself.
        withClue("append_started must stay zero until every validation succeeds") {
            events.count { it.first == "node_started" && it.second.path("node_id").asText() == "append" } shouldBe 0
        }
        events
            .single { it.first == "node_failed" }
            .second
            .path("error")
            .path("code")
            .asText() shouldBe "pipeline.script.rejects_strict"
        jdbc.queryForObject("SELECT state FROM lake_deliveries WHERE delivery_id = ?", String::class.java, batch) shouldBe "FAILED"
        jdbc.queryForObject("SELECT failure_phase FROM lake_deliveries WHERE delivery_id = ?", String::class.java, batch) shouldBe
            "validation"
        jdbc.queryForObject("SELECT count(*) FROM lake_table_revisions WHERE namespace = 'p4delivery'", Int::class.java) shouldBe before
        badStatus shouldBe 400
        println("case=strict_barrier append_started=0 ledger=FAILED phase=validation added_revisions=0")
    }

    private fun ref(id: String) = mapOf("id" to id, "version" to 1)
}
