package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import java.util.UUID

/** HTTP authoring and source upload fixtures; execution itself stays on the actual app entry point. */
internal class ManagedLakeDeliveryHttp(
    private val port: Int,
    private val adminJwt: String,
    private val lakeName: String,
    private val bucket: String,
) {
    private val json = ObjectMapper()

    fun createPipeline(): String {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val templates = listOf("land" to "SELECT * FROM delivery", "append" to "SELECT * FROM staged")
        templates.forEach { (node, sql) ->
            given()
                .port(port)
                .asSession(adminJwt)
                .contentType(ContentType.JSON)
                .body(
                    json.writeValueAsString(
                        mapOf(
                            "id" to "p4delivery/${node}_$suffix.sql",
                            "dialect" to "DUCKDB",
                            "display_name" to node,
                            "description" to "P4 delivery acceptance",
                            "imports" to emptyList<String>(),
                            "body" to sql,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
        }
        return given()
            .port(port)
            .asSession(adminJwt)
            .contentType(ContentType.JSON)
            .body(
                """
                {"schema_version":1,"name":"p4delivery/run_$suffix","display_name":"P4 delivery","description":"P4 delivery acceptance",
                 "settings":{"tempdb":{"engine":"DUCKDB"}},"parameters":{"batch_id":{"type":"STRING","required":true}},
                 "nodes":[{"id":"land","description":"Read the immutable delivery","type":"DQL","source":"tempdb",
                   "delivery":{"feed":"orders","id":"${'$'}batch_id"},"template":{"id":"p4delivery/land_$suffix.sql","version":1},
                   "output":{"target":"tempdb","table":"staged"},"depends_on":[]},
                  {"id":"append","description":"Publish after every validation","type":"DQL","source":"tempdb",
                   "template":{"id":"p4delivery/append_$suffix.sql","version":1},
                   "output":{"target":"datasource","datasource":"$lakeName","table":"p4delivery.orders","mode":"append"},
                   "depends_on":["land"]}]}
                """.trimIndent(),
            ).post("/api/v1/pipelines")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("data.id")
    }

    fun putDelivery(): String {
        val batch = "batch_" + UUID.randomUUID().toString()
        SharedE2e.s3Client().use { client ->
            client.putObject(
                {
                    it.bucket(bucket).key("landing/orders/$batch/input.csv")
                },
                software.amazon.awssdk.core.sync.RequestBody
                    .fromString("id\n11\n22\n"),
            )
            client.putObject(
                { it.bucket(bucket).key("landing/orders/$batch/_done") },
                software.amazon.awssdk.core.sync.RequestBody
                    .empty(),
            )
        }
        return batch
    }
}
