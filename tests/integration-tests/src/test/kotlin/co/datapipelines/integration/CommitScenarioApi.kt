package co.datapipelines.integration

import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.http.ContentType

/**
 * The REST authoring half of the #1035 commit scenarios (R4): datasource, templates and pipelines created through the
 * application's own API as the seeded workspace admin, and the node shapes the scenarios share. Every node reads and
 * writes the ONE customer datasource [datasource], which is what makes a family a local-write family.
 */
class CommitScenarioApi(
    private val port: Int,
    private val session: String,
    private val datasource: String,
) {
    private val mapper = ObjectMapper()

    fun registerDatasource(
        jdbcUrl: String,
        username: String,
        password: String,
    ) {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(session)
            .body(
                """
                {"name": "$datasource", "display_name": "Commit scenario $datasource", "dialect": "POSTGRES",
                 "jdbc_url": "$jdbcUrl", "username": "$username", "password": "$password"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(HTTP_CREATED)
    }

    /** Creates and releases a template (a pipeline can only reference released ones). */
    fun template(
        id: String,
        body: String,
    ) {
        val response =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(session)
                .body(
                    """
                    {"id": "$id", "dialect": "POSTGRES", "display_name": "$id", "description": "Commit scenario template", "imports": [],
                     "body": ${mapper.writeValueAsString(body)}}
                    """.trimIndent(),
                ).`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(HTTP_CREATED)
                .extract()
        given()
            .port(port)
            .asSession(session)
            .header("If-Match", response.jsonPath().getString("data.body_hash"))
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(HTTP_OK)
    }

    /** Creates a draft pipeline and returns its id. */
    fun pipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to name,
                    "description" to "Commit scenario pipeline $name",
                    "parameters" to emptyMap<String, String>(),
                    "nodes" to nodes,
                ),
            )
        val response =
            given()
                .port(
                    port,
                ).contentType(ContentType.JSON)
                .asSession(session)
                .body(body)
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        check(
            response.statusCode() == HTTP_CREATED,
        ) { "pipeline $name creation failed (${response.statusCode()}): ${response.body().asString()}" }
        return response.jsonPath().getString("data.id")
    }

    /** Creates and releases a pipeline (a child for a PIPELINE node, or a released run target) and returns its id. */
    fun releasedPipeline(
        name: String,
        nodes: List<Map<String, Any?>>,
    ): String {
        val id = pipeline(name, nodes)
        val hash =
            given()
                .port(port)
                .asSession(session)
                .`when`()
                .get("/api/v1/pipelines/$id")
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(
                port,
            ).asSession(session)
            .header("If-Match", hash)
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(HTTP_OK)
        return id
    }

    /** A DQL node that copies the rows [template] returns into [table] (append). */
    fun copy(
        id: String,
        template: String,
        table: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Copy the source rows onto $table",
            "type" to "DQL",
            "source" to datasource,
            "template" to mapOf("id" to template, "version" to 1),
            "output" to mapOf("target" to "datasource", "datasource" to datasource, "table" to table, "mode" to "append"),
            "depends_on" to dependsOn,
        )

    /** A DQL node whose rows go back to the invoking caller. */
    fun caller(
        id: String,
        template: String,
        dependsOn: List<String> = emptyList(),
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Return rows to the invoking caller",
            "type" to "DQL",
            "source" to datasource,
            "template" to mapOf("id" to template, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to dependsOn,
        )

    /** A DML node whose deferred foreign key the family's COMMIT must check. */
    fun deferredWrite(
        id: String,
        template: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Write a row whose deferred foreign key the COMMIT must check",
            "type" to "DML",
            "source" to datasource,
            "template" to mapOf("id" to template, "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to dependsOn,
        )

    /** A PIPELINE node invoking the released child [childName] and appending its rows onto [table]. */
    fun child(
        id: String,
        childName: String,
        table: String,
        dependsOn: List<String>,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "Invoke $childName v1 onto $table",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to childName, "version" to 1),
            "output" to mapOf("target" to "datasource", "datasource" to datasource, "table" to table, "mode" to "append"),
            "depends_on" to dependsOn,
        )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
    }
}
