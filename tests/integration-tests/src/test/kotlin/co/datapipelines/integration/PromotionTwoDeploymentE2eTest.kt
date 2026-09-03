package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertAll
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID

/**
 * Promotion, end to end, across TWO real deployments (versioning §10; round 055 gates 3–5).
 *
 * ## Why two whole application contexts and two databases
 * Every interesting property of promotion is a property of the PAIR: the delta is computed
 * from the target's inventory, the actor is a row in the TARGET's `users` table, the version
 * numbers are preserved across two independent `pipeline_versions` sequences, and the
 * fail-closed credential is the receiver's to enforce. A single-context test with a mocked
 * client could not observe any of them — it would assert that the sender sends, which is the
 * half that was never in doubt.
 *
 * So: `dev` (authoring enabled, holds the target's URL and key) and `uat` (authoring
 * DISABLED, holds the same key as its `server-key`), each on its own Postgres and its own
 * Redis, both in this JVM. Assertions about what landed are made against **uat's database**,
 * not against the sender's response: a service's own report that it pushed three things is
 * not evidence that three things exist.
 *
 * ## What is proven, in order
 * 1. **Fail closed** (§10.6, gate 3) — no key, wrong key, and a receiver with promotion
 *    unconfigured all refuse; the server key on any OTHER route is a 401; promotion into an
 *    authoring-enabled deployment is refused.
 * 2. **The batch** (§10.4, gate 4) — a released pipeline with a template import AND a child
 *    pipeline promotes as one closure; uat holds all three at the SAME versions and hashes,
 *    stamped with the system service account, with `source_env = dev` recorded.
 *
 *    **The child-before-parent assertion is the receiver's own validator, not a comparison in
 *    this file**, and it is real: reversing `pipelineOrder` in `PromotionService` turns this
 *    test RED with `pipeline.validation.pipeline_not_found` on `nodes[0].pipeline` — the
 *    parent cannot resolve a child that has not landed yet — and takes four more tests with
 *    it (falsified 2026-09-02: 5 of 12 red on the reversal, 12/12 green restored).
 * 3. **Idempotency** — promoting again pushes nothing.
 * 4. **The delta** — bump and release ONE pipeline on dev; only that one moves.
 * 5. **Datasource pre-validation** (§10.5, gate 5) — a batch naming a datasource uat does not
 *    have fails WHOLE, with the consolidated code, and uat is byte-unchanged (row counts
 *    before and after).
 *
 * ## Reflection, deliberately
 * `tests/integration-tests` may depend only on `:modules:app` (module-structure §4.2,
 * enforced by `verifyModuleDependencies`), so `PromotionService` is on the RUNTIME classpath
 * but not the compile one. It is invoked reflectively, the same way
 * `SampleDataBootstrapE2eTest` drives `afterSingletonsInstantiated`. The alternative — driving
 * the UI form — would need a minted session cookie and would test the screen, not the rule.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PromotionTwoDeploymentE2eTest {
    private val mapper = ObjectMapper()

    /**
     * JUnit-shaped names over kotest's matchers: this module asserts with kotest, and every
     * assertion below reads better as `expected, actual, why` than as an infix chain buried in
     * a `withClue`. One place, so the two vocabularies meet exactly here.
     */
    private fun assertEquals(
        expected: Any?,
        actual: Any?,
        clue: String = "",
    ) = withClue(clue) { actual shouldBe expected }

    private fun assertTrue(
        condition: Boolean,
        clue: String = "",
    ) = withClue(clue) { condition shouldBe true }

    // ------------------------------------------------------------------ 1. fail closed (gate 3)

    @Test
    @Order(1)
    fun `the receiver refuses a promotion request with no key, a wrong key, and a near-miss key`() {
        assertAll(
            { assertEquals(401, inventoryOn(portUat, key = null).statusCode(), "no key") },
            { assertEquals(401, inventoryOn(portUat, key = "not-the-key").statusCode(), "wrong key") },
            // A shared prefix buys nothing — the compare is over the whole value.
            { assertEquals(401, inventoryOn(portUat, key = SERVER_KEY.dropLast(1)).statusCode(), "near miss") },
        )
        assertEquals(
            "auth.promotion.key_invalid",
            errorCodeOf(inventoryOn(portUat, key = "not-the-key")),
        )
    }

    @Test
    @Order(2)
    fun `a deployment with NO server-key configured refuses promotion entirely - fail closed`() {
        // dev holds a TARGET key (it sends) but no `server-key` of its own (it does not
        // receive). §10.6: absent ⇒ the endpoint refuses everything. Presenting the very key
        // dev uses to authenticate ITSELF elsewhere changes nothing.
        assertAll(
            { assertEquals(401, inventoryOn(portDev, key = SERVER_KEY).statusCode()) },
            { assertEquals("auth.promotion.key_invalid", errorCodeOf(inventoryOn(portDev, key = SERVER_KEY))) },
            { assertEquals(401, inventoryOn(portDev, key = null).statusCode()) },
        )
    }

    @Test
    @Order(3)
    fun `the server key grants NO access on any other route`() {
        // §10.6: "no other route consults it, and it grants no read access". Asserted on the
        // route a leaked promotion key would be most valuable on.
        listOf("/api/v1/pipelines", "/api/v1/templates", "/api/v1/datasources").forEach { path ->
            val response =
                httpClient.send(
                    HttpRequest
                        .newBuilder(URI.create("http://localhost:$portUat$path"))
                        .header(PROMOTION_HEADER, SERVER_KEY)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            assertEquals(401, response.statusCode(), "$path must not accept the promotion server key")
        }
    }

    @Test
    @Order(4)
    fun `the valid key opens the inventory, and only the inventory`() {
        val response = inventoryOn(portUat, key = SERVER_KEY)

        assertEquals(200, response.statusCode())
        val data = mapper.readTree(response.body()).path("data")
        assertEquals("uat", data.path("deployment").asText())
        assertEquals(false, data.path("authoring_enabled").asBoolean())
        assertEquals(WORKSPACE, data.path("workspace").asText())
    }

    @Test
    @Order(5)
    fun `promotion INTO an authoring-enabled deployment is refused`() {
        // dev has authoring on, so it is not a legal receiver whatever key it holds. Proven
        // against a deployment that DOES accept the credential, so the refusal is the
        // authoring guard and not the credential gate: uat's key is presented to a dev
        // configured, for this one probe, to accept it.
        val response =
            pushTo(portDevReceiver, key = SERVER_KEY, body = """{"source_env":"uat","key_fingerprint":"none","workspace":"$WORKSPACE"}""")

        assertEquals(409, response.statusCode())
        assertEquals("pipeline.promotion.target_is_authoring", errorCodeOf(response))
    }

    // ------------------------------------------------------------------ 2. the batch (gate 4)

    @Test
    @Order(10)
    fun `a released pipeline promotes with its template import and its child pipeline, at the same versions and hashes`() {
        seedContent()

        val applied = promoteOrExplain(PARENT)

        assertEquals(WORKSPACE, applied["workspace"])
        // The closure: parent + child, and both templates (the leaf and the library it imports).
        assertEquals(2, applied["pipelines"], "parent and child")
        assertEquals(2, applied["templates"], "the pinned template and its imported library")

        // --- What UAT actually holds. Read from uat's own database, not from the response. ---
        assertAll(
            { assertEquals(devPipelineVersion(PARENT), uatPipelineVersion(PARENT), "parent version") },
            { assertEquals(devPipelineVersion(CHILD), uatPipelineVersion(CHILD), "child version") },
            { assertEquals(devPipelineHash(PARENT), uatPipelineHash(PARENT), "parent body hash") },
            { assertEquals(devPipelineHash(CHILD), uatPipelineHash(CHILD), "child body hash") },
            { assertEquals(devTemplateVersion(LEAF_TEMPLATE), uatTemplateVersion(LEAF_TEMPLATE), "template version") },
            { assertEquals(devTemplateHash(LEAF_TEMPLATE), uatTemplateHash(LEAF_TEMPLATE), "template body hash") },
            { assertEquals(devTemplateVersion(LIB_TEMPLATE), uatTemplateVersion(LIB_TEMPLATE), "library version") },
        )
    }

    @Test
    @Order(11)
    fun `every promoted row is stamped with the system service account, never a human`() {
        // R7: the FKs are NOT NULL and the source's user ids mean nothing here.
        assertAll(
            { assertEquals("system", uatCreatedByProvider(PARENT)) },
            { assertEquals("system", uatCreatedByProvider(CHILD)) },
            { assertEquals("system@system.invalid", uatCreatedByEmail(PARENT)) },
            // Falsification: the actor is not simply "whatever user exists" — uat's own
            // seeded admin is a different row, and nothing promoted points at it.
            { assertTrue(uatCreatedByEmail(PARENT) != "e2e-promotion@datapipelines.test") },
        )
    }

    @Test
    @Order(12)
    fun `the receiver records where the batch came from, and never the key`() {
        val row =
            uatQuery(
                "SELECT details_json->>'source_env' AS env, details_json->>'key_fingerprint' AS fp," +
                    " details_json::text AS whole FROM audit_log WHERE event = 'auth.promotion.accepted'" +
                    " ORDER BY timestamp DESC LIMIT 1",
            )
        assertEquals("dev", row["env"])
        assertTrue(row["fp"]!!.startsWith("sha256:"), "a fingerprint, not a key: ${row["fp"]}")
        assertTrue(!row["whole"]!!.contains(SERVER_KEY), "the audit row carried the credential")
    }

    // ------------------------------------------------------------------ 3. idempotency

    @Test
    @Order(20)
    fun `promoting the same release again pushes nothing`() {
        // §10.2: same hash ⇒ nothing to push. The plan is the observable — a second promote()
        // of an unchanged pipeline is refused by §10.3's not_newer guard, which is the
        // correct answer: "same-version pushes are a bug, not a no-op to swallow".
        val plan = plan()
        assertEquals(emptyList<String>(), promotableNames(plan), "nothing should be promotable")

        val refusal = runCatching { promote(PARENT) }.exceptionOrNull()
        assertTrue(refusal != null, "a same-version push must be refused, not silently accepted")
        assertEquals("pipeline.promotion.not_newer", codeOf(refusal!!), "refusal was: $refusal")
    }

    // ------------------------------------------------------------------ 4. the delta

    @Test
    @Order(30)
    fun `bump and release ONE pipeline on dev - only that one moves`() {
        val childVersionBefore = uatPipelineVersion(CHILD)
        val parentVersionBefore = uatPipelineVersion(PARENT)

        bumpAndRelease(CHILD)

        // §10.2's listing: exactly the changed pipeline, and it is offered at its new version.
        val names = promotableNames(plan())
        assertEquals(listOf(CHILD), names)

        val applied = promoteOrExplain(CHILD)
        assertEquals(1, applied["pipelines"], "only the changed pipeline moves")
        // Its templates are already there at the same version and hash, so they are skipped.
        assertEquals(0, applied["templates"])

        assertAll(
            { assertEquals(childVersionBefore + 1, uatPipelineVersion(CHILD)) },
            { assertEquals(devPipelineHash(CHILD), uatPipelineHash(CHILD)) },
            { assertEquals(parentVersionBefore, uatPipelineVersion(PARENT), "the untouched pipeline did not move") },
        )
    }

    // ------------------------------------------------------------------ 5. §10.5 (gate 5)

    @Test
    @Order(40)
    fun `a batch naming a datasource uat does not have fails WHOLE, and uat is byte-unchanged`() {
        val before = uatRowCounts()

        // Registered on dev ONLY: a pipeline that would not save on the sender proves nothing
        // about the receiver's inventory. The asymmetry IS the case §10.5 exists for.
        registerDatasourceOn(portDev, ABSENT_DATASOURCE)
        createTemplateOn(portDev, ORPHAN_TEMPLATE, "SELECT 1 AS n")
        createPipelineOn(portDev, ORPHAN, dqlNode("read", ABSENT_DATASOURCE, ORPHAN_TEMPLATE))

        val refusal = runCatching { promote(ORPHAN) }.exceptionOrNull()

        assertTrue(refusal != null, "a batch referencing an absent datasource must be refused")
        assertEquals("pipeline.promotion.missing_datasources", codeOf(refusal!!), "refusal was: $refusal")
        assertTrue(refusal.message.orEmpty().contains(ABSENT_DATASOURCE), "the refusal must name what is missing")
        // The whole point of PRE-validation: nothing was pushed, so nothing changed.
        assertEquals(before, uatRowCounts(), "uat changed despite a refused batch")
    }

    // ------------------------------------------------------------------ 6. one transaction (§10.4)

    @Test
    @Order(45)
    fun `the receiver applies a batch in ONE transaction - a failing second entry rolls the first back`() {
        // §10.4: "the receiver applies it in one transaction or not at all". The SENDER's
        // pre-validation would refuse this batch before it ever left, so the batch is
        // hand-built and pushed RAW — the receiver's own atomicity is what is under test, and
        // a guard that only the sender enforces is not the guard §10.4 asks for.
        createTemplateOn(portDev, TX_TEMPLATE, "SELECT 2 AS n")
        createPipelineOn(portDev, TX_OK, dqlNode("read", DATASOURCE, LEAF_TEMPLATE))
        // TX_BAD pins a template that exists on dev, is NOT on uat, and is NOT in the batch —
        // so the receiver's import refuses it with `pipeline.import.missing_template`.
        createPipelineOn(portDev, TX_BAD, dqlNode("read", DATASOURCE, TX_TEMPLATE))

        val before = uatRowCounts()
        val batch =
            """
            {"source_env":"dev","key_fingerprint":"none","workspace":"$WORKSPACE","templates":[],
             "pipelines":[${devPipelinePayload(TX_OK)},${devPipelinePayload(TX_BAD)}]}
            """.trimIndent()

        val response = pushTo(portUat, key = SERVER_KEY, body = batch)

        assertEquals(400, response.statusCode(), "the batch must be refused: ${response.body()}")
        assertEquals("pipeline.import.missing_template", errorCodeOf(response))
        // The FIRST pipeline was valid and would have landed on its own. It did not.
        assertEquals(0, uatPipelineCount(TX_OK), "the first entry survived a failed batch — the transaction did not roll back")
        assertEquals(before, uatRowCounts(), "uat changed despite a refused batch")
    }

    // ------------------------------------------------------------------ content on dev

    /** A parent that runs a child through a PIPELINE node and pins a template that imports a library. */
    private fun seedContent() {
        registerDatasourceOn(portDev)
        registerDatasourceOn(portUat)
        createTemplateOn(portDev, LIB_TEMPLATE, "<#macro one>1</#macro>", isLibrary = true)
        // templates.md §6.3: the engine SYNTHESIZES `<#import ... as lib>` from the array — a
        // literal `<#import>` in a body is a forbidden construct, so the body only uses the alias.
        createTemplateOn(portDev, LEAF_TEMPLATE, "SELECT <@lib.one/> AS n", imports = listOf(LIB_TEMPLATE))
        createPipelineOn(portDev, CHILD, dqlNode("read", DATASOURCE, LEAF_TEMPLATE))
        createPipelineOn(portDev, PARENT, pipelineNode("run_child", CHILD))
    }

    private fun dqlNode(
        id: String,
        datasource: String,
        template: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "promotion e2e read",
            "type" to "DQL",
            "source" to datasource,
            "template" to mapOf("id" to template, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    private fun pipelineNode(
        id: String,
        child: String,
    ): Map<String, Any?> =
        mapOf(
            "id" to id,
            "description" to "promotion e2e child run",
            "type" to "PIPELINE",
            "pipeline" to mapOf("name" to child, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    // ------------------------------------------------------------------ driving promotion

    /** `PromotionService.plan(workspaceId, workspaceName)` — see the class KDoc on reflection. */
    private fun plan(): Any {
        val service = devPromotionService()
        val method = service.javaClass.getMethod("plan", UUID::class.java, String::class.java)
        return method.invoke(service, WORKSPACE_ID, WORKSPACE)
    }

    @Suppress("UNCHECKED_CAST")
    private fun promotableNames(plan: Any): List<String> {
        val candidates = plan.javaClass.getMethod("getPromotable").invoke(plan) as List<Any>
        return candidates.map { it.javaClass.getMethod("getName").invoke(it) as String }
    }

    /** `PromotionService.promote(...)`, unwrapping the reflective invocation exception. */
    private fun promote(vararg names: String): Map<String, Any?> {
        val service = devPromotionService()
        val method = service.javaClass.getMethod("promote", UUID::class.java, String::class.java, List::class.java)
        val applied =
            try {
                method.invoke(service, WORKSPACE_ID, WORKSPACE, names.toList())
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        return mapOf(
            "workspace" to applied.javaClass.getMethod("getWorkspace").invoke(applied),
            "templates" to applied.javaClass.getMethod("getTemplates").invoke(applied),
            "pipelines" to applied.javaClass.getMethod("getPipelines").invoke(applied),
        )
    }

    /**
     * `promote`, but a refusal fails the test with the CODE and the DETAILS rather than with a
     * message like "has unmet dependencies", which names nothing. The details map is where the
     * receiver puts `missing_datasources` / `missing_templates`, and it is the only thing that
     * makes a failed promotion diagnosable from a test report.
     */
    private fun promoteOrExplain(vararg names: String): Map<String, Any?> =
        try {
            promote(*names)
        } catch (e: Throwable) {
            throw AssertionError("promotion refused: code=${codeOf(e)} details=${detailsOf(e)} message=${e.message}", e)
        }

    private fun detailsOf(error: Throwable): Any? = error.javaClass.getMethod("getDetails").invoke(error)

    /**
     * A thrown `ApiException`'s catalogued CODE. `ApiException` is on the runtime classpath
     * only (see the class KDoc), and asserting on a message's text would pass for the wrong
     * reason the day a message is reworded — the code is the contract.
     */
    private fun codeOf(error: Throwable): String = error.javaClass.getMethod("getCode").invoke(error) as String

    private fun devPromotionService(): Any = checkNotNull(dev).getBean(Class.forName("co.datapipelines.web.pipelines.PromotionService"))

    // ------------------------------------------------------------------ REST helpers (dev)

    private fun registerDatasourceOn(
        port: Int,
        name: String = DATASOURCE,
    ) {
        val existing =
            given()
                .port(port)
                .header(API_KEY_HEADER, keyFor(port))
                .`when`()
                .get("/api/v1/datasources/$name")
        if (existing.statusCode == 200) return
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, keyFor(port))
            .body(
                """
                {"name": "$name", "display_name": "Promotion E2E", "dialect": "H2",
                 "jdbc_url": "jdbc:h2:mem:promo_${port}_$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                 "username": "sa", "password": "sa"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .extract()
            .let { require(it.statusCode() == 201) { "datasource '$name' create failed ${it.statusCode()}: ${it.body().asString()}" } }
    }

    private fun createTemplateOn(
        port: Int,
        id: String,
        body: String,
        isLibrary: Boolean = false,
        imports: List<String> = emptyList(),
    ) {
        val importsJson = imports.joinToString(",") { """{"id": "$it", "version": 1, "alias": "lib"}""" }
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, keyFor(port))
            .body(
                """
                {"id": "$id", "dialect": "H2", "display_name": "Promotion E2E $id",
                 "description": "Promotion E2E template", "is_library": $isLibrary,
                 "imports": [$importsJson], "body": ${mapper.writeValueAsString(body)}}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/templates")
            .then()
            .extract()
            .let { require(it.statusCode() == 201) { "template '$id' create failed ${it.statusCode()}: ${it.body().asString()}" } }
    }

    private fun createPipelineOn(
        port: Int,
        name: String,
        vararg nodes: Map<String, Any?>,
    ) {
        val body =
            mapper.writeValueAsString(
                mapOf(
                    "schema_version" to 1,
                    "name" to name,
                    "display_name" to name,
                    "description" to "Promotion E2E",
                    "nodes" to nodes.toList(),
                ),
            )
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, keyFor(port))
            .body(body)
            .`when`()
            .post("/api/v1/pipelines")
            .then()
            .extract()
            .let { require(it.statusCode() == 201) { "pipeline '$name' create failed ${it.statusCode()}: ${it.body().asString()}" } }
    }

    /** Write a draft (description change — content, not identity) and release it: version + 1. */
    private fun bumpAndRelease(name: String) {
        // The id comes from dev's own row, not from a listing: the paged listing's wire shape
        // is not what this test is about, and a fixture that depends on it breaks for reasons
        // that say nothing about promotion.
        val id = devJdbc.scalar("SELECT id::text FROM pipelines WHERE name = '$name' AND is_deleted = FALSE")

        val full =
            given()
                .port(portDev)
                .header(API_KEY_HEADER, keyFor(portDev))
                .`when`()
                .get("/api/v1/pipelines/$id")
                .then()
                .extract()
        val tree = mapper.readTree(full.body().asString()).path("data") as com.fasterxml.jackson.databind.node.ObjectNode
        val hash = tree.path("body_hash").asText()
        tree.put("description", "Promotion E2E, revised ${UUID.randomUUID()}")
        listOf("id", "version", "owner", "created_at", "updated_at", "status", "body_hash", "released_at", "current_version", "draft")
            .forEach(tree::remove)

        given()
            .port(portDev)
            .contentType(ContentType.JSON)
            .header(API_KEY_HEADER, keyFor(portDev))
            .header("If-Match", hash)
            .body(mapper.writeValueAsString(tree))
            .`when`()
            .put("/api/v1/pipelines/$id")
            .then()
            .statusCode(200)

        val draftHash =
            given()
                .port(portDev)
                .header(API_KEY_HEADER, keyFor(portDev))
                .`when`()
                .get("/api/v1/pipelines/$id")
                .then()
                .extract()
                .jsonPath()
                .getString("data.body_hash")

        given()
            .port(portDev)
            .header(API_KEY_HEADER, keyFor(portDev))
            .header("If-Match", draftHash)
            .`when`()
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
    }

    // ------------------------------------------------------------------ raw HTTP (the credential proofs)

    private fun inventoryOn(
        port: Int,
        key: String?,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/promotion/inventory?workspace=$WORKSPACE"))
                .GET()
        key?.let { builder.header(PROMOTION_HEADER, it) }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun pushTo(
        port: Int,
        key: String?,
        body: String,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/api/v1/promotion/push"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        key?.let { builder.header(PROMOTION_HEADER, it) }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun errorCodeOf(response: HttpResponse<String>): String =
        mapper
            .readTree(response.body())
            .path("error")
            .path("code")
            .asText()

    private fun errorCodeOf(response: Response): String = response.jsonPath().getString("error.code")

    /** One pipeline's stored body plus the §9.2 lifecycle fields, read straight from dev's DB. */
    private fun devPipelinePayload(name: String): String {
        val row =
            devJdbc.row(
                "SELECT p.id::text AS id, v.version::text AS version, v.body_hash AS hash, v.body_json::text AS body" +
                    " FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                    " WHERE p.name = '$name' AND v.version = p.current_version",
            )
        val node = mapper.readTree(row["body"]) as com.fasterxml.jackson.databind.node.ObjectNode
        node.put("id", row["id"])
        node.put("version", row["version"]!!.toInt())
        node.put("body_hash", row["hash"])
        return mapper.writeValueAsString(node)
    }

    private fun uatPipelineCount(name: String): Int = uatJdbc.scalar("SELECT COUNT(*) FROM pipelines WHERE name = '$name'").toInt()

    // ------------------------------------------------------------------ database assertions

    private fun devPipelineVersion(name: String): Int = pipelineVersion(devJdbc, name)

    private fun uatPipelineVersion(name: String): Int = pipelineVersion(uatJdbc, name)

    private fun devPipelineHash(name: String): String = pipelineHash(devJdbc, name)

    private fun uatPipelineHash(name: String): String = pipelineHash(uatJdbc, name)

    private fun devTemplateVersion(id: String): Int = templateVersion(devJdbc, id)

    private fun uatTemplateVersion(id: String): Int = templateVersion(uatJdbc, id)

    private fun devTemplateHash(id: String): String = templateHash(devJdbc, id)

    private fun uatTemplateHash(id: String): String = templateHash(uatJdbc, id)

    private fun pipelineVersion(
        jdbc: Jdbc,
        name: String,
    ): Int = jdbc.scalar("SELECT current_version FROM pipelines WHERE name = '$name' AND is_deleted = FALSE").toInt()

    private fun pipelineHash(
        jdbc: Jdbc,
        name: String,
    ): String =
        jdbc.scalar(
            "SELECT v.body_hash FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                " WHERE p.name = '$name' AND v.version = p.current_version",
        )

    private fun templateVersion(
        jdbc: Jdbc,
        id: String,
    ): Int = jdbc.scalar("SELECT current_version FROM templates WHERE name = '$id' AND is_deleted = FALSE").toInt()

    private fun templateHash(
        jdbc: Jdbc,
        id: String,
    ): String =
        jdbc.scalar(
            "SELECT v.body_hash FROM template_versions v JOIN templates t ON t.id = v.template_id" +
                " WHERE t.name = '$id' AND v.version = t.current_version",
        )

    private fun uatCreatedByProvider(name: String): String =
        uatJdbc.scalar(
            "SELECT u.provider FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                " JOIN users u ON u.id = v.created_by WHERE p.name = '$name' AND v.version = p.current_version",
        )

    private fun uatCreatedByEmail(name: String): String =
        uatJdbc.scalar(
            "SELECT u.email FROM pipeline_versions v JOIN pipelines p ON p.id = v.pipeline_id" +
                " JOIN users u ON u.id = v.created_by WHERE p.name = '$name' AND v.version = p.current_version",
        )

    private fun uatQuery(sql: String): Map<String, String> = uatJdbc.row(sql)

    /** The byte-unchanged assertion of gate 5: every table a promotion could have touched. */
    private fun uatRowCounts(): Map<String, String> =
        uatJdbc.row(
            "SELECT (SELECT COUNT(*) FROM pipelines)::text AS pipelines," +
                " (SELECT COUNT(*) FROM pipeline_versions)::text AS pipeline_versions," +
                " (SELECT COUNT(*) FROM templates)::text AS templates," +
                " (SELECT COUNT(*) FROM template_versions)::text AS template_versions",
        )

    /** A tiny JDBC reader — the suites in this module read rows directly rather than through Spring. */
    class Jdbc(
        private val url: String,
        private val user: String,
        private val password: String,
    ) {
        fun scalar(sql: String): String = row(sql).values.first()

        fun row(sql: String): Map<String, String> =
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        check(rs.next()) { "no row for: $sql" }
                        (1..rs.metaData.columnCount).associate { i ->
                            rs.metaData.getColumnLabel(i) to (rs.getString(i) ?: "")
                        }
                    }
                }
            }

        fun execute(sql: String) {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute(sql) }
            }
        }

        fun seedKey(key: E2eAuth.SeededKey) {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)
                        VALUES ('$ADMIN_USER_ID', 'e2e-promotion@datapipelines.test', 'E2E Promotion', 'test', 'e2e-promo-sub', TRUE, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """.trimIndent(),
                    )
                }
                val sql =
                    "INSERT INTO api_keys (id, user_id, name, key_hash, scopes, workspace_id)" +
                        " VALUES (?, ?, ?, ?, ?, '$WORKSPACE_ID_TEXT') ON CONFLICT (id) DO NOTHING"
                connection.prepareStatement(sql).use { ps ->
                    ps.setString(1, key.id)
                    ps.setObject(2, UUID.fromString(ADMIN_USER_ID))
                    ps.setString(3, key.name)
                    ps.setString(4, key.hash)
                    ps.setArray(5, connection.createArrayOf("text", key.scopes))
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun keyFor(port: Int): String = if (port == portDev) devKey.plaintext else uatKey.plaintext

    companion object {
        private const val REDIS_PORT = 6379
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val PROMOTION_HEADER = "DP-Promotion-Key"

        /**
         * The shared secret both deployments hold. A FIXTURE, deliberately low-entropy and
         * self-describing: a realistic `openssl rand -base64 32` string in a test file is a
         * secret scanner's true positive by construction, and the compare is over bytes.
         */
        private const val SERVER_KEY = "promotion-e2e-shared-key-not-a-real-secret"

        private const val WORKSPACE = "default"
        private const val WORKSPACE_ID_TEXT = "defa0000-0000-0000-0000-000000000001"
        private val WORKSPACE_ID: UUID = UUID.fromString(WORKSPACE_ID_TEXT)
        private const val ADMIN_USER_ID = "aaaa0000-0000-0000-0000-0000000000e2"

        private const val DATASOURCE = "promo_e2e_ds"
        private const val ABSENT_DATASOURCE = "promo_e2e_missing_on_uat"
        private const val LIB_TEMPLATE = "promo_e2e_lib.sql"
        private const val LEAF_TEMPLATE = "promo_e2e_leaf.sql"
        private const val ORPHAN_TEMPLATE = "promo_e2e_orphan.sql"
        private const val CHILD = "promo_e2e_child"
        private const val PARENT = "promo_e2e_parent"
        private const val ORPHAN = "promo_e2e_orphan"
        private const val TX_TEMPLATE = "promo_e2e_tx.sql"
        private const val TX_OK = "promo_e2e_tx_ok"
        private const val TX_BAD = "promo_e2e_tx_bad"

        private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

        @Container
        @JvmStatic
        val postgresDev: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").withDatabaseName("dp_dev").withUsername("dp").withPassword("dp")

        @Container
        @JvmStatic
        val postgresUat: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine").withDatabaseName("dp_uat").withUsername("dp").withPassword("dp")

        @Container
        @JvmStatic
        val redisDev: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(REDIS_PORT)

        @Container
        @JvmStatic
        val redisUat: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(REDIS_PORT)

        private var dev: ConfigurableApplicationContext? = null
        private var uat: ConfigurableApplicationContext? = null

        /**
         * A THIRD context: a deployment that both authors AND holds a promotion server-key —
         * the misconfiguration §10.1 D7 forbids. It exists only so the `target_is_authoring`
         * refusal can be proven against a receiver that ACCEPTS the credential, isolating the
         * authoring guard from the credential gate.
         */
        private var devReceiver: ConfigurableApplicationContext? = null

        private var portDev: Int = 0
        private var portUat: Int = 0
        private var portDevReceiver: Int = 0

        private lateinit var devJdbc: Jdbc
        private lateinit var uatJdbc: Jdbc
        private lateinit var devKey: E2eAuth.SeededKey
        private lateinit var uatKey: E2eAuth.SeededKey

        @BeforeAll
        @JvmStatic
        fun bootBothDeployments() {
            // UAT first: dev needs its port to configure the promotion target.
            uat =
                boot(
                    postgres = postgresUat,
                    redis = redisUat,
                    name = "uat",
                    authoring = false,
                    extra = arrayOf("--datapipelines.deployment.promotion.server-key=$SERVER_KEY"),
                )
            portUat = portOf(uat)

            dev =
                boot(
                    postgres = postgresDev,
                    redis = redisDev,
                    name = "dev",
                    authoring = true,
                    extra =
                        arrayOf(
                            "--datapipelines.deployment.promotion.target.base-url=http://localhost:$portUat",
                            "--datapipelines.deployment.promotion.target.server-key=$SERVER_KEY",
                        ),
                )
            portDev = portOf(dev)

            // The probe context (see its field's KDoc): authoring ON *and* a server-key.
            devReceiver =
                boot(
                    postgres = postgresDev,
                    redis = redisDev,
                    name = "dev-receiver-probe",
                    authoring = true,
                    extra = arrayOf("--datapipelines.deployment.promotion.server-key=$SERVER_KEY"),
                )
            portDevReceiver = portOf(devReceiver)

            devJdbc = Jdbc(postgresDev.jdbcUrl, postgresDev.username, postgresDev.password)
            uatJdbc = Jdbc(postgresUat.jdbcUrl, postgresUat.username, postgresUat.password)
            devKey = E2eAuth.generateKey("promotion-e2e-dev", arrayOf("admin"))
            uatKey = E2eAuth.generateKey("promotion-e2e-uat", arrayOf("admin"))
            devJdbc.seedKey(devKey)
            uatJdbc.seedKey(uatKey)
        }

        @AfterAll
        @JvmStatic
        fun closeBothDeployments() {
            devReceiver?.close()
            dev?.close()
            uat?.close()
        }

        private fun boot(
            postgres: PostgreSQLContainer<*>,
            redis: GenericContainer<*>,
            name: String,
            authoring: Boolean,
            extra: Array<String>,
        ): ConfigurableApplicationContext =
            SpringApplicationBuilder(DatapipelinesApplication::class.java)
                .run(
                    // Command-line args, not builder `.properties(...)`: those are DEFAULT
                    // properties and application.yml's `${...}` placeholders would override
                    // them. Args win (the gotcha DatasourcePoolInvalidationE2eTest records).
                    *
                        arrayOf(
                            "--server.port=0",
                            "--management.server.port=0",
                            "--spring.datasource.url=${postgres.jdbcUrl}",
                            "--spring.datasource.username=${postgres.username}",
                            "--spring.datasource.password=${postgres.password}",
                            "--spring.data.redis.host=${redis.host}",
                            "--spring.data.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                            "--spring.data.redis.password=",
                            "--datapipelines.redis.host=${redis.host}",
                            "--datapipelines.redis.port=${redis.getMappedPort(REDIS_PORT)}",
                            "--datapipelines.jwt.secret=$SECRET",
                            "--datapipelines.db.encryption-key=$SECRET",
                            // Local accounts satisfy §7's "at least one authentication method"
                            // without an OIDC stub; nothing here logs in interactively.
                            "--datapipelines.auth.local.enabled=true",
                            "--datapipelines.auth.base-url=http://localhost:8080",
                            "--datapipelines.deployment.name=$name",
                            "--datapipelines.deployment.authoring-enabled=$authoring",
                        ) + extra,
                )

        private fun portOf(context: ConfigurableApplicationContext?): Int =
            Integer.parseInt(checkNotNull(context?.environment?.getProperty("local.server.port")))

        private val SECRET: String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
