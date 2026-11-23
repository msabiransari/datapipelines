package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID

/**
 * #1111 — one development key allowed to call every live route of a published namespace (auth.md §7.7; rest-api §19.5),
 * end to end over HTTP against a real metadata database and a real PostgreSQL target.
 *
 * The scenario is ordered because it is ONE operator's story told against one stored grant: a key bound once at
 * `/school/v1` with `"allow": true` calls routes published after the binding; the shadow and scope rules decide which
 * nodes it reaches; its grant is downgraded, unbound and revoked under fresh calls, retries and polls; and the SAME rows
 * are then read by a second application context with the authoring capability off (a receiver posture) and a third with
 * mutations withheld. Every effect claim is a row count in the target or the metadata database read on this suite's
 * own JDBC connections — never inferred from an answer.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["datapipelines.endpoints.mutations-enabled=true"],
)
@Suppress("LargeClass") // one ordered operator story over one seed; splitting it would re-seed and re-boot per part
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthoringNamespaceKeyIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()
    private val routes = mutableMapOf<String, String>()
    private val contexts = mutableListOf<ConfigurableApplicationContext>()

    private lateinit var dev: Key
    private lateinit var reader: Key
    private lateinit var foreign: Key

    /** The run the lifecycle cases admitted (on the POST entity later purged). */
    private lateinit var admitted: String

    /** The run admitted on the recreated POST entity; the posture cases replay and poll it. */
    private lateinit var postureRun: String

    @BeforeAll
    fun seed() {
        E2eClean.beforeSeeding()
        seedRows()
        seedTarget()
        registerDatasource()
        sqlTemplate(READ_TEMPLATE, "SELECT count(*) AS n FROM ns_hits")
        sqlTemplate(HIT_TEMPLATE, "INSERT INTO ns_hits (tag) VALUES (CAST(:tag AS text))")
        pipeline(READ_PIPELINE, read(), emptyMap())
        pipeline(HIT_PIPELINE, hit(), mapOf("tag" to mapOf("type" to "STRING", "required" to true, "description" to "The row's tag.")))
        routes["seed"] = publish("$NS/seed", "GET", READ_PIPELINE)
    }

    @AfterAll
    fun closeContexts() {
        contexts.forEach { it.close() }
    }

    // -----------------------------------------------------------------------------------------
    // Before the opt-in: the ordinary GET binding admits no write
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(1)
    fun `before the opt-in, an ordinary GET binding at the namespace is refused a newly published mutation`() {
        dev = mint("ns-dev", """["$NS"]""")
        reader = mint("ns-reader", """["$NS"]""")
        foreign = mint("ns-foreign", "[]", workspace = OTHER_WORKSPACE)
        routes["before GET"] = publish("$NS/before", "GET", READ_PIPELINE)
        routes["before POST"] = publish("$NS/before", "POST", HIT_PIPELINE)

        val refused = mutate(dev, "POST", "$NS/before?tag=pre-change", "pre-change-1")
        withClue(refused.asString()) {
            refused.statusCode() shouldBe 405
            refused.jsonPath().getString("error.code") shouldBe "endpoint.method_not_allowed"
            allowOf(refused) shouldBe setOf("GET")
        }
        hits("pre-change") shouldBe 0
        markedRows(dev) shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // The agent journey: bind once, then publish and call
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(2)
    fun `bound once with allow true, the key calls every route published beneath the node afterwards - each effect once`() {
        val ack = allow(dev, NS, true)
        withClue(ack.asString()) {
            ack.statusCode() shouldBe 201
            ack.jsonPath().getString("data.path_prefix") shouldBe NS
            ack.jsonPath().getString("data.api_key_id") shouldBe dev.id
            ack.jsonPath().getString("data.api_key_name") shouldBe "ns-dev"
            ack.jsonPath().getBoolean("data.allow") shouldBe true
            ack.jsonPath().getBoolean("data.bound") shouldBe true
        }
        val bindingsAfterSetup = bindingRows(dev)

        routes["orders GET"] = publish(ORDERS, "GET", READ_PIPELINE)
        routes["orders POST"] = publish(ORDERS, "POST", HIT_PIPELINE)
        routes["orders PUT"] = publish(ORDERS, "PUT", HIT_PIPELINE)
        routes["orders DELETE"] = publish(ORDERS, "DELETE", HIT_PIPELINE)
        routes["pupil PUT"] = publish("$NS/pupils/{tag}", "PUT", HIT_PIPELINE)
        routes["inbox POST"] = publish(INBOX, "POST", HIT_PIPELINE)

        withClue("the new GET route") { get(dev, ORDERS).statusCode() shouldBe 200 }
        val calls =
            listOf(
                Triple("POST", "$ORDERS?tag=j-post", "j-post"),
                Triple("PUT", "$ORDERS?tag=j-put", "j-put"),
                Triple("DELETE", "$ORDERS?tag=j-delete", "j-delete"),
                Triple("PUT", "$NS/pupils/j-pupil", "j-pupil"),
                Triple("POST", "$INBOX?tag=j-inbox", "j-inbox"),
                Triple("POST", "$NS/before?tag=j-before", "j-before"),
            )
        for ((method, path, tag) in calls) {
            withClue("$method $path") {
                val first = mutate(dev, method, path, "journey-$tag")
                withClue(first.asString()) { first.statusCode() shouldBe 200 }
                val id = first.jsonPath().getString("execution_id")
                first.jsonPath().getString("status") shouldBe "SUCCESS"
                hits(tag) shouldBe 1

                val retry = mutate(dev, method, path, "journey-$tag")
                withClue(retry.asString()) { retry.statusCode() shouldBe 200 }
                retry.jsonPath().getString("execution_id") shouldBe id
                hits(tag) shouldBe 1
                executions(id) shouldBe 1

                val statusUrl = first.jsonPath().getString("status_url")
                statusUrl shouldBe "/api${path.substringBefore('?')}/executions/$id"
                val status = poll(dev, statusUrl)
                withClue(status.asString()) { status.statusCode() shouldBe 200 }
                status.jsonPath().getString("data.status") shouldBe "SUCCESS"
                // A discard-output write has no rows to read: the result poll answers 204, under the same grant.
                poll(dev, "$statusUrl/result").statusCode() shouldBe 204
            }
        }

        val unsupported = mutate(dev, "PATCH", ORDERS, "journey-patch")
        withClue(unsupported.asString()) {
            unsupported.statusCode() shouldBe 405
            allowOf(unsupported) shouldBe setOf("GET", "POST", "PUT", "DELETE")
        }
        withClue("no further key change was needed") { bindingRows(dev) shouldBe bindingsAfterSetup }
    }

    // -----------------------------------------------------------------------------------------
    // Scope and shadowing
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(3)
    fun `a sibling namespace with a similar prefix and another workspace's root opt-in reach nothing here`() {
        routes["beta GET"] = publish("/school/v1-beta/x", "GET", READ_PIPELINE)
        routes["beta POST"] = publish("/school/v1-beta/x", "POST", HIT_PIPELINE)
        val sibling = mutate(dev, "POST", "/school/v1-beta/x?tag=sibling", "scope-sibling")
        withClue(sibling.asString()) { sibling.statusCode() shouldBe 404 }

        val rootOfOther = allow(foreign, "/", true, workspace = OTHER_WORKSPACE)
        withClue(rootOfOther.asString()) { rootOfOther.statusCode() shouldBe 201 }
        val crossing = mutate(foreign, "POST", "$ORDERS?tag=foreign", "scope-foreign")
        withClue(crossing.asString()) { crossing.statusCode() shouldBe 404 }
        hits("sibling") shouldBe 0
        hits("foreign") shouldBe 0
    }

    @Test
    @Order(4)
    fun `an ordinary key at the same node neither writes nor shadows the marked key`() {
        val ordinary = mutate(reader, "POST", "$ORDERS?tag=same-node-reader", "same-node-reader")
        withClue(ordinary.asString()) {
            ordinary.statusCode() shouldBe 405
            allowOf(ordinary) shouldBe setOf("GET")
        }
        mutate(dev, "POST", "$ORDERS?tag=same-node-dev", "same-node-dev").statusCode() shouldBe 200
        hits("same-node-reader") shouldBe 0
        hits("same-node-dev") shouldBe 1
    }

    @Test
    @Order(5)
    fun `a nearer ordinary binding shadows the opt-in - another key's or the key's own - and a nearer opt-in restores it`() {
        // Another key's ordinary binding at the route's own node decides that subtree: the dev key is not bound there.
        bind(reader, ORDERS)
        try {
            val shadowed = mutate(dev, "POST", "$ORDERS?tag=shadow-other", "shadow-other")
            withClue(shadowed.asString()) { shadowed.statusCode() shouldBe 404 }
            withClue("the shadow is that subtree only") {
                mutate(dev, "POST", "$INBOX?tag=shadow-inbox", "shadow-inbox").statusCode() shouldBe 200
            }
        } finally {
            unbind(reader, ORDERS)
        }
        hits("shadow-other") shouldBe 0

        // The key's OWN ordinary nearer binding shadows its upper opt-in too: it reads there and writes nothing.
        bind(dev, ORDERS).jsonPath().getMap<String, Any?>("data").containsKey("allow") shouldBe false
        val own = mutate(dev, "POST", "$ORDERS?tag=shadow-own", "shadow-own")
        withClue(own.asString()) {
            own.statusCode() shouldBe 405
            allowOf(own) shouldBe setOf("GET")
        }
        get(dev, ORDERS).statusCode() shouldBe 200

        // A deeper explicit opt-in admits again; removing that row returns the subtree to the upper opt-in.
        allow(dev, ORDERS, true).jsonPath().getBoolean("data.allow") shouldBe true
        mutate(dev, "POST", "$ORDERS?tag=deeper-opt-in", "deeper-opt-in").statusCode() shouldBe 200
        unbind(dev, ORDERS)
        mutate(dev, "POST", "$ORDERS?tag=upper-again", "upper-again").statusCode() shouldBe 200
        hits("shadow-own") shouldBe 0
        hits("deeper-opt-in") shouldBe 1
        hits("upper-again") shouldBe 1
    }

    @Test
    @Order(6)
    fun `an exact route grant admits its one method where the opt-in is shadowed`() {
        bind(reader, ORDERS)
        grant(dev, ORDERS, "POST")
        try {
            mutate(dev, "POST", "$ORDERS?tag=exact-shadowed", "exact-shadowed").statusCode() shouldBe 200
            val put = mutate(dev, "PUT", "$ORDERS?tag=exact-put", "exact-put")
            withClue(put.asString()) {
                put.statusCode() shouldBe 405
                allowOf(put) shouldBe setOf("POST")
            }
        } finally {
            revokeGrant(dev, ORDERS, "POST")
            unbind(reader, ORDERS)
        }
        hits("exact-shadowed") shouldBe 1
        hits("exact-put") shouldBe 0
    }

    @Test
    @Order(7)
    fun `a disabled route and a retired route are not admitted by the opt-in`() {
        sql("UPDATE published_endpoints SET is_enabled = FALSE WHERE id = '${routes.getValue("before POST")}'")
        // No route sets is_enabled (rest-api §19.5); a publish is what invalidates every registry snapshot.
        routes["flush"] = publish("$NS/flush-1", "GET", READ_PIPELINE)
        try {
            val disabled = mutate(dev, "POST", "$NS/before?tag=disabled", "disabled-1")
            withClue(disabled.asString()) {
                disabled.statusCode() shouldBe 405
                allowOf(disabled) shouldBe setOf("GET")
            }
        } finally {
            sql("UPDATE published_endpoints SET is_enabled = TRUE WHERE id = '${routes.getValue("before POST")}'")
            publish("$NS/flush-2", "GET", READ_PIPELINE)
        }
        hits("disabled") shouldBe 0
        mutate(dev, "POST", "$NS/before?tag=enabled-again", "enabled-again").statusCode() shouldBe 200

        // A released route is never deleted (#786 R1): it is retired by discarding its released version.
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .post("/api/v1/endpoints/${routes.getValue("orders DELETE")}/versions/1/discard")
            .then()
            .statusCode(200)
        // The opt-in widens no lifecycle rule: the retired route answers the key exactly what it answers any grant
        // (no servable version), and a key without a grant on it is still refused the method.
        val retired = mutate(dev, "DELETE", "$ORDERS?tag=deleted", "deleted-1")
        withClue(retired.asString()) {
            retired.statusCode() shouldBe 503
            retired.jsonPath().getString("error.code") shouldBe "endpoint.pipeline_not_released"
        }
        val ungranted = mutate(reader, "DELETE", "$ORDERS?tag=deleted", "deleted-2")
        withClue(ungranted.asString()) {
            ungranted.statusCode() shouldBe 405
            allowOf(ungranted) shouldBe setOf("GET")
        }
        hits("deleted") shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // Grant lifecycle: fresh calls, retries and both polling URLs
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(10)
    fun `downgrade, unbind and re-allow decide the next fresh call, retry and poll - and add no effect`() {
        val first = mutate(dev, "POST", "$ORDERS?tag=life", LIFE_KEY)
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        admitted = first.jsonPath().getString("execution_id")
        val urls = listOf("/api$ORDERS/executions/$admitted", "/api$ORDERS/executions/$admitted/result")

        // Downgrade: the binding stays (the key still reads), the opt-in is gone.
        val down = allow(dev, NS, false)
        down.jsonPath().getBoolean("data.allow") shouldBe false
        down.jsonPath().getBoolean("data.bound") shouldBe true
        refusedEverywhere(expectedStatus = 405, fresh = "life-down")
        get(dev, ORDERS).statusCode() shouldBe 200

        // Re-allowed: the reservation outlived the downgrade — the retry replays the original, nothing reruns.
        allow(dev, NS, true).statusCode() shouldBe 201
        val replay = mutate(dev, "POST", "$ORDERS?tag=life", LIFE_KEY)
        withClue(replay.asString()) { replay.statusCode() shouldBe 200 }
        replay.jsonPath().getString("execution_id") shouldBe admitted
        poll(dev, urls[0]).statusCode() shouldBe 200

        // Unbound: the GET row and its opt-in go together; the key reaches nothing at the node any more.
        unbind(dev, NS)
        refusedEverywhere(expectedStatus = 404, fresh = "life-unbound")
        val recreated = allow(dev, NS, true)
        recreated.jsonPath().getBoolean("data.bound") shouldBe true
        mutate(dev, "POST", "$ORDERS?tag=life", LIFE_KEY).jsonPath().getString("execution_id") shouldBe admitted
        hits("life") shouldBe 1
        hits("life-down") shouldBe 0
        hits("life-unbound") shouldBe 0
    }

    @Test
    @Order(11)
    fun `a different key, a different path and a recreated route never read the original run`() {
        val urls = listOf("/api$ORDERS/executions/$admitted", "/api$ORDERS/executions/$admitted/result")
        urls.forEach { withClue("reader $it") { poll(reader, it).statusCode() shouldBe 404 } }
        poll(dev, "/api$INBOX/executions/$admitted").statusCode() shouldBe 404
        poll(dev, "/api$INBOX/executions/$admitted/result").statusCode() shouldBe 404

        // A released route has no delete verb (#786 R1), so the operator's purge stands in for deletion; the route
        // published again at the same path is a NEW entity: callable, and the old run is not its run.
        sql("DELETE FROM published_endpoints WHERE id = '${routes.getValue("orders POST")}'")
        routes["orders POST"] = publish(ORDERS, "POST", HIT_PIPELINE)
        urls.forEach { withClue("recreated $it") { poll(dev, it).statusCode() shouldBe 404 } }
        mutate(dev, "POST", "$ORDERS?tag=recreated", "recreated-1").statusCode() shouldBe 200
        hits("recreated") shouldBe 1

        // The run the posture cases replay and poll: admitted on the CURRENT entity.
        val posture = mutate(dev, "POST", "$ORDERS?tag=posture", POSTURE_KEY)
        withClue(posture.asString()) { posture.statusCode() shouldBe 200 }
        postureRun = posture.jsonPath().getString("execution_id")
    }

    @Test
    @Order(12)
    fun `a revoked key is refused its fresh call, its retry and its polls, and nothing runs again`() {
        val doomed = mint("ns-doomed", "[]")
        allow(doomed, NS, true).statusCode() shouldBe 201
        val first = mutate(doomed, "POST", "$ORDERS?tag=revoked", "revoked-1")
        withClue(first.asString()) { first.statusCode() shouldBe 200 }
        val id = first.jsonPath().getString("execution_id")

        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/auth/api-keys/${doomed.id}")
            .then()
            .statusCode(204)
        mutate(doomed, "POST", "$ORDERS?tag=revoked-fresh", "revoked-2").statusCode() shouldBe 401
        mutate(doomed, "POST", "$ORDERS?tag=revoked", "revoked-1").statusCode() shouldBe 401
        poll(doomed, "/api$ORDERS/executions/$id").statusCode() shouldBe 401
        poll(doomed, "/api$ORDERS/executions/$id/result").statusCode() shouldBe 401
        hits("revoked") shouldBe 1
        hits("revoked-fresh") shouldBe 0
    }

    // -----------------------------------------------------------------------------------------
    // Postures: the same stored opt-in read by a receiver and by a deployment withholding mutations
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(20)
    fun `with authoring off the stored opt-in is dormant - listed, unusable, withdrawable - and exact grants still work`() {
        val receiver = boot("--datapipelines.deployment.authoring-enabled=false", "--datapipelines.endpoints.mutations-enabled=true")

        withClue("the operator inventory lists the dormant opt-in") {
            inventory() shouldContain listOf(DEFAULT_WORKSPACE, NS, dev.id, "ns-dev")
        }
        get(dev, ORDERS, receiver).statusCode() shouldBe 200
        val dormant = mutate(dev, "POST", "$ORDERS?tag=dormant", "dormant-1", receiver)
        withClue(dormant.asString()) {
            dormant.statusCode() shouldBe 405
            allowOf(dormant) shouldBe setOf("GET")
        }
        mutate(dev, "POST", "$ORDERS?tag=posture", POSTURE_KEY, receiver).statusCode() shouldBe 405
        poll(dev, "/api$ORDERS/executions/$postureRun", receiver).statusCode() shouldBe 404
        poll(dev, "/api$ORDERS/executions/$postureRun/result", receiver).statusCode() shouldBe 404

        // An exact grant is independent of the posture.
        grant(reader, INBOX, "POST", receiver)
        mutate(reader, "POST", "$INBOX?tag=receiver-exact", "receiver-exact", receiver).statusCode() shouldBe 200

        // Turning an opt-in ON is refused before any write; the stored row is unchanged.
        val before = bindingRows(dev)
        val refused = allow(dev, NS, true, onPort = receiver)
        withClue(refused.asString()) {
            refused.statusCode() shouldBe 409
            refused.jsonPath().getString("error.code") shouldBe "endpoint.authoring_disabled"
        }
        bindingRows(dev) shouldBe before

        // The same stored row is live on the authoring deployment at this moment.
        mutate(dev, "POST", "$ORDERS?tag=posture-on", "posture-on").statusCode() shouldBe 200

        // Withdrawn on the receiver: the authoring deployment stops honouring it on its next call.
        val withdrawn = allow(dev, NS, false, onPort = receiver)
        withClue(withdrawn.asString()) { withdrawn.statusCode() shouldBe 201 }
        mutate(dev, "POST", "$ORDERS?tag=after-withdraw", "after-withdraw").statusCode() shouldBe 405

        // Re-enabling the posture WITHOUT a retained grant opens nothing; re-allowing it on an authoring deployment does.
        allow(dev, NS, true).statusCode() shouldBe 201
        mutate(dev, "POST", "$ORDERS?tag=re-enabled", "re-enabled").statusCode() shouldBe 200

        // Removal on the receiver (unbind) is permitted too.
        unbind(dev, NS, receiver)
        mutate(dev, "POST", "$ORDERS?tag=after-removal", "after-removal").statusCode() shouldBe 404
        allow(dev, NS, true).statusCode() shouldBe 201

        hits("dormant") shouldBe 0
        hits("receiver-exact") shouldBe 1
        hits("posture-on") shouldBe 1
        hits("after-withdraw") shouldBe 0
        hits("re-enabled") shouldBe 1
        hits("after-removal") shouldBe 0
        hits("posture") shouldBe 1
    }

    @Test
    @Order(21)
    fun `with mutations withheld a fresh authorised write is 503, while the admitted replay and its polls keep their contract`() {
        val withheld = boot("--datapipelines.endpoints.mutations-enabled=false")
        val fresh = mutate(dev, "POST", "$ORDERS?tag=withheld", "withheld-1", withheld)
        withClue(fresh.asString()) {
            fresh.statusCode() shouldBe 503
            fresh.jsonPath().getString("error.code") shouldBe "endpoint.mutations_disabled"
        }
        val replay = mutate(dev, "POST", "$ORDERS?tag=posture", POSTURE_KEY, withheld)
        withClue(replay.asString()) { replay.statusCode() shouldBe 200 }
        replay.jsonPath().getString("execution_id") shouldBe postureRun
        poll(dev, "/api$ORDERS/executions/$postureRun", withheld).statusCode() shouldBe 200
        poll(dev, "/api$ORDERS/executions/$postureRun/result", withheld).statusCode() shouldBe 204
        hits("withheld") shouldBe 0
        hits("posture") shouldBe 1
    }

    // -----------------------------------------------------------------------------------------
    // Management and persistence
    // -----------------------------------------------------------------------------------------

    @Test
    @Order(30)
    fun `explicit null, a non-boolean, or allow beside a mutation method is 400 and writes nothing`() {
        val before = bindingRows(dev)
        val audits = auditCount(dev)
        for (value in listOf("null", "\"true\"", "1", "{}")) {
            val response = bindBody("""{"api_key_id": "${dev.id}", "path_prefix": "$NS", "allow": $value}""")
            withClue("$value ${response.asString()}") {
                response.statusCode() shouldBe 400
                response.jsonPath().getString("error.code") shouldBe "pipeline.execution.invalid_parameter_type"
                response.jsonPath().getString("error.details.reason") shouldBe "wrong_type"
                response.jsonPath().getString("error.details.path") shouldBe "allow"
            }
        }
        for (method in listOf("POST", "PUT", "DELETE")) {
            val response =
                bindBody("""{"api_key_id": "${dev.id}", "path_prefix": "$ORDERS", "method": "$method", "allow": false}""")
            withClue("$method ${response.asString()}") {
                response.statusCode() shouldBe 400
                response.jsonPath().getString("error.details.reason") shouldBe "allow_with_method"
            }
        }
        bindingRows(dev) shouldBe before
        auditCount(dev) shouldBe audits
    }

    @Test
    @Order(31)
    fun `true repeats idempotently, omission preserves the stored opt-in, and false at an absent node creates nothing`() {
        val rows = bindingRows(dev)
        val repeat = allow(dev, NS, true)
        repeat.jsonPath().getBoolean("data.allow") shouldBe true
        bindingRows(dev) shouldBe rows

        val omitted = bind(dev, NS)
        withClue(omitted.asString()) {
            omitted.statusCode() shouldBe 201
            omitted.jsonPath().getMap<String, Any?>("data").keys shouldBe setOf("path_prefix", "api_key_name", "api_key_id")
        }
        markedAt(dev, NS) shouldBe true

        val absent = allow(dev, INBOX, false)
        withClue(absent.asString()) {
            absent.statusCode() shouldBe 201
            absent.jsonPath().getBoolean("data.allow") shouldBe false
            absent.jsonPath().getBoolean("data.bound") shouldBe false
        }
        count("SELECT count(*) FROM endpoint_key_bindings WHERE api_key_id = '${dev.id}' AND path_prefix = '$INBOX'") shouldBe 0

        // Each explicit request is audited with what was asked and what the one statement left stored.
        val audited =
            rows(
                "SELECT details_json->>'path_prefix', details_json->>'requested', details_json->>'allow', " +
                    "details_json->>'bound' FROM audit_log WHERE event = 'endpoint.key_namespace_writes' " +
                    "AND key_id = '${dev.id}' ORDER BY id DESC LIMIT 1",
            )
        audited shouldBe listOf(listOf(INBOX, "false", "false", "false"))
    }

    @Test
    @Order(32)
    fun `the normal UI save of the key's association keeps a retained node's opt-in`() {
        val saved =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.URLENC)
                .formParam("bindings", NS)
                .`when`()
                .post("/partials/api-keys/${dev.id}/bindings")
        withClue(saved.asString()) { saved.statusCode() shouldBe 200 }
        markedAt(dev, NS) shouldBe true
        mutate(dev, "POST", "$ORDERS?tag=after-ui-save", "after-ui-save").statusCode() shouldBe 200
    }

    @Test
    @Order(33)
    fun `by id and by name resolve only the caller's keys of the active workspace - same owner, same name elsewhere`() {
        val here = mint("ns-twin", "[]")
        val there = mint("ns-twin", "[]", workspace = OTHER_WORKSPACE)
        val byName = bindBody("""{"api_key_name": "ns-twin", "path_prefix": "$NS", "allow": true}""")
        withClue(byName.asString()) {
            byName.statusCode() shouldBe 201
            byName.jsonPath().getString("data.api_key_id") shouldBe here.id
        }
        markedAt(here, NS) shouldBe true
        bindingRows(there) shouldBe emptyList()

        val byForeignId = bindBody("""{"api_key_id": "${there.id}", "path_prefix": "$NS", "allow": true}""")
        withClue(byForeignId.asString()) { byForeignId.statusCode() shouldBe 404 }
        bindingRows(there) shouldBe emptyList()
    }

    @Test
    @Order(34)
    fun `a member without the bind permission and a key of another kind are refused before anything is written`() {
        val author =
            given()
                .port(port)
                .asSession(AUTHOR_SESSION)
                .contentType(ContentType.JSON)
                .body("""{"api_key_id": "${reader.id}", "path_prefix": "$NS", "allow": true}""")
                .`when`()
                .post("/api/v1/endpoints/bindings")
        withClue(author.asString()) { author.statusCode() shouldBe 403 }
        markedAt(reader, NS) shouldBe false

        val mcp =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body("""{"name": "ns-mcp", "kind": "mcp", "role": "author"}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.id")
        val wrongKind = bindBody("""{"api_key_id": "$mcp", "path_prefix": "$NS", "allow": true}""")
        withClue(wrongKind.asString()) { wrongKind.statusCode() shouldBe 404 }
        count("SELECT count(*) FROM endpoint_key_bindings WHERE api_key_id = '$mcp'") shouldBe 0
    }

    @Test
    @Order(35)
    fun `the database refuses the opt-in on an exact route grant`() {
        val refused =
            shouldThrow<SQLException> {
                sql(
                    "UPDATE endpoint_key_bindings SET allow_namespace_writes = TRUE " +
                        "WHERE api_key_id = '${reader.id}' AND method = 'POST'",
                )
            }
        refused.message.orEmpty() shouldContain "chk_endpoint_key_bindings_namespace_writes"
    }

    @Test
    @Order(36)
    fun `historical rows whose key belongs to another workspace neither decide, shadow nor grant, and are inventoried`() {
        // As a pre-#1111 by-name bind could have written them: the FOREIGN workspace's key, under THIS workspace.
        sql(
            "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, method, endpoint_id, created_by, " +
                "allow_namespace_writes) VALUES " +
                "('$ORDERS', '${foreign.id}', '$DEFAULT_WORKSPACE', 'GET', NULL, '$ADMIN_USER', FALSE), " +
                "('$NS', '${foreign.id}', '$DEFAULT_WORKSPACE', 'GET', NULL, '$ADMIN_USER', TRUE), " +
                "('$ORDERS', '${foreign.id}', '$DEFAULT_WORKSPACE', 'POST', '${routes.getValue("orders POST")}', '$ADMIN_USER', FALSE)",
        )
        count(MISMATCH_PREFLIGHT) shouldBe 3

        withClue("a mismatched nearer row does not shadow the legitimate opt-in") {
            mutate(dev, "POST", "$ORDERS?tag=mismatch-dev", "mismatch-dev").statusCode() shouldBe 200
        }
        withClue("a mismatched row grants its key nothing") {
            mutate(foreign, "POST", "$ORDERS?tag=mismatch-foreign", "mismatch-foreign").statusCode() shouldBe 404
        }
        hits("mismatch-dev") shouldBe 1
        hits("mismatch-foreign") shouldBe 0

        // The documented cleanup: one transaction, previewed by its count, deleting exactly the mismatched rows.
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.autoCommit = false
            c.createStatement().use { s -> s.executeUpdate(MISMATCH_CLEANUP) shouldBe 3 }
            c.commit()
        }
        count(MISMATCH_PREFLIGHT) shouldBe 0
        markedAt(dev, NS) shouldBe true
    }

    /** The operator's three statements are the runbook's own text (metadata-db §4.14): a doc edit that drifts goes red here. */
    @Test
    @Order(37)
    fun `the runbook prints the inventory, preflight and cleanup this suite executes, verbatim`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, METADATA_DOC).isFile) dir = dir.parentFile
        val doc = File(checkNotNull(dir) { "$METADATA_DOC not found" }, METADATA_DOC).readText()
        listOf(INVENTORY, MISMATCH_PREFLIGHT, MISMATCH_CLEANUP).forEach { statement -> doc shouldContain statement }
    }

    @Test
    @Order(40)
    fun `a deactivated workspace's routes are not admitted by the opt-in, and reactivation restores it`() {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .post("/api/v1/workspaces/default/deactivate")
            .then()
            .statusCode(200)
        try {
            val refused = mutate(dev, "POST", "$ORDERS?tag=deactivated", "deactivated-1")
            withClue(refused.asString()) {
                refused.statusCode() shouldBe 404
                refused.jsonPath().getString("error.code") shouldBe "auth.key_workspace_inactive"
            }
        } finally {
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .header("DP-Workspace", OTHER_WORKSPACE)
                .`when`()
                .post("/api/v1/workspaces/default/reactivate")
                .then()
                .statusCode(200)
        }
        hits("deactivated") shouldBe 0
        mutate(dev, "POST", "$ORDERS?tag=reactivated", "reactivated-1").statusCode() shouldBe 200
        hits("reactivated") shouldBe 1
    }

    // ---------------------------------------------------------------------------------- helpers

    /** The fresh call, the admitted retry and both polls of [admitted] all refused; nothing runs. */
    private fun refusedEverywhere(
        expectedStatus: Int,
        fresh: String,
    ) {
        val freshCall = mutate(dev, "POST", "$ORDERS?tag=$fresh", fresh)
        withClue("fresh ${freshCall.asString()}") { freshCall.statusCode() shouldBe expectedStatus }
        val retry = mutate(dev, "POST", "$ORDERS?tag=life", LIFE_KEY)
        withClue("retry ${retry.asString()}") {
            retry.statusCode() shouldBe expectedStatus
            retry.header("DP-Execution-Id") shouldBe null
            retry.asString() shouldNotContain admitted
        }
        poll(dev, "/api$ORDERS/executions/$admitted").statusCode() shouldBe 404
        poll(dev, "/api$ORDERS/executions/$admitted/result").statusCode() shouldBe 404
    }

    private fun allowOf(response: Response): Set<String> =
        response
            .header("Allow")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun mutate(
        key: Key,
        method: String,
        path: String,
        idempotencyKey: String,
        onPort: Int = port,
    ): Response =
        given()
            .port(onPort)
            .header(API_KEY_HEADER, key.plaintext)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .`when`()
            .request(method, "/api$path")
            .thenReturn()

    private fun get(
        key: Key,
        path: String,
        onPort: Int = port,
    ): Response =
        given()
            .port(onPort)
            .header(API_KEY_HEADER, key.plaintext)
            .`when`()
            .get("/api$path")
            .thenReturn()

    private fun poll(
        key: Key,
        url: String,
        onPort: Int = port,
    ): Response =
        given()
            .port(onPort)
            .header(API_KEY_HEADER, key.plaintext)
            .`when`()
            .get(url)
            .thenReturn()

    private fun bindBody(
        body: String,
        workspace: String? = null,
        onPort: Int = port,
    ): Response {
        val spec = given().port(onPort).asSession(ADMIN_SESSION).contentType(ContentType.JSON)
        workspace?.let { spec.header(WORKSPACE_HEADER, it) }
        return spec
            .body(body)
            .`when`()
            .post("/api/v1/endpoints/bindings")
            .thenReturn()
    }

    private fun allow(
        key: Key,
        node: String,
        value: Boolean,
        workspace: String? = null,
        onPort: Int = port,
    ): Response = bindBody("""{"api_key_id": "${key.id}", "path_prefix": "$node", "allow": $value}""", workspace, onPort)

    private fun bind(
        key: Key,
        node: String,
    ): Response =
        bindBody("""{"api_key_id": "${key.id}", "path_prefix": "$node"}""").also {
            withClue(it.asString()) { it.statusCode() shouldBe 201 }
        }

    private fun unbind(
        key: Key,
        node: String,
        onPort: Int = port,
    ) {
        given()
            .port(onPort)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints/bindings?pathPrefix=$node&apiKeyId=${key.id}")
            .then()
            .statusCode(204)
    }

    private fun grant(
        key: Key,
        path: String,
        method: String,
        onPort: Int = port,
    ) {
        val response = bindBody("""{"api_key_id": "${key.id}", "path_prefix": "$path", "method": "$method"}""", onPort = onPort)
        withClue(response.asString()) { response.statusCode() shouldBe 201 }
    }

    private fun revokeGrant(
        key: Key,
        path: String,
        method: String,
    ) {
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .`when`()
            .delete("/api/v1/endpoints/bindings?pathPrefix=$path&apiKeyId=${key.id}&method=$method")
            .then()
            .statusCode(204)
    }

    private fun mint(
        name: String,
        bindings: String,
        workspace: String? = null,
    ): Key {
        val spec = given().port(port).contentType(ContentType.JSON).asSession(ADMIN_SESSION)
        workspace?.let { spec.header(WORKSPACE_HEADER, it) }
        val minted =
            spec
                .body("""{"name": "$name", "kind": "endpoint", "bindings": $bindings}""")
                .`when`()
                .post("/api/v1/auth/api-keys")
                .thenReturn()
        withClue(minted.asString()) { minted.statusCode() shouldBe 201 }
        return Key(minted.jsonPath().getString("data.id"), minted.jsonPath().getString("data.key"))
    }

    /** Publishes [path]/[method] pinning v1 of [pipeline] and releases it; the entity id. */
    private fun publish(
        path: String,
        method: String,
        pipeline: String,
    ): String {
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body("""{"path": "$path", "method": "$method", "pipeline": "$pipeline", "pipeline_version": 1, "timeout_seconds": 60}""")
                .`when`()
                .post("/api/v1/endpoints")
                .thenReturn()
        withClue("publish $method $path: ${created.asString()}") { created.statusCode() shouldBe 201 }
        val id = created.jsonPath().getString("data.id")
        val hash =
            given()
                .port(port)
                .asSession(ADMIN_SESSION)
                .get("/api/v1/endpoints/$id/versions/1")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .post("/api/v1/endpoints/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    /**
     * A second application context on the same metadata database and Redis; its port. [overrides] are the posture under
     * test, so each names `mutations-enabled` itself (a repeated argument would bind as the list "true,false").
     */
    private fun boot(vararg overrides: String): Int {
        val context =
            SpringApplicationBuilder(DatapipelinesApplication::class.java).run(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=${SharedE2e.postgres.jdbcUrl}",
                "--spring.datasource.username=${SharedE2e.postgres.username}",
                "--spring.datasource.password=${SharedE2e.postgres.password}",
                "--datapipelines.redis.password=",
                "--datapipelines.redis.host=${SharedE2e.redisHost}",
                "--datapipelines.redis.port=${SharedE2e.redisPort}",
                "--datapipelines.jwt.secret=$JWT_SECRET",
                "--datapipelines.db.encryption-key=$ENCRYPTION_KEY",
                "--datapipelines.auth.local.enabled=true",
                "--datapipelines.endpoints.key-request-budget.max-requests=$KEY_BUDGET",
                *overrides,
            )
        contexts += context
        return Integer.parseInt(checkNotNull(context.environment.getProperty("local.server.port")))
    }

    // ---------------------------------------------------------------------------------- database

    private fun hits(tag: String): Int =
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM ns_hits WHERE tag = '$tag'").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun executions(id: String): Int = count("SELECT count(*) FROM pipeline_executions WHERE execution_id = '$id'")

    private fun markedRows(key: Key): Int =
        count("SELECT count(*) FROM endpoint_key_bindings WHERE api_key_id = '${key.id}' AND allow_namespace_writes")

    private fun markedAt(
        key: Key,
        node: String,
    ): Boolean =
        count(
            "SELECT count(*) FROM endpoint_key_bindings WHERE api_key_id = '${key.id}' AND path_prefix = '$node' " +
                "AND method = 'GET' AND allow_namespace_writes",
        ) == 1

    /** Every row of [key], as `method path allow`, ordered — the "nothing was written" comparator. */
    private fun bindingRows(key: Key): List<String> =
        rows(
            "SELECT method || ' ' || path_prefix || ' ' || allow_namespace_writes::text FROM endpoint_key_bindings " +
                "WHERE api_key_id = '${key.id}' ORDER BY method, path_prefix",
        ).map { it.first().orEmpty() }

    private fun auditCount(key: Key): Int =
        count("SELECT count(*) FROM audit_log WHERE event = 'endpoint.key_namespace_writes' AND key_id = '${key.id}'")

    /** The documented operator inventory (metadata-db §4.14), run verbatim: one list per row. */
    private fun inventory(): List<List<String?>> = rows(INVENTORY).map { it.take(INVENTORY_IDENTITY_COLUMNS) }

    private fun count(sql: String): Int = rows(sql).first().first().orEmpty().toInt()

    private fun rows(sql: String): List<List<String?>> =
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    val columns = rs.metaData.columnCount
                    generateSequence { if (rs.next()) (1..columns).map { rs.getString(it) } else null }.toList()
                }
            }
        }

    private fun sql(statement: String) {
        DriverManager.getConnection(SharedE2e.postgres.jdbcUrl, SharedE2e.postgres.username, SharedE2e.postgres.password).use { c ->
            c.createStatement().use { it.execute(statement) }
        }
    }

    // ---------------------------------------------------------------------------------- seed

    private fun seedRows() {
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                "('$ADMIN_USER', '$ADMIN_EMAIL', 'NS Admin', 'test', 'ns1111-admin', TRUE, TRUE), " +
                "('$AUTHOR_USER', '$AUTHOR_EMAIL', 'NS Author', 'test', 'ns1111-author', TRUE, FALSE)",
        )
        sql(
            "INSERT INTO workspaces (id, name, display_name, created_by) " +
                "VALUES ('$OTHER_WORKSPACE_ID', '$OTHER_WORKSPACE', 'Namespace other workspace', '$ADMIN_USER')",
        )
        sql(
            "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES " +
                "('$DEFAULT_WORKSPACE', '$ADMIN_USER', 'workspace_admin'), " +
                "('$OTHER_WORKSPACE_ID', '$ADMIN_USER', 'workspace_admin'), " +
                "('$DEFAULT_WORKSPACE', '$AUTHOR_USER', 'author')",
        )
    }

    private fun seedTarget() {
        DriverManager.getConnection(target.jdbcUrl, target.username, target.password).use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE IF NOT EXISTS ns_hits (tag text NOT NULL, at timestamptz NOT NULL DEFAULT now())")
                s.execute("TRUNCATE ns_hits")
            }
        }
    }

    private fun registerDatasource() {
        given()
            .port(port)
            .contentType(ContentType.JSON)
            .asSession(ADMIN_SESSION)
            .body(
                """
                {"name": "$DATASOURCE", "display_name": "Namespace 1111", "dialect": "POSTGRES",
                 "jdbc_url": "${target.jdbcUrl}", "username": "${target.username}", "password": "${target.password}"}
                """.trimIndent(),
            ).`when`()
            .post("/api/v1/datasources")
            .then()
            .statusCode(201)
    }

    private fun sqlTemplate(
        id: String,
        body: String,
    ) {
        val payload =
            mapOf(
                "id" to id,
                "dialect" to "POSTGRES",
                "display_name" to id,
                "description" to "#1111 template",
                "imports" to emptyList<Any>(),
                "body" to body,
            )
        val hash =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(payload))
                .`when`()
                .post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getString("data.body_hash")
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", hash)
            .contentType(ContentType.JSON)
            .body("""{"name": "$id"}""")
            .`when`()
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun read(): Map<String, Any?> =
        mapOf(
            "id" to "read",
            "description" to "Counts the rows",
            "type" to "DQL",
            "source" to DATASOURCE,
            "template" to mapOf("id" to READ_TEMPLATE, "version" to 1),
            "output" to mapOf("target" to "caller"),
            "depends_on" to emptyList<String>(),
        )

    private fun hit(): Map<String, Any?> =
        mapOf(
            "id" to "write",
            "description" to "One tagged row",
            "type" to "DML",
            "source" to DATASOURCE,
            "template" to mapOf("id" to HIT_TEMPLATE, "version" to 1),
            "operation" to "update",
            "output" to mapOf("target" to "discard"),
            "depends_on" to emptyList<String>(),
        )

    /** Creates [name] with one [node] and releases its v1 — a route pins an exact released version. */
    private fun pipeline(
        name: String,
        node: Map<String, Any?>,
        parameters: Map<String, Any?>,
    ) {
        val document =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Namespace 1111 $name",
                "description" to "#1111 namespace-writes fixture",
                "parameters" to parameters,
                "nodes" to listOf(node),
            )
        val created =
            given()
                .port(port)
                .contentType(ContentType.JSON)
                .asSession(ADMIN_SESSION)
                .body(mapper.writeValueAsString(document))
                .`when`()
                .post("/api/v1/pipelines")
                .thenReturn()
        withClue("create $name: ${created.asString()}") { created.statusCode() shouldBe 201 }
        given()
            .port(port)
            .asSession(ADMIN_SESSION)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .`when`()
            .post("/api/v1/pipelines/${created.jsonPath().getString("data.id")}/release")
            .then()
            .statusCode(200)
    }

    private class Key(
        val id: String,
        val plaintext: String,
    )

    companion object {
        private const val API_KEY_HEADER = "DP-API-Key"
        private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        private const val WORKSPACE_HEADER = "DP-Workspace"
        private const val DATASOURCE = "ns1111"
        private const val DEFAULT_WORKSPACE = "defa0000-0000-0000-0000-000000000001"
        private const val OTHER_WORKSPACE_ID = "defa0000-0000-0000-0000-000000001111"
        private const val OTHER_WORKSPACE = "ns-other"

        private const val NS = "/school/v1"
        private const val ORDERS = "$NS/orders"
        private const val INBOX = "$NS/inbox"
        private const val LIFE_KEY = "life-1"
        private const val POSTURE_KEY = "posture-1"

        private const val READ_TEMPLATE = "test/ns1111_read.sql"
        private const val HIT_TEMPLATE = "test/ns1111_hit.sql"
        private const val READ_PIPELINE = "test/ns1111_read"
        private const val HIT_PIPELINE = "test/ns1111_hit"
        private const val KEY_BUDGET = "100000"

        /** metadata-db §4.14 — the read-only inventory of stored opt-ins, never selecting a secret. */
        private const val INVENTORY =
            "SELECT b.workspace_id, b.path_prefix, b.api_key_id, k.name, b.created_by, b.created_at " +
                "FROM endpoint_key_bindings b JOIN api_keys k ON k.id = b.api_key_id " +
                "WHERE b.allow_namespace_writes ORDER BY b.workspace_id, b.path_prefix, b.api_key_id"
        private const val INVENTORY_IDENTITY_COLUMNS = 4

        /** metadata-db §4.14 — the count-only preflight of rows whose key belongs to another workspace. */
        private const val MISMATCH_PREFLIGHT =
            "SELECT count(*) FROM endpoint_key_bindings b JOIN api_keys k ON k.id = b.api_key_id " +
                "WHERE k.workspace_id <> b.workspace_id"

        /** metadata-db §4.14 — the cleanup, run inside a transaction whose row count is checked before COMMIT. */
        private const val METADATA_DOC = "docs/metadata-db.md"
        private const val MISMATCH_CLEANUP =
            "DELETE FROM endpoint_key_bindings b USING api_keys k WHERE k.id = b.api_key_id AND k.workspace_id <> b.workspace_id"

        private val ADMIN_USER: String = UUID.randomUUID().toString()
        private const val ADMIN_EMAIL = "ns1111-admin@datapipelines.test"
        private val AUTHOR_USER: String = UUID.randomUUID().toString()
        private const val AUTHOR_EMAIL = "ns1111-author@datapipelines.test"
        private val JWT_SECRET = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_USER, ADMIN_EMAIL)
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_USER, AUTHOR_EMAIL)

        private val target = SharedE2e.scratchDatabase("namespace1111_target")

        /** One key for every context: a datasource password one context encrypted must decrypt on the others. */
        private val ENCRYPTION_KEY: String =
            Base64.getEncoder().encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { SharedE2e.postgres.jdbcUrl }
            registry.add("spring.datasource.username") { SharedE2e.postgres.username }
            registry.add("spring.datasource.password") { SharedE2e.postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { SharedE2e.redisHost }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.local.enabled") { "true" }
            // One dev key drives the whole story; the serve path's per-key budget would refuse it mid-suite.
            registry.add("datapipelines.endpoints.key-request-budget.max-requests") { KEY_BUDGET }
        }
    }
}
