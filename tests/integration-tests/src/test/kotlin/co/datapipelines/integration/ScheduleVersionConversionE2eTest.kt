package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * #786 §D.5 — the in-place conversion of existing schedules at a real upgrade boot (owner ruling 2026-11-08: one
 * server, convert in place; ruling 2 changes the pin, never the binding semantics). [EndpointVersionMigrationE2eTest]'s
 * shape: its own PostgreSQL, sequential boots of the shipped application, and schema-1 schedule rows written by SQL
 * BETWEEN boots — `{"pipeline": "<name>", "version": "current"}` at `payload_schema_version = 1`, exactly what the
 * previous version wrote.
 *
 * - **the binding fixture**: a schedule with a KEYWORD binding (`TODAY`), a LITERAL binding (a DATE value) and a
 *   keyword-shaped STRING literal resolves the same inputs at the same frozen `referenceAt` before the conversion
 *   (the shipped resolver over the stored schema-1 row — what the pointer-following executor bound) and after it (the
 *   resolver over the converted row); and a REAL run of the converted schedule (Run now, prepared by the worker)
 *   binds exactly what the schema-1 row resolves to at that run's own frozen `reference_at` —
 *   after the conversion and again after a rerun. The before/after payload diff is exactly the `version` value, and no
 *   other column moves but the revision. (This module compiles against `app` only, so the resolver is reached by reflection, as
 *   `SchedulerShutdownOrderE2eTest` reaches the scheduler's classes.)
 * - the pin is the version CURRENT names at the upgrade (v2 here, not v1); a DRAFT CURRENT converts to its exact pin;
 *   the schedule's QUEUED run is pinned in the same transaction, a terminal run keeps its history, a deleted schedule
 *   is not touched; one report line each and the summary;
 * - a rerun boot is a no-op even after CURRENT moved (the selector is schema 1: nothing is repinned);
 * - E.0: missing pipeline, no versions, no current → the boot is refused with `kind=schedule` lines and NOTHING is
 *   written by EITHER step (a convertible legacy API beside them stays version-less: every step's precondition is read
 *   before any step writes); the operator's fix lets the next boot convert the rest;
 * - the conversion racing a discard of the version it pins, both orders, under the real G0 guard (a raw connection
 *   holds the workspace row; both writers queue on it, observed with `pg_blocking_pids`, never timed).
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduleVersionConversionE2eTest {
    private val mapper = ObjectMapper()
    private var context: ConfigurableApplicationContext? = null
    private var port: Int = 0
    private var adminId: UUID = UUID(0, 0)
    private var adminJwt: String = ""
    private var bindingsPipeline: UUID = UUID(0, 0)
    private var bindingsSchedule: UUID = UUID(0, 0)
    private var payloadBefore: ObjectNode = mapper.createObjectNode()

    @AfterAll
    fun close() {
        context?.close()
    }

    @Test
    @Order(1)
    fun `boot one - the fixture pipeline and a schedule with keyword and literal bindings save at schema 2`() {
        context = boot()
        adminId = UUID.randomUUID()
        sql(
            "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)" +
                " VALUES ('$adminId', '$ADMIN_EMAIL', 'Conversion Admin', 'test', 'conversion-admin-sub', TRUE, TRUE)",
        )
        sql("INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Conversion E2E')")
        sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$WORKSPACE_ID', '$adminId', 'workspace_admin')")
        adminJwt = E2eSession.jwt(jwtSecret, adminId.toString(), ADMIN_EMAIL, WORKSPACE)

        bindingsPipeline = createAndRelease(BINDINGS_PIPELINE)
        // v2: a released copy, made CURRENT — so the conversion's pin is what CURRENT names, not the first version.
        copyVersion(bindingsPipeline, 2, "RELEASED")
        sql("UPDATE pipelines SET current_version = 2 WHERE id = '$bindingsPipeline'")

        val created =
            session()
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "name" to BINDINGS_SCHEDULE,
                            "payload" to mapOf("pipeline" to BINDINGS_PIPELINE, "version" to 1, "parameter_bindings" to BINDINGS),
                            "parameters" to mapOf("note" to "TODAY"),
                            "cron" to YEARLY,
                            "timezone" to ZONE.id,
                        ),
                    ),
                ).post("/api/v1/schedules")
        withClue(created.asString().take(DIAGNOSTIC_CHARS)) { created.statusCode shouldBe 201 }
        bindingsSchedule = UUID.fromString(created.jsonPath().getString("data.id"))
        scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$bindingsSchedule'") shouldBe 2
    }

    @Test
    @Order(2)
    fun `boot two converts every live schema-1 schedule in place - the version only, the same resolved inputs`(output: CapturedOutput) {
        close()
        // What the previous version wrote: the same schedule, its payload following CURRENT at schema 1.
        sql(
            "UPDATE schedules SET payload_schema_version = 1, payload_json = jsonb_set(payload_json, '{version}', '\"current\"') " +
                "WHERE id = '$bindingsSchedule'",
        )
        val terminal = legacyRun(bindingsSchedule, "succeeded", admitBy = "NOW() - INTERVAL '1 minute'")
        val draftPipeline = pipelineCopy(DRAFT_PIPELINE, current = 1, "DRAFT")
        val draftSchedule = legacySchedule("test/e2econv_draft_current", DRAFT_PIPELINE)
        // A run that was waiting at the upgrade (no task instance: it never starts here, its payload copy is the point).
        val queued = legacyRun(draftSchedule, "queued", admitBy = "NOW() + INTERVAL '1 day'")
        val deleted = legacySchedule("test/e2econv_deleted", "test/e2econv_gone", deleted = true)
        val before = row(bindingsSchedule)
        payloadBefore = payloadOf(bindingsSchedule)
        val resolvedBefore = resolveStored(payloadBefore, parametersOf(bindingsSchedule), REFERENCE_AT)
        withClue("the fixture resolves real values at the frozen reference: TODAY in the schedule's zone, the literals as written") {
            resolvedBefore shouldBe mapper.readTree("""{"note": "TODAY", "as_of_date": "$LOGICAL_DAY", "fixed_date": "$FIXED_DATE"}""")
        }

        context = boot()

        val after = row(bindingsSchedule)
        val payloadAfter = payloadOf(bindingsSchedule)
        withClue("the same inputs at the same frozen referenceAt, over the converted row") {
            resolveStored(payloadAfter, parametersOf(bindingsSchedule), REFERENCE_AT) shouldBe resolvedBefore
        }
        withClue("a real run of the converted schedule: the worker's prepare binds what the schema-1 row bound at the run's reference") {
            val run = preparedRun(bindingsSchedule)
            run.inputs shouldBe resolveStored(payloadBefore, parametersOf(bindingsSchedule), run.referenceAt)
            run.version shouldBe "2"
        }
        withClue("the payload diff is exactly the version value: \"current\" became the integer CURRENT named (2)") {
            payloadAfter["version"] shouldBe mapper.nodeFactory.numberNode(2)
            payloadAfter.deepCopy().apply { remove("version") } shouldBe payloadBefore.deepCopy().apply { remove("version") }
            payloadAfter.fieldNames().asSequence().toList() shouldBe payloadBefore.fieldNames().asSequence().toList()
        }
        withClue("no other column moved but the schema number and the revision (parameters, cron, zone, policy, updated_*)") {
            after["payload_schema_version"] shouldBe 2
            after["revision"] shouldBe (before["revision"] as Int) + 1
            after.minus(CONVERTED_COLUMNS) shouldBe before.minus(CONVERTED_COLUMNS)
        }
        withClue("the queued run's payload copy is pinned in the same transaction; the terminal run keeps its history") {
            runSchemaAndVersion(queued) shouldBe listOf(2, "1")
            runSchemaAndVersion(terminal) shouldBe listOf(1, "current")
        }
        withClue("a DRAFT CURRENT converts to its exact pin; a deleted schedule is not selected and refuses nothing") {
            payloadOf(draftSchedule)["version"].asInt() shouldBe 1
            scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$draftSchedule'") shouldBe 2
            scalar<Int>("SELECT current_version FROM pipelines WHERE id = '$draftPipeline'") shouldBe 1
            scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$deleted'") shouldBe 1
        }
        output.out shouldContain
            "event=scheduler.conversion.converted id=$bindingsSchedule name=$BINDINGS_SCHEDULE pipeline=$BINDINGS_PIPELINE " +
            "pipeline_version=2 status=RELEASED queued_runs=0"
        output.out shouldContain "pipeline=$DRAFT_PIPELINE pipeline_version=1 status=DRAFT queued_runs=1"
        output.out shouldContain "event=scheduler.conversion.summary converted=2 released=1 draft=1 queued_runs=1"
    }

    @Test
    @Order(3)
    fun `a rerun boot repins nothing - even after CURRENT moved, the pin, payload and revision are unchanged`(output: CapturedOutput) {
        // CURRENT moves back to v1: a conversion that re-selected converted rows would now repin the schedule to 1.
        sql("UPDATE pipelines SET current_version = 1 WHERE id = '$bindingsPipeline'")
        val before = rows(SNAPSHOT)
        close()

        context = boot()

        rows(SNAPSHOT) shouldBe before
        payloadOf(bindingsSchedule)["version"].asInt() shouldBe 2
        withClue("the rerun's run executes the exact pin (v2, not the moved CURRENT) and binds what the schema-1 row bound") {
            val run = preparedRun(bindingsSchedule)
            run.inputs shouldBe resolveStored(payloadBefore, parametersOf(bindingsSchedule), run.referenceAt)
            run.version shouldBe "2"
        }
        output.out shouldContain "event=scheduler.conversion.summary converted=0 released=0 draft=0 queued_runs=0"
        output.out shouldNotContain "event=scheduler.conversion.converted"
    }

    @Test
    @Order(4)
    fun `E0 - schedules with no pipeline, no versions or no current refuse the boot and NEITHER step writes`(output: CapturedOutput) {
        close()
        pipelineCopy("test/e2econv_no_versions", current = null)
        pipelineCopy("test/e2econv_no_current", current = null, "DISCARDED")
        val convertible = legacySchedule("test/e2econv_a_convertible", BINDINGS_PIPELINE)
        val missing = legacySchedule("test/e2econv_z_missing", "test/e2econv_missing")
        val noVersions = legacySchedule("test/e2econv_z_no_versions", "test/e2econv_no_versions")
        val noCurrent = legacySchedule("test/e2econv_z_no_current", "test/e2econv_no_current")
        legacyApi("/e2econv/v1/convertible", bindingsPipeline)
        val schemaOneBefore = scalar<Long>(SCHEMA_ONE_LIVE)

        val refused = shouldThrowAny { boot() }

        val messages = generateSequence(refused) { it.cause }.mapNotNull { it.message }.toList()
        withClue(messages) {
            messages.any { it.contains("The upgrade's conversion refused to start: 3 API(s) or schedule(s)") } shouldBe true
        }
        withClue("nothing written: the convertible schedule is still schema 1 and the legacy API beside it still version-less") {
            scalar<Long>(SCHEMA_ONE_LIVE) shouldBe schemaOneBefore
            scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$convertible'") shouldBe 1
            scalar<Long>(VERSIONLESS_APIS) shouldBe 1L
        }
        output.out shouldContain
            "event=scheduler.conversion.refused kind=schedule id=$missing name=test/e2econv_z_missing " +
            "pipeline=test/e2econv_missing reason=missing_pipeline"
        output.out shouldContain "id=$noVersions name=test/e2econv_z_no_versions pipeline=test/e2econv_no_versions reason=no_versions"
        output.out shouldContain "id=$noCurrent name=test/e2econv_z_no_current pipeline=test/e2econv_no_current reason=no_current"
        output.out shouldContain "event=scheduler.conversion.refused_summary refused=3"

        // The operator's fix on the previous version: delete the refused schedules. The next boot converts the rest.
        sql("UPDATE schedules SET deleted_at = NOW(), deleted_by = '$adminId' WHERE id IN ('$missing', '$noVersions', '$noCurrent')")
        context = boot()
        withClue("the convertible schedule pins what CURRENT names now (v1, moved by the rerun leg)") {
            payloadOf(convertible)["version"].asInt() shouldBe 1
        }
        scalar<Long>(VERSIONLESS_APIS) shouldBe 0L
    }

    @Test
    @Order(5)
    fun `conversion racing a discard - the discard first is refused as pinned, and the conversion pins the version it protected`() {
        val racing = pipelineCopy("test/e2econv_race_a", current = 2, "RELEASED", "RELEASED")
        val schedule = legacySchedule("test/e2econv_race_a", "test/e2econv_race_a")

        val (discard, booted) =
            holdingWorkspace { holder ->
                val discard = CompletableFuture.supplyAsync { discardVersion(racing, 2) }
                awaitWaiters(holder, 1)
                val booted = CompletableFuture.supplyAsync { boot() }
                awaitWaiters(holder, 2)
                discard to booted
            }

        withClue("a schema-1 schedule follows CURRENT, so the version it runs today is already protected") {
            assertPinnedRefusal(discard.get(2, TimeUnit.MINUTES), "test/e2econv_race_a")
        }
        booted.get(5, TimeUnit.MINUTES).close()
        payloadOf(schedule)["version"].asInt() shouldBe 2
        scalar<String>("SELECT status FROM pipeline_versions WHERE pipeline_id = '$racing' AND version = 2") shouldBe "RELEASED"
    }

    @Test
    @Order(6)
    fun `conversion racing a discard - the conversion first pins the version, and the discard is refused as pinned`() {
        val racing = pipelineCopy("test/e2econv_race_b", current = 2, "RELEASED", "RELEASED")
        val schedule = legacySchedule("test/e2econv_race_b", "test/e2econv_race_b")

        val (booted, discard) =
            holdingWorkspace { holder ->
                val booted = CompletableFuture.supplyAsync { boot() }
                awaitWaiters(holder, 1)
                val discard = CompletableFuture.supplyAsync { discardVersion(racing, 2) }
                awaitWaiters(holder, 2)
                booted to discard
            }

        booted.get(5, TimeUnit.MINUTES).close()
        withClue("the conversion committed the exact pin first; the discard reads it and refuses") {
            assertPinnedRefusal(discard.get(2, TimeUnit.MINUTES), "test/e2econv_race_b")
        }
        payloadOf(schedule)["version"].asInt() shouldBe 2
        scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$schedule'") shouldBe 2
        scalar<String>("SELECT status FROM pipeline_versions WHERE pipeline_id = '$racing' AND version = 2") shouldBe "RELEASED"
    }

    // ------------------------------------------------------------------ #786 round 7, R1: a queued run converts from ITS OWN copy

    @Test
    @Order(7)
    fun `R1 - a queued run whose copy names the OLD pipeline converts to that pipeline's own CURRENT, and a rerun repins nothing`(
        output: CapturedOutput,
    ) {
        // Two pipelines with their own bodies (created through REST), each with its own CURRENT: old at v2, new at v3.
        val old = createAndRelease(AB_OLD)
        copyVersion(old, 2, "RELEASED", from = old)
        sql("UPDATE pipelines SET current_version = 2 WHERE id = '$old'")
        val new = createAndRelease(AB_NEW)
        copyVersion(new, 2, "RELEASED", from = new)
        copyVersion(new, 3, "RELEASED", from = new)
        sql("UPDATE pipelines SET current_version = 3 WHERE id = '$new'")
        close()
        // What the previous version left: the schedule saved on OLD, a run queued from it, then an ordinary edit of the
        // schedule to NEW — the edit rewrites the schedule, never the run's frozen copy.
        val schedule = legacySchedule("test/e2econv_ab_edit", AB_OLD)
        val run = legacyRun(schedule, "queued", admitBy = "NOW() + INTERVAL '1 day'")
        sql(
            "UPDATE schedules SET payload_json = '{\"pipeline\": \"$AB_NEW\", \"version\": \"current\"}'," +
                " target_ref = 'pipeline:$AB_NEW', revision = revision + 1 WHERE id = '$schedule'",
        )
        val runBefore = runRow(run)
        withClue("the two targets are distinct versions of distinct bodies") {
            bodyHash(old, 2) shouldNotBe bodyHash(new, 3)
        }

        context = boot()

        payloadOf(schedule) shouldBe mapper.readTree("""{"pipeline": "$AB_NEW", "version": 3}""")
        withClue("the run keeps its own pipeline and pins ITS CURRENT (2), never the schedule's (3)") {
            runPayloadOf(run) shouldBe mapper.readTree("""{"pipeline": "$AB_OLD", "version": 2}""")
            runSchemaAndVersion(run) shouldBe listOf(2, "2")
        }
        withClue("no other column of the run moved: parameters, frozen reference, window, actor, state") {
            runRow(run).minus(RUN_CONVERTED_COLUMNS) shouldBe runBefore.minus(RUN_CONVERTED_COLUMNS)
        }
        output.out shouldContain "name=test/e2econv_ab_edit pipeline=$AB_NEW pipeline_version=3 status=RELEASED queued_runs=1"
        output.out shouldContain
            "event=scheduler.conversion.converted_run id=$run schedule=$schedule pipeline=$AB_OLD pipeline_version=2 status=RELEASED"

        // A rerun after the old pipeline's CURRENT moved repins nothing.
        sql("UPDATE pipelines SET current_version = 1 WHERE id = '$old'")
        val snapshot = rows(SNAPSHOT)
        close()
        context = boot()
        rows(SNAPSHOT) shouldBe snapshot
        runSchemaAndVersion(run) shouldBe listOf(2, "2")
        output.out shouldContain "event=scheduler.conversion.summary converted=0 released=0 draft=0 queued_runs=0"
    }

    @Test
    @Order(8)
    fun `R1 E0 - a queued run whose own pipeline is missing or has no CURRENT refuses the boot, named kind=run, and nothing is written`(
        output: CapturedOutput,
    ) {
        close()
        val noCurrent = pipelineCopy(RUN_NO_CURRENT, current = null, "DISCARDED")
        val missingSchedule = legacySchedule("test/e2econv_run_missing", AB_NEW)
        val missingRun = legacyRun(missingSchedule, "queued", admitBy = "NOW() + INTERVAL '1 day'", payload = legacyPayload(RUN_GONE))
        val noCurrentSchedule = legacySchedule("test/e2econv_run_no_current", AB_NEW)
        val noCurrentRun =
            legacyRun(noCurrentSchedule, "queued", admitBy = "NOW() + INTERVAL '1 day'", payload = legacyPayload(RUN_NO_CURRENT))
        val before = rows(SNAPSHOT)

        val refused = shouldThrowAny { boot() }

        val messages = generateSequence(refused) { it.cause }.mapNotNull { it.message }.toList()
        withClue(messages) { messages.any { it.contains("The upgrade's conversion refused to start: 2 ") } shouldBe true }
        withClue("nothing written: both schedules (whose own pipeline is fine) and both runs are as they were") {
            rows(SNAPSHOT) shouldBe before
        }
        output.out shouldContain
            "event=scheduler.conversion.refused kind=run id=$missingRun schedule=$missingSchedule name=test/e2econv_run_missing " +
            "pipeline=$RUN_GONE reason=missing_pipeline"
        output.out shouldContain
            "event=scheduler.conversion.refused kind=run id=$noCurrentRun schedule=$noCurrentSchedule name=test/e2econv_run_no_current " +
            "pipeline=$RUN_NO_CURRENT reason=no_current"
        output.out shouldNotContain "kind=schedule id=$missingSchedule"
        output.out shouldNotContain "kind=schedule id=$noCurrentSchedule"
        output.out shouldContain "event=scheduler.conversion.refused_summary refused=2"

        // The operator's fix on the previous version: release the run's pipeline, or delete the schedule.
        sql(
            "UPDATE pipeline_versions SET status = 'RELEASED', discarded_by = NULL, discarded_at = NULL" +
                " WHERE pipeline_id = '$noCurrent' AND version = 1",
        )
        sql("UPDATE pipelines SET current_version = 1 WHERE id = '$noCurrent'")
        sql("UPDATE schedules SET deleted_at = NOW(), deleted_by = '$adminId' WHERE id = '$missingSchedule'")
        context = boot()
        payloadOf(noCurrentSchedule)["version"].asInt() shouldBe 3
        runPayloadOf(noCurrentRun) shouldBe mapper.readTree("""{"pipeline": "$RUN_NO_CURRENT", "version": 1}""")
        withClue("the deleted schedule's run is not selected: the worker skips it as schedule_deleted") {
            runSchemaAndVersion(missingRun) shouldBe listOf(1, "current")
        }
    }

    @Test
    @Order(9)
    fun `R1 - a run target that loses its CURRENT under the guard rolls back the schedule's own write with it`(output: CapturedOutput) {
        close()
        val target = pipelineCopy(RUN_RACE, current = 1, "RELEASED")
        val schedule = legacySchedule("test/e2econv_run_race", AB_NEW)
        val run = legacyRun(schedule, "queued", admitBy = "NOW() + INTERVAL '1 day'", payload = legacyPayload(RUN_RACE))
        val before = rows(SNAPSHOT)

        val booted =
            holdingWorkspace { holder ->
                val booted = CompletableFuture.supplyAsync { boot() }
                // The precondition (unguarded reads) passed; the conversion now waits for the workspace guard.
                awaitWaiters(holder, 1)
                // Meanwhile the run's pipeline loses its CURRENT: the guarded re-read, after the schedule's write, refuses.
                sql("UPDATE pipelines SET current_version = NULL WHERE id = '$target'")
                booted
            }

        shouldThrowAny { booted.get(5, TimeUnit.MINUTES) }
        withClue("the schedule was written first inside the transaction; the run's refusal rolled both back") {
            rows(SNAPSHOT) shouldBe before
            scalar<Int>("SELECT payload_schema_version FROM schedules WHERE id = '$schedule'") shouldBe 1
        }
        output.out shouldContain
            "event=scheduler.conversion.refused kind=run id=$run schedule=$schedule name=test/e2econv_run_race " +
            "pipeline=$RUN_RACE reason=no_current"

        // CURRENT back: the next boot converts the schedule and its run, each to its own pipeline's version.
        sql("UPDATE pipelines SET current_version = 1 WHERE id = '$target'")
        context = boot()
        payloadOf(schedule)["version"].asInt() shouldBe 3
        runPayloadOf(run) shouldBe mapper.readTree("""{"pipeline": "$RUN_RACE", "version": 1}""")
    }

    // ------------------------------------------------------------------ the binding witnesses

    /**
     * The BEFORE side: the shipped `PipelineInputResolver` over the stored row's bindings and literals at the frozen
     * reference — exactly what the pointer-following executor bound. Reflection: this module compiles against `app` only.
     */
    private fun resolveStored(
        payload: JsonNode,
        parameters: JsonNode,
        at: Instant,
    ): JsonNode {
        val bindings = payload.path(BINDINGS_FIELD).properties().associate { it.key to it.value }
        val literals = parameters.properties().associate { it.key to it.value }
        val referenceType = Class.forName("co.datapipelines.executor.ExecutionReference")
        val reference = referenceType.getConstructor(Instant::class.java, ZoneId::class.java).newInstance(at, ZONE)
        val resolverType = Class.forName("co.datapipelines.application.pipelines.PipelineInputResolver")
        val result =
            resolverType
                .getMethod("resolve", Map::class.java, Map::class.java, Map::class.java, referenceType)
                .invoke(resolverType.getConstructor().newInstance(), DECLARED, literals, bindings, reference)
        withClue("the resolver refused the fixture: $result") { result.javaClass.simpleName shouldBe "Resolved" }
        return mapper.valueToTree(result.javaClass.getMethod("getParameters").invoke(result))
    }

    /** A run the worker prepared: its frozen reference, the literal map it bound and the version it pinned. */
    private data class PreparedRun(
        val referenceAt: Instant,
        val inputs: JsonNode,
        val version: String,
    )

    /**
     * The AFTER side: a real run of [schedule] — Run now records it with its frozen `reference_at` (the record instant,
     * the schedule's zone), the worker prepares it — and what `prepare` bound at that reference. Returns once the run
     * ended, so the next leg's run has the schedule's one active slot.
     */
    private fun preparedRun(schedule: UUID): PreparedRun {
        val accepted = session().post("/api/v1/schedules/$schedule/run")
        withClue(accepted.asString().take(DIAGNOSTIC_CHARS)) { accepted.statusCode shouldBe 202 }
        val id = accepted.jsonPath().getString("data.id")
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
        while (System.nanoTime() < deadline) {
            val run = rows("SELECT state, reason, reference_at, prepared_json::text AS p FROM schedule_runs WHERE id = '$id'").single()
            if (run["state"] as String !in ACTIVE_STATES) {
                val prepared = checkNotNull(run["p"] as String?) { "run $id ended unprepared: $run" }
                val snapshot = mapper.readTree(prepared)
                return PreparedRun(
                    (run["reference_at"] as java.sql.Timestamp).toInstant(),
                    snapshot["resolved_parameters"],
                    snapshot["version"].asText(),
                )
            }
            Thread.sleep(POLL_MILLIS)
        }
        error("run $id of $schedule did not end within a minute")
    }

    // ------------------------------------------------------------------ REST

    private fun createAndRelease(name: String): UUID {
        val created = session().contentType(ContentType.JSON).body(pipelineBody(name)).post("/api/v1/pipelines")
        check(created.statusCode == 201) { "pipeline create $name → ${created.statusCode}: ${created.asString().take(DIAGNOSTIC_CHARS)}" }
        val id = created.jsonPath().getString("data.id")
        session()
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return UUID.fromString(id)
    }

    private fun discardVersion(
        pipeline: UUID,
        version: Int,
    ): Response = session().post("/api/v1/pipelines/$pipeline/versions/$version/discard")

    private fun assertPinnedRefusal(
        discard: Response,
        schedule: String,
    ) {
        withClue(discard.asString().take(DIAGNOSTIC_CHARS)) {
            discard.statusCode shouldBe 409
            discard.jsonPath().getString("error.code") shouldBe "pipeline.version.pinned"
            discard.jsonPath().getList<String>("error.details.referencing_schedules.schedule") shouldBe listOf(schedule)
        }
    }

    private fun session() = given().port(port).asSession(adminJwt)

    // ------------------------------------------------------------------ boot + fixtures

    private fun boot(): ConfigurableApplicationContext {
        val args = properties().map { (k, v) -> "--$k=$v" }.toTypedArray()
        val started = SpringApplicationBuilder(DatapipelinesApplication::class.java).web(WebApplicationType.SERVLET).run(*args)
        if (context?.isActive != true) {
            port = checkNotNull(started.environment.getProperty("local.server.port", Int::class.java)) { "no server port" }
        }
        return started
    }

    /** Holds the workspace row in an open transaction (a writer in flight), runs [block], then commits. */
    private fun <T> holdingWorkspace(block: (Int) -> T): T =
        connection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute("SELECT 1 FROM workspaces WHERE id = '$WORKSPACE_ID' FOR NO KEY UPDATE") }
            val pid =
                holder.createStatement().use { s ->
                    s.executeQuery("SELECT pg_backend_pid()").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            val result = block(pid)
            holder.commit()
            result
        }

    /** Waits until [count] backends are queued behind [holderPid]'s lock (directly or behind an earlier waiter). */
    private fun awaitWaiters(
        holderPid: Int,
        count: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (System.nanoTime() < deadline) {
            val waiting =
                scalar<Long>("SELECT COUNT(*) FROM pg_stat_activity WHERE pid <> $holderPid AND cardinality(pg_blocking_pids(pid)) > 0")
            if (waiting >= count) return
            Thread.sleep(POLL_MILLIS)
        }
        error("expected $count waiter(s) behind backend $holderPid")
    }

    /** Adds version [version] of [pipeline] as a copy of [from]'s v1 body (the fixture pipeline's by default) with [status]. */
    private fun copyVersion(
        pipeline: UUID,
        version: Int,
        status: String,
        from: UUID = bindingsPipeline,
    ) = sql(
        "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by," +
            " released_at, discarded_by, discarded_at)" +
            " SELECT '$pipeline', $version, body_json, body_hash, '$status', created_by," +
            " CASE WHEN '$status' <> 'DRAFT' THEN created_by END, CASE WHEN '$status' <> 'DRAFT' THEN NOW() END," +
            " CASE WHEN '$status' = 'DISCARDED' THEN created_by END, CASE WHEN '$status' = 'DISCARDED' THEN NOW() END" +
            " FROM pipeline_versions WHERE pipeline_id = '$from' AND version = 1",
    )

    /** A pipeline whose versions 1..n are copies of the fixture pipeline's v1 body with [statuses]. */
    private fun pipelineCopy(
        name: String,
        current: Int?,
        vararg statuses: String,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES ('$id', '$name', 'E2E', '$adminId', ${current ?: "NULL"}, '$WORKSPACE_ID')",
        )
        statuses.forEachIndexed { index, status -> copyVersion(id, index + 1, status) }
        return id
    }

    /** A schedule the previous version wrote: schema 1, following CURRENT, never due during the suite. */
    private fun legacySchedule(
        name: String,
        pipeline: String,
        deleted: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json, target_ref, cron," +
                " timezone, created_by, updated_by, deleted_at, deleted_by)" +
                " VALUES ('$id', '$WORKSPACE_ID', '$name', 'pipeline', 1, '{\"pipeline\": \"$pipeline\", \"version\": \"current\"}'," +
                " 'pipeline:$pipeline', '$YEARLY', 'UTC', '$adminId', '$adminId'," +
                (if (deleted) " NOW(), '$adminId')" else " NULL, NULL)"),
        )
        return id
    }

    /**
     * A manual run of [schedule] in [state], admitted until [admitBy] (SQL); its payload copy is the schedule's stored one,
     * or [payload] — the copy a run queued before an edit of the schedule's target still carries.
     */
    private fun legacyRun(
        schedule: UUID,
        state: String,
        admitBy: String,
        payload: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val copy = payload?.let { "CAST('$it' AS jsonb)" } ?: "s.payload_json"
        sql(
            "INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, reference_at, reference_timezone, admit_by," +
                " executor_id, payload_schema_version, payload_json, parameters_json, actor_user_id, state)" +
                " SELECT '$id', s.id, s.workspace_id, 'manual', NOW(), s.timezone, $admitBy, s.executor_id," +
                " s.payload_schema_version, $copy, s.parameters_json, u.id, '$state'" +
                " FROM schedules s, users u WHERE s.id = '$schedule' AND u.kind = 'system'",
        )
        return id
    }

    /** An API the previous version wrote: the entity and its legacy columns, no version row. */
    private fun legacyApi(
        path: String,
        pipeline: UUID,
    ) = sql(
        "INSERT INTO published_endpoints" +
            " (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by)" +
            " VALUES ('${UUID.randomUUID()}', '$WORKSPACE_ID', '$path', '$pipeline', 30, '', true, '$adminId')",
    )

    private fun payloadOf(schedule: UUID): ObjectNode =
        mapper.readTree(scalar<String>("SELECT payload_json::text FROM schedules WHERE id = '$schedule'")) as ObjectNode

    private fun parametersOf(schedule: UUID): JsonNode =
        mapper.readTree(scalar<String>("SELECT parameters_json::text FROM schedules WHERE id = '$schedule'"))

    /** A schema-1 payload naming [pipeline], as the previous version wrote it. */
    private fun legacyPayload(pipeline: String) = """{"pipeline": "$pipeline", "version": "current"}"""

    private fun runPayloadOf(run: UUID): JsonNode =
        mapper.readTree(scalar<String>("SELECT payload_json::text FROM schedule_runs WHERE id = '$run'"))

    /** Every column of the run row, JSON columns as text. */
    private fun runRow(run: UUID): Map<String, Any?> =
        rows(
            "SELECT *, payload_json::text AS payload_text, parameters_json::text AS parameters_text FROM schedule_runs WHERE id = '$run'",
        ).single().mapValues { (_, v) -> if (v?.javaClass?.simpleName == "PGobject") v.toString() else v }

    private fun bodyHash(
        pipeline: UUID,
        version: Int,
    ): String = scalar("SELECT body_hash FROM pipeline_versions WHERE pipeline_id = '$pipeline' AND version = $version")

    private fun runSchemaAndVersion(run: UUID): List<Any?> =
        rows("SELECT payload_schema_version, payload_json ->> 'version' AS v FROM schedule_runs WHERE id = '$run'").single().values.toList()

    /** Every column of the schedule row, JSON columns as text. */
    private fun row(schedule: UUID): Map<String, Any?> =
        rows(
            "SELECT *, payload_json::text AS payload_text, parameters_json::text AS parameters_text FROM schedules WHERE id = '$schedule'",
        ).single().mapValues { (_, v) -> if (v?.javaClass?.simpleName == "PGobject") v.toString() else v }

    /** A SQL array (the notification columns) as a list, read while its connection is open, so rows compare by value. */
    private fun valueOf(value: Any?): Any? = if (value is java.sql.Array) (value.array as Array<*>).toList() else value

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun sql(statement: String) = connection().use { c -> c.createStatement().use { it.execute(statement) } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(query: String): T = rows(query).single().values.first() as T

    private fun rows(query: String): List<Map<String, Any?>> =
        connection().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(query).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add((1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to valueOf(rs.getObject(it)) })
                        }
                    }
                }
            }
        }

    /** The fixture: two DATE parameters (one per binding source) and a STRING whose literal value is keyword-shaped. */
    private fun pipelineBody(name: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Conversion bindings",
                "description" to "Reads a keyword-bound and a literal-bound date",
                "parameters" to
                    mapOf(
                        "as_of_date" to mapOf("type" to "DATE", "required" to true, "description" to "Bound to TODAY."),
                        "fixed_date" to mapOf("type" to "DATE", "required" to true, "description" to "Bound to a literal."),
                        "note" to mapOf("type" to "STRING", "description" to "A literal, keyword-shaped text."),
                    ),
                "nodes" to
                    listOf(
                        calculator("fq_today", "today_quarter", "as_of_date"),
                        calculator("fq_fixed", "fixed_quarter", "fixed_date"),
                    ),
            ),
        )

    private fun calculator(
        id: String,
        key: String,
        parameter: String,
    ) = mapOf(
        "id" to id,
        "type" to "CALCULATOR",
        "kind" to "fiscal_quarter",
        "context_key" to key,
        "inputs" to mapOf("date" to "\$$parameter", "fiscal_start" to "01-01"),
    )

    companion object {
        private const val ADMIN_EMAIL = "conversion-admin@e2e.test"
        private const val WORKSPACE = "conv-e2e"
        private val WORKSPACE_ID: UUID = UUID.randomUUID()
        private const val BINDINGS_PIPELINE = "test/e2econv_bindings"

        /** R1: the schedule's old and new targets, and the queued runs' own targets. */
        private const val AB_OLD = "test/e2econv_ab_old"
        private const val AB_NEW = "test/e2econv_ab_new"
        private const val RUN_GONE = "test/e2econv_run_gone"
        private const val RUN_NO_CURRENT = "test/e2econv_run_no_current"
        private const val RUN_RACE = "test/e2econv_run_race"

        /** The columns the conversion writes on a run; every other column must be what it was. */
        private val RUN_CONVERTED_COLUMNS = setOf("payload_json", "payload_text", "payload_schema_version")
        private const val BINDINGS_SCHEDULE = "test/e2econv_bindings"
        private const val DRAFT_PIPELINE = "test/e2econv_draft_current"
        private const val YEARLY = "0 3 1 1 *"
        private const val POLL_MILLIS = 50L
        private const val DIAGNOSTIC_CHARS = 1_000
        private const val FIXED_DATE = "2026-01-15"
        private const val BINDINGS_FIELD = "parameter_bindings"
        private val ACTIVE_STATES = setOf("queued", "starting", "running")

        /** 04:30 UTC on 8 March is 23:30 on 7 March in New York: TODAY is the zone's day, not the UTC one. */
        private val REFERENCE_AT: Instant = Instant.parse("2026-03-08T04:30:00Z")
        private val ZONE: ZoneId = ZoneId.of("America/New_York")
        private const val LOGICAL_DAY = "2026-03-07"
        private val DECLARED = mapOf("as_of_date" to "DATE", "fixed_date" to "DATE", "note" to "STRING")
        private val BINDINGS =
            mapOf(
                "as_of_date" to mapOf("source" to "keyword", "name" to "TODAY"),
                "fixed_date" to mapOf("source" to "literal", "value" to FIXED_DATE),
            )

        /** The columns the conversion writes; every other column of the row must be byte-for-byte what it was. */
        private val CONVERTED_COLUMNS = setOf("payload_json", "payload_text", "payload_schema_version", "revision")
        private const val SCHEMA_ONE_LIVE =
            "SELECT COUNT(*) FROM schedules WHERE payload_schema_version = 1 AND deleted_at IS NULL"
        private const val VERSIONLESS_APIS =
            "SELECT COUNT(*) FROM published_endpoints e WHERE NOT EXISTS " +
                "(SELECT 1 FROM published_endpoint_versions v WHERE v.endpoint_id = e.id)"
        private const val SNAPSHOT =
            "SELECT s.id, s.revision, s.payload_schema_version, s.payload_json::text, s.updated_at, r.id AS run," +
                " r.payload_schema_version AS run_schema, r.payload_json::text AS run_payload" +
                " FROM schedules s LEFT JOIN schedule_runs r ON r.schedule_id = s.id ORDER BY s.id, r.id"

        private val random = SecureRandom()
        private val jwtSecret = randomSecret()
        private val encryptionKey = randomSecret()

        private fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(32).also { random.nextBytes(it) })

        @Container
        @JvmStatic
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("datapipelines")
                .withUsername("datapipelines")
                .withPassword("datapipelines")

        private fun properties(): Map<String, String> =
            mapOf(
                "server.port" to "0",
                "management.server.port" to "0",
                "spring.datasource.url" to postgres.jdbcUrl,
                "spring.datasource.username" to postgres.username,
                "spring.datasource.password" to postgres.password,
                "datapipelines.redis.password" to "",
                "datapipelines.redis.host" to SharedE2e.redis.host,
                "datapipelines.redis.port" to SharedE2e.redisPort.toString(),
                "datapipelines.jwt.secret" to jwtSecret,
                "datapipelines.db.encryption-key" to encryptionKey,
                "datapipelines.auth.local.enabled" to "true",
                "datapipelines.auth.base-url" to "http://localhost:8080",
                // The binding witness is a REAL run: this context's worker runs (the module default is API mode).
                "datapipelines.scheduler.enabled" to "true",
                "datapipelines.scheduler.tick-interval-seconds" to "1",
                "datapipelines.scheduler.polling-interval-seconds" to "1",
                // The race's two writers queue behind a held lock across a whole context start; the production bound (5 s)
                // would refuse the first waiter busy before the second arrives.
                "datapipelines.release.dependencies.lock-wait-seconds" to "60",
            )
    }
}
