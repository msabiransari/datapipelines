package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.ClassUtils
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * #895 — the upgrade's in-place conversion has ONE rollback boundary: every API (#786 §E), schedule and queued run
 * (§D.5) of one startup converts in one transaction that the startup runner (`ConversionStartup`) owns, under one G0
 * acquisition order — the union of every workspace either family writes in, then the endpoint registry key, then the
 * entity rows. [ScheduleVersionConversionE2eTest]'s shape: its own PostgreSQL, sequential boots of the shipped
 * application, legacy rows written by SQL between boots exactly as the previous version wrote them.
 *
 * The fixture: two version-less APIs in two workspaces (WA, WB — one DRAFT pin, one with a key binding), two schema-1
 * schedules (one per workspace; WA's converts first) with a queued run each — WB's run's frozen copy names ANOTHER
 * pipeline than its schedule. Every failure leg compares every candidate row — the whole row, every table — read on
 * new connections against its pre-start snapshot.
 *
 * - B.1 a storage failure on the LAST write (WB's queued run) → the start refuses, nothing converted;
 * - B.2 a schedule target losing its CURRENT while the start waits for the guard (the SECOND family) → the existing
 *   refusal line, nothing converted;
 * - B.3 no injection → everything converts, fields and the key binding preserved; a rerun converts nothing;
 * - B.4 the acquisition order, observed on the database: while the start waits for the registry key it already holds
 *   every union workspace; while it waits for the last workspace it holds no registry key; a contending discard in
 *   one workspace queues behind it and is answered (refused as pinned), never a deadlock;
 * - B.6 the conflict retry at its new owner: a CAS loss in the second family replays both families from fresh rows
 *   (one rollback, one commit, the first API written twice); a candidate in a workspace the union did not cover
 *   re-plans the union; conflicts on every attempt refuse after exactly three attempts with nothing converted;
 * - B.7 the commit-time effects — converted lines, success summaries and the registry invalidation — surface exactly
 *   once after the joint commit, and never for an attempt that rolled back.
 *
 * Observation without compiling against the steps (this module sees `app` only): a [BeanPostProcessor] installed
 * per boot wraps the runner's two steps, its lock port and the registry's invalidation publisher in JDK proxies (by
 * interface NAME) that record each call and each transaction's outcome on one timeline ([Recorder]). Counts across a
 * rollback come from fixture SEQUENCES advanced by fixture triggers (a sequence is not rolled back).
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Suppress("LargeClass") // the brief's ONE real-PostgreSQL class for #895 B.1-B.7; a split would boot its fixtures twice
class ConversionRollbackBoundaryE2eTest {
    private val mapper = ObjectMapper()
    private var adminJwt: String = ""
    private var apiPipeline: UUID = UUID(0, 0)

    @Test
    @Order(1)
    fun `boot one - the fixture workspaces and pipelines`() {
        val context = boot(Recorder())
        try {
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin)" +
                    " VALUES ('$ADMIN_ID', '$ADMIN_EMAIL', 'Rollback Admin', 'test', 'rollback-admin-sub', TRUE, TRUE)",
            )
            listOf(WA to WA_NAME, WB to WB_NAME).forEach { (id, name) ->
                sql("INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$id', '$name', 'RB', '$ADMIN_ID')")
                sql("INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('$id', '$ADMIN_ID', 'workspace_admin')")
            }
            adminJwt = E2eSession.jwt(jwtSecret, ADMIN_ID.toString(), ADMIN_EMAIL, WA_NAME)
            // A real body (B.4's discard goes through REST): v1 created and released, v2 its released copy, CURRENT = 2.
            apiPipeline = createAndRelease(context, API_A_PIPELINE)
            copyVersion(apiPipeline, 2, "RELEASED")
            sql("UPDATE pipelines SET current_version = 2 WHERE id = '$apiPipeline'")
            pipeline(WB, API_B_PIPELINE, current = 1, "DRAFT")
            pipeline(WA, SCHED_PIPELINE, current = 1, "RELEASED")
            pipeline(WB, SCHED_PIPELINE, current = 2, "RELEASED", "RELEASED")
            pipeline(WB, OTHER_PIPELINE, current = 1, "RELEASED")
            // The key whose binding over WA's API must survive the conversion untouched.
            sql(
                "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin, kind) VALUES " +
                    "('$KEY_IDENTITY', 'dpk_rb895key@keys.invalid', 'rb key', 'key', '$KEY_ID', TRUE, FALSE, 'service')",
            )
            sql(
                "INSERT INTO api_keys (id, user_id, created_by, name, key_hash, workspace_id, kind, role)" +
                    " VALUES ('$KEY_ID', '$KEY_IDENTITY', '$ADMIN_ID', 'rb key', 'not-a-hash', '$WA', 'mcp', 'workspace_admin')",
            )
        } finally {
            context.close()
        }
    }

    @Test
    @Order(2)
    fun `B1 - a storage failure on the LAST write refuses the start and every candidate row is as it was`(output: CapturedOutput) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        val before = snapshot()
        // The last write of the start: WB's schedule's queued run (WA's schedule, then WB's, then each one's runs).
        sql(
            "CREATE FUNCTION rb_fail_last() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF NEW.id = ''${fixture.runZ}''::uuid AND NEW.payload_schema_version = 2 THEN " +
                "RAISE EXCEPTION ''$INJECTED_FAILURE''; END IF; RETURN NEW; END'",
        )
        sql("CREATE TRIGGER rb_fail_last BEFORE UPDATE ON schedule_runs FOR EACH ROW EXECUTE FUNCTION rb_fail_last()")
        try {
            val mark = output.out.length
            val refused = shouldThrowAny { boot(recorder) }

            generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString("\n") shouldContain INJECTED_FAILURE
            assertUnchanged(before)
            assertNoSuccessEffects(output.out.substring(mark), recorder)
        } finally {
            sql("DROP TRIGGER rb_fail_last ON schedule_runs")
            sql("DROP FUNCTION rb_fail_last()")
            clearFixture()
        }
    }

    @Test
    @Order(3)
    fun `B2 - a schedule target that loses its CURRENT under the guard refuses the start and nothing is converted`(
        output: CapturedOutput,
    ) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        val before = snapshot()
        val mark = output.out.length
        try {
            val booted =
                holding(LOCK_WB) { holder ->
                    val booted = CompletableFuture.supplyAsync { boot(recorder) }
                    // The precondition (unguarded reads) passed; the start now waits for WB's guard row.
                    awaitWaiter(holder)
                    // Meanwhile WB's schedule target loses its CURRENT: the guarded re-read of the SECOND family refuses.
                    sql("UPDATE pipelines SET current_version = NULL WHERE workspace_id = '$WB' AND name = '$SCHED_PIPELINE'")
                    booted
                }

            shouldThrowAny { booted.get(5, TimeUnit.MINUTES) }
            assertUnchanged(before)
            val out = output.out.substring(mark)
            out shouldContain
                "event=scheduler.conversion.refused kind=schedule id=${fixture.scheduleZ} name=$SCHEDULE_Z " +
                "pipeline=$SCHED_PIPELINE reason=no_current"
            out shouldContain "event=scheduler.conversion.refused_summary refused=1"
            assertNoSuccessEffects(out, recorder)
        } finally {
            sql("UPDATE pipelines SET current_version = 2 WHERE workspace_id = '$WB' AND name = '$SCHED_PIPELINE'")
            clearFixture()
        }
    }

    @Test
    @Order(4)
    fun `B1 - a storage failure on the LAST API write, after the other family's and the first API's writes, rolls back everything`(
        output: CapturedOutput,
    ) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        val before = snapshot()
        // WB's API is the last API write (path order); the base ran the schedule family first and committed it whole.
        sql(
            "CREATE FUNCTION rb_fail_api() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF NEW.endpoint_id = ''${fixture.apiB}''::uuid " +
                "THEN RAISE EXCEPTION ''$INJECTED_FAILURE''; END IF; RETURN NEW; END'",
        )
        sql("CREATE TRIGGER rb_fail_api BEFORE INSERT ON published_endpoint_versions FOR EACH ROW EXECUTE FUNCTION rb_fail_api()")
        try {
            val mark = output.out.length
            val refused = shouldThrowAny { boot(recorder) }

            generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString("\n") shouldContain INJECTED_FAILURE
            assertUnchanged(before)
            assertNoSuccessEffects(output.out.substring(mark), recorder)
        } finally {
            sql("DROP TRIGGER rb_fail_api ON published_endpoint_versions")
            sql("DROP FUNCTION rb_fail_api()")
            clearFixture()
        }
    }

    @Test
    @Order(5)
    fun `B2 - an API target that loses its CURRENT under the guard, after earlier writes, rolls back everything`(output: CapturedOutput) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        val before = snapshot()
        val mark = output.out.length
        try {
            val booted =
                holding(LOCK_WB) { holder ->
                    val booted = CompletableFuture.supplyAsync { boot(recorder) }
                    awaitWaiter(holder)
                    // WB's API's pipeline loses its CURRENT: its locked re-read refuses after WA's API was written.
                    sql("UPDATE pipelines SET current_version = NULL WHERE workspace_id = '$WB' AND name = '$API_B_PIPELINE'")
                    booted
                }

            shouldThrowAny { booted.get(5, TimeUnit.MINUTES) }
            assertUnchanged(before)
            val out = output.out.substring(mark)
            out shouldContain
                "event=endpoint.conversion.refused kind=api id=${fixture.apiB} path=/rb/b/one pipeline=$API_B_PIPELINE reason=no_current"
            out shouldContain "event=endpoint.conversion.refused_summary refused=1"
            assertNoSuccessEffects(out, recorder)
        } finally {
            sql("UPDATE pipelines SET current_version = 1 WHERE workspace_id = '$WB' AND name = '$API_B_PIPELINE'")
            clearFixture()
        }
    }

    @Test
    @Order(6)
    fun `B3 - with no injection every candidate converts, fields and the key binding kept, and a rerun converts nothing`(
        output: CapturedOutput,
    ) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        val before = snapshot()
        try {
            val mark = output.out.length
            boot(recorder).close()

            version(fixture.apiA) shouldBe listOf(1, "RELEASED", API_A_PIPELINE, 2, 45, "A one")
            version(fixture.apiB) shouldBe listOf(1, "DRAFT", API_B_PIPELINE, 1, 30, "")
            withClue("the entity rows: only the pointer moved; enabled state, legacy columns and the key binding untouched") {
                endpoint(fixture.apiA).minus("current_version") shouldBe before.endpoint(fixture.apiA).minus("current_version")
                endpoint(fixture.apiB).minus("current_version") shouldBe before.endpoint(fixture.apiB).minus("current_version")
                rows(BINDINGS) shouldBe before.getValue("bindings")
            }
            payloadOf("schedules", fixture.scheduleA) shouldBe mapper.readTree("""{"pipeline": "$SCHED_PIPELINE", "version": 1}""")
            payloadOf("schedules", fixture.scheduleZ) shouldBe mapper.readTree("""{"pipeline": "$SCHED_PIPELINE", "version": 2}""")
            payloadOf("schedule_runs", fixture.runA) shouldBe mapper.readTree("""{"pipeline": "$SCHED_PIPELINE", "version": 1}""")
            withClue("WB's run converts from its OWN copy: the other pipeline's CURRENT, never its schedule's") {
                payloadOf("schedule_runs", fixture.runZ) shouldBe mapper.readTree("""{"pipeline": "$OTHER_PIPELINE", "version": 1}""")
            }
            val out = output.out.substring(mark)
            assertOneSuccessSet(out, recorder, apis = 2, schedules = 2, runs = 2)
            withClue("the runner's declared family order: the APIs, then the schedules and their queued runs") {
                recorder.calls("").filter { it.endsWith(".plan") } shouldContainExactly
                    listOf("endpointStartupConversion.plan", "scheduleStartupConversion.plan")
            }

            // The rerun: the selectors are legacy-only, so nothing is selected, written or invalidated.
            val converted = snapshot()
            val rerun = Recorder()
            val rerunMark = output.out.length
            boot(rerun).close()

            snapshot() shouldBe converted
            val rerunOut = output.out.substring(rerunMark)
            rerunOut shouldContain "event=endpoint.conversion.summary converted=0 released=0 draft=0"
            rerunOut shouldContain "event=scheduler.conversion.summary converted=0 released=0 draft=0 queued_runs=0"
            lines(rerunOut, "event=endpoint.conversion.converted").shouldBeEmpty()
            lines(rerunOut, "event=scheduler.conversion.converted").shouldBeEmpty()
            rerun.count("invalidate") shouldBe 0
        } finally {
            clearFixture()
        }
    }

    @Test
    @Order(7)
    fun `B4 - every union workspace is held before the registry key, and a contending discard queues and is answered`() {
        val serving = boot(Recorder())
        try {
            val fixture = legacyFixture()
            val recorder = Recorder()
            val (booted, discard) =
                holding(LOCK_REGISTRY) { registryHolder ->
                    val lastWorkspace = connection()
                    lastWorkspace.autoCommit = false
                    lastWorkspace.createStatement().use { it.execute(LOCK_WB) }
                    val booted = CompletableFuture.supplyAsync { boot(recorder) }
                    val start = awaitWaiter(pidOf(lastWorkspace))
                    withClue("while it waits for the union's LAST workspace, the start holds WA and NOT the registry key") {
                        rowLockedElsewhere(WA) shouldBe true
                        holdsRegistryKey(start) shouldBe false
                    }
                    lastWorkspace.commit()
                    lastWorkspace.close()
                    awaitWaiter(registryHolder) shouldBe start
                    withClue("while it waits for the registry key, the start holds EVERY union workspace (G0: classes 2 before 3)") {
                        rowLockedElsewhere(WA) shouldBe true
                        rowLockedElsewhere(WB) shouldBe true
                    }
                    // A pin writer in WA on the serving instance: it needs WA, which the start holds.
                    val discard = CompletableFuture.supplyAsync { discardVersion(serving, apiPipeline, 2) }
                    awaitBlockedBy(start)
                    booted to discard
                }

            booted.get(5, TimeUnit.MINUTES).close()
            withClue("the discard waited for the start's commit, then read the pin the start wrote: refused, not deadlocked") {
                val response = discard.get(2, TimeUnit.MINUTES)
                withClue(response.asString().take(DIAGNOSTIC_CHARS)) {
                    response.statusCode shouldBe 409
                    response.jsonPath().getString("error.code") shouldBe "pipeline.version.pinned"
                }
            }
            version(fixture.apiA) shouldBe listOf(1, "RELEASED", API_A_PIPELINE, 2, 45, "A one")
            withClue("one attempt at the seam: the union once, then the registry key") {
                recorder.calls("locks.") shouldContainExactly listOf("locks.workspaces[$WA, $WB]", "locks.endpointRegistry")
            }
        } finally {
            serving.close()
            clearFixture()
        }
    }

    @Test
    @Order(8)
    fun `B6 - a CAS loss in the SECOND family rolls both families back and the runner replays them from fresh rows`(
        output: CapturedOutput,
    ) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        countingApiWrites(fixture.apiA)
        // WB's schedule's CAS loses ONCE (the row is skipped: zero rows updated), after both APIs and WA's schedule wrote.
        skippingScheduleWrite(fixture.scheduleZ, times = 1)
        try {
            val mark = output.out.length
            boot(recorder).close()

            withClue("one rollback, one commit; WA's API written twice — once per attempt") {
                recorder.outcomes() shouldContainExactly listOf("tx:rolled_back", "tx:committed")
                sequenceValue(API_WRITES) shouldBe 2L
                sequenceValue(SKIPS) shouldBe 2L
            }
            version(fixture.apiA) shouldBe listOf(1, "RELEASED", API_A_PIPELINE, 2, 45, "A one")
            payloadOf("schedules", fixture.scheduleZ) shouldBe mapper.readTree("""{"pipeline": "$SCHED_PIPELINE", "version": 2}""")
            payloadOf("schedule_runs", fixture.runZ) shouldBe mapper.readTree("""{"pipeline": "$OTHER_PIPELINE", "version": 1}""")
            assertOneSuccessSet(output.out.substring(mark), recorder, apis = 2, schedules = 2, runs = 2)
        } finally {
            dropFixtureTriggers()
            clearFixture()
        }
    }

    @Test
    @Order(9)
    fun `B6 - a candidate in a workspace the union did not cover re-plans the union, never acquiring a workspace late`(
        output: CapturedOutput,
    ) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        sql(
            "INSERT INTO workspaces (id, name, display_name, created_by) VALUES ('$WC', '$WC_NAME', 'RB', '$ADMIN_ID')" +
                " ON CONFLICT DO NOTHING",
        )
        if (scalar<Long>("SELECT COUNT(*) FROM pipelines WHERE workspace_id = '$WC'") == 0L) {
            pipeline(WC, API_C_PIPELINE, current = 1, "RELEASED")
        }
        var apiC: UUID? = null
        // After the first attempt read its plan and took its locks: a legacy API appears in WC, which the union lacks.
        recorder.beforeCall = { call ->
            if (call == "$API_STEP.convert" && apiC == null) apiC = legacyApi(WC, "/rb/c/one", API_C_PIPELINE)
        }
        try {
            val mark = output.out.length
            boot(recorder).close()

            withClue("attempt 1 rolled back on the uncovered row; attempt 2 planned the wider union and committed") {
                recorder.outcomes() shouldContainExactly listOf("tx:rolled_back", "tx:committed")
                recorder.calls("locks.") shouldContainExactly
                    listOf(
                        "locks.workspaces[$WA, $WB]",
                        "locks.endpointRegistry",
                        "locks.workspaces[$WA, $WB, $WC]",
                        "locks.endpointRegistry",
                    )
            }
            version(checkNotNull(apiC)) shouldBe listOf(1, "RELEASED", API_C_PIPELINE, 1, 30, "")
            version(fixture.apiA) shouldBe listOf(1, "RELEASED", API_A_PIPELINE, 2, 45, "A one")
            assertOneSuccessSet(output.out.substring(mark), recorder, apis = 3, schedules = 2, runs = 2)
        } finally {
            clearFixture()
        }
    }

    @Test
    @Order(10)
    fun `B6 - conflicts on every attempt refuse the start after exactly three attempts and nothing is converted`(output: CapturedOutput) {
        val fixture = legacyFixture()
        val recorder = Recorder()
        countingApiWrites(fixture.apiA)
        skippingScheduleWrite(fixture.scheduleZ, times = Int.MAX_VALUE)
        val before = snapshot()
        try {
            val mark = output.out.length
            val refused = shouldThrowAny { boot(recorder) }

            generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString("\n") shouldContain "3 times"
            withClue("exactly three attempts, each rolled back") {
                recorder.outcomes() shouldContainExactly List(3) { "tx:rolled_back" }
                sequenceValue(SKIPS) shouldBe 3L
                sequenceValue(API_WRITES) shouldBe 3L
            }
            assertUnchanged(before)
            assertNoSuccessEffects(output.out.substring(mark), recorder)
        } finally {
            dropFixtureTriggers()
            clearFixture()
        }
    }

    // ------------------------------------------------------------------ assertions

    /** Every candidate row, every table, on new connections, equals its pre-start snapshot; the diff is printed. */
    private fun assertUnchanged(before: Map<String, List<Map<String, Any?>>>) {
        val after = snapshot()
        val changed =
            after.flatMap { (table, rows) ->
                val old = before.getValue(table)
                (rows - old.toSet()).map { "$table now: $it" } + (old - rows.toSet()).map { "$table was: $it" }
            }
        withClue("rows that differ from the pre-start snapshot (committed by the refused start):\n${changed.joinToString("\n")}") {
            after shouldBe before
        }
    }

    /** B.7, failed start: no converted line, no success summary, no invalidation — none of a rolled-back attempt's effects. */
    private fun assertNoSuccessEffects(
        out: String,
        recorder: Recorder,
    ) {
        withClue("a rolled-back conversion reports no success") {
            lines(out, "event=endpoint.conversion.converted").shouldBeEmpty()
            lines(out, "event=endpoint.conversion.summary").shouldBeEmpty()
            lines(out, "event=scheduler.conversion.converted").shouldBeEmpty()
            lines(out, "event=scheduler.conversion.summary").shouldBeEmpty()
            recorder.count("invalidate") shouldBe 0
        }
    }

    /** B.7, successful start: exactly one set of success effects, every one AFTER the joint commit. */
    private fun assertOneSuccessSet(
        out: String,
        recorder: Recorder,
        apis: Int,
        schedules: Int,
        runs: Int,
    ) {
        withClue("one converted line per row, one summary per family, one invalidation — all after the commit") {
            lines(out, "event=endpoint.conversion.converted ").size shouldBe apis
            lines(out, "event=endpoint.conversion.summary converted=$apis ").size shouldBe 1
            lines(out, "event=scheduler.conversion.converted ").size shouldBe schedules
            lines(out, "event=scheduler.conversion.converted_run ").size shouldBe runs
            lines(out, "event=scheduler.conversion.summary converted=$schedules ").size shouldBe 1
            recorder.count("invalidate") shouldBe 1
            val window = recorder.window()
            window.indexOf("invalidate") shouldBe window.lastIndexOf("tx:committed") + 1
        }
    }

    private fun lines(
        out: String,
        prefix: String,
    ): List<String> = out.lines().filter { it.contains(prefix) }

    // ------------------------------------------------------------------ fixture

    private data class Fixture(
        val apiA: UUID,
        val apiB: UUID,
        val scheduleA: UUID,
        val scheduleZ: UUID,
        val runA: UUID,
        val runZ: UUID,
    )

    /** What the previous version left: two version-less APIs (one key-bound), two schema-1 schedules, a queued run each. */
    private fun legacyFixture(): Fixture {
        val apiA = legacyApi(WA, "/rb/a/one", API_A_PIPELINE, timeout = 45, description = "A one")
        val apiB = legacyApi(WB, "/rb/b/one", API_B_PIPELINE, enabled = false)
        sql(
            "INSERT INTO endpoint_key_bindings (path_prefix, api_key_id, workspace_id, created_by)" +
                " VALUES ('/rb/a', '$KEY_ID', '$WA', '$ADMIN_ID')",
        )
        val scheduleA = legacySchedule(WA, SCHEDULE_A, SCHED_PIPELINE)
        val scheduleZ = legacySchedule(WB, SCHEDULE_Z, SCHED_PIPELINE)
        val runA = legacyRun(scheduleA, SCHED_PIPELINE)
        // Queued before an edit of the schedule's target: its frozen copy still names the other pipeline.
        val runZ = legacyRun(scheduleZ, OTHER_PIPELINE)
        return Fixture(apiA, apiB, scheduleA, scheduleZ, runA, runZ)
    }

    private fun clearFixture() {
        val scope = "workspace_id IN ('$WA', '$WB', '$WC')"
        sql("DELETE FROM schedule_runs WHERE $scope")
        sql("DELETE FROM schedules WHERE $scope")
        sql("DELETE FROM endpoint_key_bindings WHERE $scope")
        sql("DELETE FROM published_endpoint_versions WHERE endpoint_id IN (SELECT id FROM published_endpoints WHERE $scope)")
        sql("UPDATE published_endpoints SET current_version = NULL WHERE $scope")
        sql("DELETE FROM published_endpoints WHERE $scope")
    }

    /** Counts every version write of [api] on a sequence: the count survives the rollback of the write it counts. */
    private fun countingApiWrites(api: UUID) {
        sql("CREATE SEQUENCE $API_WRITES")
        sql(
            "CREATE FUNCTION rb_count_api() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF NEW.endpoint_id = ''$api''::uuid THEN PERFORM nextval(''$API_WRITES''); END IF; RETURN NEW; END'",
        )
        sql("CREATE TRIGGER rb_count_api AFTER INSERT ON published_endpoint_versions FOR EACH ROW EXECUTE FUNCTION rb_count_api()")
    }

    /** The conversion's CAS on [schedule] updates zero rows (a lost CAS) for its first [times] attempts. */
    private fun skippingScheduleWrite(
        schedule: UUID,
        times: Int,
    ) {
        sql("CREATE SEQUENCE $SKIPS")
        sql(
            "CREATE FUNCTION rb_skip_schedule() RETURNS trigger LANGUAGE plpgsql AS " +
                "'BEGIN IF NEW.id = ''$schedule''::uuid AND NEW.payload_schema_version = 2 THEN " +
                "IF nextval(''$SKIPS'') <= $times THEN RETURN NULL; END IF; END IF; RETURN NEW; END'",
        )
        sql("CREATE TRIGGER rb_skip_schedule BEFORE UPDATE ON schedules FOR EACH ROW EXECUTE FUNCTION rb_skip_schedule()")
    }

    private fun dropFixtureTriggers() {
        sql("DROP TRIGGER IF EXISTS rb_count_api ON published_endpoint_versions")
        sql("DROP FUNCTION IF EXISTS rb_count_api()")
        sql("DROP TRIGGER IF EXISTS rb_skip_schedule ON schedules")
        sql("DROP FUNCTION IF EXISTS rb_skip_schedule()")
        sql("DROP SEQUENCE IF EXISTS $API_WRITES")
        sql("DROP SEQUENCE IF EXISTS $SKIPS")
    }

    private fun sequenceValue(sequence: String): Long =
        rows("SELECT last_value, is_called FROM $sequence").single().let { if (it["is_called"] == true) it["last_value"] as Long else 0L }

    private fun pipeline(
        workspace: UUID,
        name: String,
        current: Int?,
        vararg statuses: String,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO pipelines (id, name, display_name, owner_id, current_version, workspace_id)" +
                " VALUES ('$id', '$name', 'RB', '$ADMIN_ID', ${current ?: "NULL"}, '$workspace')",
        )
        statuses.forEachIndexed { index, status -> copyVersion(id, index + 1, status) }
        return id
    }

    /** Version [version] of [pipeline]: a copy of the REST-created pipeline's v1 body with [status]. */
    private fun copyVersion(
        pipeline: UUID,
        version: Int,
        status: String,
    ) = sql(
        "INSERT INTO pipeline_versions (pipeline_id, version, body_json, body_hash, status, created_by, released_by," +
            " released_at, discarded_by, discarded_at)" +
            " SELECT '$pipeline', $version, body_json, body_hash, '$status', created_by," +
            " CASE WHEN '$status' <> 'DRAFT' THEN created_by END, CASE WHEN '$status' <> 'DRAFT' THEN NOW() END," +
            " CASE WHEN '$status' = 'DISCARDED' THEN created_by END, CASE WHEN '$status' = 'DISCARDED' THEN NOW() END" +
            " FROM pipeline_versions WHERE pipeline_id = '$apiPipeline' AND version = 1",
    )

    /** An API the previous version wrote: the entity and its legacy columns, no version row. */
    private fun legacyApi(
        workspace: UUID,
        path: String,
        pipeline: String,
        timeout: Int = 30,
        description: String = "",
        enabled: Boolean = true,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO published_endpoints" +
                " (id, workspace_id, path_pattern, pipeline_id, timeout_seconds, description, is_enabled, created_by)" +
                " SELECT '$id', '$workspace', '$path', p.id, $timeout, '$description', $enabled, '$ADMIN_ID'" +
                " FROM pipelines p WHERE p.workspace_id = '$workspace' AND p.name = '$pipeline'",
        )
        return id
    }

    /** A schedule the previous version wrote: schema 1, following CURRENT, never due during the suite. */
    private fun legacySchedule(
        workspace: UUID,
        name: String,
        pipeline: String,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO schedules (id, workspace_id, name, executor_id, payload_schema_version, payload_json, target_ref, cron," +
                " timezone, created_by, updated_by)" +
                " VALUES ('$id', '$workspace', '$name', 'pipeline', 1, '${legacyPayload(pipeline)}'," +
                " 'pipeline:$pipeline', '$YEARLY', 'UTC', '$ADMIN_ID', '$ADMIN_ID')",
        )
        return id
    }

    /** A queued manual run of [schedule] whose frozen payload copy names [pipeline]; admitted until tomorrow. */
    private fun legacyRun(
        schedule: UUID,
        pipeline: String,
    ): UUID {
        val id = UUID.randomUUID()
        sql(
            "INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, reference_at, reference_timezone, admit_by," +
                " executor_id, payload_schema_version, payload_json, parameters_json, actor_user_id, state)" +
                " SELECT '$id', s.id, s.workspace_id, 'manual', NOW(), s.timezone, NOW() + INTERVAL '1 day', s.executor_id," +
                " 1, CAST('${legacyPayload(pipeline)}' AS jsonb), s.parameters_json, u.id, 'queued'" +
                " FROM schedules s, users u WHERE s.id = '$schedule' AND u.kind = 'system'",
        )
        return id
    }

    private fun legacyPayload(pipeline: String) = """{"pipeline": "$pipeline", "version": "current"}"""

    // ------------------------------------------------------------------ reads (each on a new connection)

    /** Every candidate row of every table the conversion writes, whole, in id order. */
    private fun snapshot(): Map<String, List<Map<String, Any?>>> {
        val scope = "workspace_id IN ('$WA', '$WB', '$WC')"
        return mapOf(
            "endpoints" to rows("SELECT * FROM published_endpoints WHERE $scope ORDER BY id"),
            "versions" to
                rows(
                    "SELECT v.* FROM published_endpoint_versions v JOIN published_endpoints e ON e.id = v.endpoint_id" +
                        " WHERE e.$scope ORDER BY v.endpoint_id, v.version",
                ),
            "bindings" to rows(BINDINGS),
            "schedules" to rows("SELECT * FROM schedules WHERE $scope ORDER BY id"),
            "runs" to rows("SELECT * FROM schedule_runs WHERE $scope ORDER BY id"),
        )
    }

    private fun Map<String, List<Map<String, Any?>>>.endpoint(id: UUID): Map<String, Any?> = getValue("endpoints").single { it["id"] == id }

    private fun endpoint(id: UUID): Map<String, Any?> = rows("SELECT * FROM published_endpoints WHERE id = '$id'").single()

    /** The API's CURRENT version: number, status, pinned pipeline and version, timeout, description. */
    private fun version(api: UUID): List<Any?> =
        rows(
            "SELECT v.version, v.status, v.pipeline_name, v.pipeline_version, v.timeout_seconds, v.description" +
                " FROM published_endpoints e JOIN published_endpoint_versions v" +
                " ON v.endpoint_id = e.id AND v.version = e.current_version WHERE e.id = '$api'",
        ).single().values.toList()

    private fun payloadOf(
        table: String,
        id: UUID,
    ) = mapper.readTree(scalar<String>("SELECT payload_json::text FROM $table WHERE id = '$id'"))

    // ------------------------------------------------------------------ locks

    /** Holds [lockSql] in an open transaction (a writer in flight), runs [block] with the holder's pid, then commits. */
    private fun <T> holding(
        lockSql: String,
        block: (Int) -> T,
    ): T =
        connection().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.execute(lockSql) }
            val pid = pidOf(holder)
            val result = block(pid)
            holder.commit()
            result
        }

    /** Waits until one backend is queued directly behind [holderPid]; returns its pid. */
    private fun awaitWaiter(holderPid: Int): Int =
        poll("a backend waiting behind $holderPid") {
            rows("SELECT pid FROM pg_stat_activity WHERE $holderPid = ANY(pg_blocking_pids(pid))").firstOrNull()?.get("pid") as Int?
        }

    /** Waits until some backend is queued behind [pid]. */
    private fun awaitBlockedBy(pid: Int) {
        poll("a backend waiting behind $pid") {
            rows("SELECT pid FROM pg_stat_activity WHERE $pid = ANY(pg_blocking_pids(pid))").firstOrNull()?.get("pid")
        }
    }

    private fun <T : Any> poll(
        what: String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        while (System.nanoTime() < deadline) {
            probe()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("expected $what")
    }

    /** True when another transaction holds [workspace]'s guard row: a NOWAIT lock attempt fails with `55P03`. */
    private fun rowLockedElsewhere(workspace: UUID): Boolean =
        connection().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("SELECT 1 FROM workspaces WHERE id = '$workspace' FOR NO KEY UPDATE NOWAIT") }
                false
            } catch (e: SQLException) {
                check(e.sqlState == LOCK_NOT_AVAILABLE) { "unexpected ${e.sqlState}: ${e.message}" }
                true
            } finally {
                c.rollback()
            }
        }

    /** Whether backend [pid] holds the endpoint registry's advisory key (one bigint key: classid 0, objid = the key). */
    private fun holdsRegistryKey(pid: Int): Boolean =
        scalar<Long>(
            "SELECT COUNT(*) FROM pg_locks WHERE pid = $pid AND granted AND locktype = 'advisory'" +
                " AND classid = 0 AND objid = $REGISTRY_KEY AND objsubid = 1",
        ) > 0

    private fun pidOf(c: Connection): Int =
        c.createStatement().use { s ->
            s.executeQuery("SELECT pg_backend_pid()").use {
                it.next()
                it.getInt(1)
            }
        }

    // ------------------------------------------------------------------ REST

    private fun createAndRelease(
        context: ConfigurableApplicationContext,
        name: String,
    ): UUID {
        val created = session(context).contentType(ContentType.JSON).body(pipelineBody(name)).post("/api/v1/pipelines")
        check(created.statusCode == 201) { "pipeline create $name → ${created.statusCode}: ${created.asString().take(DIAGNOSTIC_CHARS)}" }
        val id = created.jsonPath().getString("data.id")
        session(context)
            .header("If-Match", created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return UUID.fromString(id)
    }

    private fun discardVersion(
        context: ConfigurableApplicationContext,
        pipeline: UUID,
        version: Int,
    ): Response = session(context).post("/api/v1/pipelines/$pipeline/versions/$version/discard")

    private fun session(context: ConfigurableApplicationContext) =
        given().port(checkNotNull(context.environment.getProperty("local.server.port", Int::class.java))).asSession(adminJwt)

    private fun pipelineBody(name: String): String =
        mapper.writeValueAsString(
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Rollback boundary",
                "description" to "A calculator over a fixed date",
                "parameters" to mapOf("as_of_date" to mapOf("type" to "DATE", "required" to true, "description" to "A date.")),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "fq",
                            "type" to "CALCULATOR",
                            "kind" to "fiscal_quarter",
                            "context_key" to "quarter",
                            "inputs" to mapOf("date" to "\$as_of_date", "fiscal_start" to "01-01"),
                        ),
                    ),
            ),
        )

    // ------------------------------------------------------------------ boot + the timeline

    /** Boots the shipped application with [recorder]'s proxies installed before any bean is created. */
    private fun boot(recorder: Recorder): ConfigurableApplicationContext {
        val args = properties().map { (k, v) -> "--$k=$v" }.toTypedArray()
        return SpringApplicationBuilder(DatapipelinesApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .initializers(ApplicationContextInitializer<ConfigurableApplicationContext> { it.beanFactory.addBeanPostProcessor(recorder) })
            .run(*args)
    }

    /**
     * One boot's timeline. Wraps — by interface NAME, since this module compiles against `app` only — the runner's
     * steps (`StartupConversion`), its lock port (`ConversionLocks`) and the registry's invalidation publisher in JDK
     * proxies that record each call; the first step call inside a transaction registers a synchronization that records
     * the transaction's outcome. [beforeCall] runs before each recorded step call (a test's injection point).
     */
    private class Recorder : BeanPostProcessor {
        val timeline = CopyOnWriteArrayList<String>()

        @Volatile
        var beforeCall: (String) -> Unit = {}

        private val recorded = listOfNotNull(type(STEP_TYPE), type(LOCKS_TYPE), type(PUBLISHER_TYPE))
        private val attemptType = type(ATTEMPT_TYPE)

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (beanName == RUNNER) timeline += RUNNER_DONE
            val wrapped = recorded.firstOrNull { it.isInstance(bean) } ?: return bean
            val label =
                when (wrapped.name) {
                    LOCKS_TYPE -> "locks"
                    PUBLISHER_TYPE -> "invalidate"
                    else -> beanName
                }
            return wrap(bean, label, isStep = wrapped.name == STEP_TYPE)
        }

        /** [bean] behind a recording proxy; a step's returned attempt is wrapped too, as `<step>.<method>`. */
        private fun wrap(
            bean: Any,
            label: String,
            isStep: Boolean,
        ): Any {
            val handler =
                InvocationHandler { _, method, args ->
                    val arguments = args ?: emptyArray()
                    if (method.declaringClass == Any::class.java) return@InvocationHandler method.invoke(bean, *arguments)
                    val call =
                        when (label) {
                            "invalidate" -> {
                                "invalidate"
                            }

                            "locks" -> {
                                "locks.${method.name}" +
                                    arguments.joinToString("") { (it as? Collection<*>)?.sortedBy(Any?::toString).toString() }
                            }

                            else -> {
                                "$label.${method.name}"
                            }
                        }
                    timeline += call
                    if (isStep) {
                        watchTransaction()
                        beforeCall(call)
                    }
                    val result =
                        try {
                            method.invoke(bean, *arguments)
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    if (isStep && result != null && attemptType?.isInstance(result) == true) wrap(result, label, isStep = true) else result
                }
            return Proxy.newProxyInstance(bean.javaClass.classLoader, ClassUtils.getAllInterfaces(bean), handler)
        }

        private fun watchTransaction() {
            if (!TransactionSynchronizationManager.isSynchronizationActive() || TransactionSynchronizationManager.hasResource(this)) return
            TransactionSynchronizationManager.bindResource(this, true)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(this@Recorder)
                        timeline += if (status == TransactionSynchronization.STATUS_COMMITTED) "tx:committed" else "tx:rolled_back"
                    }
                },
            )
        }

        override fun postProcessBeforeInitialization(
            bean: Any,
            beanName: String,
        ): Any {
            if (beanName == RUNNER) timeline += RUNNER_START
            return bean
        }

        /** The runner's window: from its initialization to its completion (or the boot's end when it threw). */
        fun window(): List<String> =
            timeline.toList().let { all ->
                val from = all.indexOf(RUNNER_START).takeIf { it >= 0 } ?: return emptyList()
                val to = all.indexOf(RUNNER_DONE).takeIf { it >= 0 } ?: all.size
                all.subList(from, to)
            }

        fun count(call: String): Int = window().count { it == call }

        fun calls(prefix: String): List<String> = window().filter { it.startsWith(prefix) }

        fun outcomes(): List<String> = calls("tx:")

        private fun type(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()
    }

    private fun connection(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun sql(statement: String) = connection().use { c -> c.createStatement().use { it.execute(statement) } }

    @Suppress("UNCHECKED_CAST")
    private fun <T> scalar(query: String): T = rows(query).single().values.first() as T

    /** Rows as label → value maps; JSON columns as text, arrays as lists, so rows compare by value. */
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

    private fun valueOf(value: Any?): Any? =
        when {
            value is java.sql.Array -> (value.array as Array<*>).toList()
            value?.javaClass?.simpleName == "PGobject" -> value.toString()
            else -> value
        }

    companion object {
        private const val ADMIN_EMAIL = "rollback-admin@e2e.test"
        private val ADMIN_ID: UUID = UUID.randomUUID()
        private val KEY_IDENTITY: UUID = UUID.randomUUID()
        private const val KEY_ID = "dpk_RB895KEYAAAA"

        /** Fixed ids so the guard's sorted order is known: WA < WB < WC (by Java's UUID order and PostgreSQL's). */
        private val WA: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        private val WB: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
        private val WC: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c3")
        private const val WA_NAME = "rb-a"
        private const val WB_NAME = "rb-b"
        private const val WC_NAME = "rb-c"

        private const val API_A_PIPELINE = "test/rb_api_a"
        private const val API_B_PIPELINE = "test/rb_api_b"
        private const val API_C_PIPELINE = "test/rb_api_c"
        private const val SCHED_PIPELINE = "test/rb_sched"
        private const val OTHER_PIPELINE = "test/rb_other"
        private const val SCHEDULE_A = "test/rb_s_a"
        private const val SCHEDULE_Z = "test/rb_s_z"

        private const val API_STEP = "endpointStartupConversion"
        private const val RUNNER = "conversionStartup"
        private const val RUNNER_START = "runner.start"
        private const val RUNNER_DONE = "runner.done"
        private const val STEP_TYPE = "co.datapipelines.web.endpoints.StartupConversion"
        private const val ATTEMPT_TYPE = "co.datapipelines.web.endpoints.ConversionAttempt"
        private const val LOCKS_TYPE = "co.datapipelines.web.endpoints.ConversionLocks"
        private const val PUBLISHER_TYPE = "co.datapipelines.application.endpoints.EndpointInvalidationPublisher"

        /** `EndpointRegistryLock.KEY` (typesystem; not on this module's compile classpath). */
        private const val REGISTRY_KEY = 740_001L
        private const val LOCK_REGISTRY = "SELECT pg_advisory_xact_lock($REGISTRY_KEY)"
        private val LOCK_WB = "SELECT 1 FROM workspaces WHERE id = '$WB' FOR NO KEY UPDATE"
        private const val LOCK_NOT_AVAILABLE = "55P03"

        private const val INJECTED_FAILURE = "rb895 injected last conversion write failure"
        private const val API_WRITES = "rb_api_a_writes"
        private const val SKIPS = "rb_schedule_skips"
        private val BINDINGS = "SELECT * FROM endpoint_key_bindings WHERE workspace_id IN ('$WA', '$WB', '$WC') ORDER BY path_prefix"
        private const val YEARLY = "0 3 1 1 *"
        private const val POLL_MILLIS = 50L
        private const val DIAGNOSTIC_CHARS = 1_000

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
                // The held locks queue writers across a whole context start; the production bound (5 s) would refuse
                // the waiting start busy before the observation is made.
                "datapipelines.release.dependencies.lock-wait-seconds" to "60",
            )
    }
}
