package co.datapipelines.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.IOException
import java.net.URI
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **Scheduler notification mail, end to end** (#442b, acceptance test [4]): the full application in a
 * DISPATCHING context with notifications ON, a real SMTP relay (GreenMail) whose IMAP side is the box
 * every assertion reads, and [SmtpInterposer] in front of it so a connect failure and an ambiguous send
 * are real transport events, not doubles. One recipient box per leg, so a leg's count is its own.
 *
 * - **start and success** — two recipients each receive their OWN message (To is that address alone),
 *   the subject is `<schedule> — <state words>`, Message-ID is the delivery's, the link's host is the
 *   configured base URL's, and no body carries an address, SQL or a parameter value;
 * - **failure, could not start + blocked once, cancellation** — real runs: an H2 statement that fails at
 *   runtime, a schedule whose only release was discarded (its second Run now is refused, no second
 *   notice), and an execution cancelled over §10.4;
 * - **unknown and its update** — the sweeper's own `instance_lost` write lands on a live execution
 *   (`ExecutionRepository.sweepStaleRunning`'s statement), the reconciler records `unknown` and blocks,
 *   then the still-running worker writes its real terminal over it (A5) and the update is mailed;
 * - **allowlist** — a stored recipient outside the allowlist (a deployment that narrowed it after the
 *   save) is written `failed / domain_not_allowed` and its box stays empty;
 * - **transient retry** — the first connection is refused before the greeting; the retry is accepted;
 * - **ambiguous send** — the relay takes the message but its answer never reaches the sender: the
 *   delivery is `uncertain`, an author's resend warns `may_duplicate`, and the box holds TWO messages
 *   with ONE Message-ID; a viewer's resend is 403;
 * - **a replayed reconciliation** — a finished run rewound to `running` makes the reconciler replay its
 *   terminal transition through the real ledger and outbox: no second event, nothing sent;
 * - **crash recovery** — rows a stopped instance left (seeded: a pending one, and one claimed an hour
 *   ago) are, respectively, sent at the next tick and marked `uncertain / interrupted`, never sent;
 * - **logs** — no `event=` line carries an address; the domain is there instead (non-vacuous).
 *
 * The delivery flag OFF is [ScheduleNotificationMailOffE2eTest] (its own context: the flag is boot
 * configuration). The allowlist narrowed BETWEEN enqueue and send, and the backoff schedule, are the
 * scheduler module's (`ScheduleNotificationSenderIntegrationTest`, F3/F6) — boot configuration again.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScheduleNotificationDeliveryE2eTest {
    @LocalServerPort
    private var port: Int = 0

    private val mapper = ObjectMapper()

    @BeforeAll
    fun captureEventLines() {
        lines.start()
        (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME).addAppender(lines)
    }

    @Test
    @Order(1)
    fun `start and success - every recipient gets its own message, and nothing it should not carry`() {
        seed()
        val schedule = createSchedule("test/notify_ok", OK_PIPELINE, listOf(OPS, LEAD), listOf("start", "success"))
        val run = runNow(schedule)
        awaitRun(schedule, run)["state"].asText() shouldBe "succeeded"
        val deliveries = awaitSettled(run, expected = 4)
        deliveries.forEach { it.state shouldBe "accepted" }

        listOf(OPS, LEAD).forEach { box ->
            val messages = awaitMessages(box, expected = 2)
            messages.map { it.subject } shouldContainExactlyInAnyOrder listOf("test/notify_ok — Started", "test/notify_ok — Completed")
            messages.forEach { message ->
                val delivery = deliveries.single { it.recipient == box && it.messageId == message.messageID }
                assertNotice(message, box, schedule, run)
                val expected = if (message.subject.endsWith("Started")) "started" else "completed"
                withClue("$box ${delivery.outcome}") { delivery.outcome shouldBe expected }
            }
        }
        trailKinds(run).filter { it.startsWith("notification_") } shouldContainExactlyInAnyOrder
            listOf("notification_enqueued", "notification_enqueued") + List(4) { "notification_sent" }
        okSchedule = schedule
        okRun = run
    }

    @Test
    @Order(2)
    fun `a failed run mails the failure notice`() {
        val schedule = createSchedule("test/notify_failing", FAIL_PIPELINE, listOf(FAIL))
        val run = runNow(schedule)
        awaitRun(schedule, run)["state"].asText() shouldBe "failed"
        awaitSettled(run, expected = 1).single().state shouldBe "accepted"
        assertNotice(awaitMessages(FAIL, expected = 1).single(), FAIL, schedule, run, "test/notify_failing — Failed")
    }

    @Test
    @Order(3)
    fun `a run that could not start mails that, and the block it causes is mailed once`() {
        val pipelineId = createPipeline(REFUSED_PIPELINE, OK_TEMPLATE)
        val schedule = createSchedule("test/notify_refused", REFUSED_PIPELINE, listOf(REFUSED))
        session(ADMIN_SESSION).post("/api/v1/pipelines/$pipelineId/versions/1/discard").then().statusCode(200)
        val run = runNow(schedule)
        val detail = awaitRun(schedule, run)
        detail["state"].asText() shouldBe "not_started"
        detail["reason"].asText() shouldBe "pointer_null"
        awaitSettled(run, expected = 2).map { it.outcome } shouldContainExactlyInAnyOrder listOf("could_not_start", "blocked")

        val again = session(ADMIN_SESSION).post("/api/v1/schedules/$schedule/run")
        again.statusCode shouldBe 409
        again.jsonPath().getString("error.code") shouldBe "schedule.blocked"
        scalar("SELECT count(*) FROM schedule_notification_events WHERE schedule_id = '$schedule'") shouldBe "2"
        awaitMessages(REFUSED, expected = 2).map { it.subject } shouldContainExactlyInAnyOrder
            listOf("test/notify_refused — Could not start", "test/notify_refused — Schedule blocked")
    }

    @Test
    @Order(4)
    fun `a cancelled run mails the cancellation`() {
        val schedule = createSchedule("test/notify_cancelled", SLOW_PIPELINE, listOf(CANCEL))
        val run = runNow(schedule)
        val execution = awaitExecution(schedule, run)
        session(ADMIN_SESSION).delete("/api/v1/executions/$execution").then().statusCode(204)
        awaitRun(schedule, run)["state"].asText() shouldBe "cancelled"
        awaitSettled(run, expected = 1).single().state shouldBe "accepted"
        assertNotice(awaitMessages(CANCEL, expected = 1).single(), CANCEL, schedule, run, "test/notify_cancelled — Stopped — cancelled")
    }

    @Test
    @Order(5)
    fun `an unknown outcome mails unknown and the block, and the worker's later real terminal mails the update`() {
        val schedule = createSchedule("test/notify_unknown", SLOW_PIPELINE, listOf(UNKNOWN))
        val run = runNow(schedule)
        val execution = awaitExecution(schedule, run)
        // The sweeper's own statement (ExecutionRepository.sweepStaleRunning), aimed at this execution:
        // its instance looks lost while its worker is still running the statement.
        sql(
            "UPDATE pipeline_executions SET status = 'ABORTED', completed_at = NOW(), " +
                "duration_ms = EXTRACT(EPOCH FROM (NOW() - started_at)) * 1000, " +
                "error_json = CAST('{\"code\":\"pipeline.execution.instance_lost\"}' AS jsonb) " +
                "WHERE execution_id = '$execution' AND status = 'RUNNING'",
        )
        poll("run $run to be unknown") { runDetail(schedule, run).takeIf { it["state"].asText() == "unknown" } }
        val updated =
            poll("run $run to be updated after unknown") {
                runDetail(schedule, run).takeIf { it["state"].asText() == "succeeded" }
            }
        updated["trail"].map { it["kind"].asText() }.filter { !it.startsWith("notification_") } shouldContainExactly
            listOf("recorded", "claimed", "execution_started", "unknown", "updated_after_unknown")

        awaitSettled(run, expected = 3).map { it.outcome } shouldContainExactlyInAnyOrder listOf("unknown", "blocked", "unknown_update")
        awaitMessages(UNKNOWN, expected = 3).map { it.subject } shouldContainExactlyInAnyOrder
            listOf(
                "test/notify_unknown — Outcome unknown — action required",
                "test/notify_unknown — Schedule blocked",
                "test/notify_unknown — Update on an unknown run",
            )
    }

    @Test
    @Order(6)
    fun `a stored recipient outside the allowlist is written failed and never receives`() {
        val schedule = createSchedule("test/notify_allowlist", OK_PIPELINE, listOf(LISTED), listOf("success"))
        // The allowlist was narrowed after this schedule saved: the stored list still names the outsider.
        sql("UPDATE schedules SET notification_recipients = ARRAY['$LISTED', '$OUTSIDE'] WHERE id = '$schedule'")
        val run = runNow(schedule)
        awaitRun(schedule, run)
        val deliveries = awaitSettled(run, expected = 2)
        deliveries.single { it.recipient == LISTED }.state shouldBe "accepted"
        deliveries.single { it.recipient == OUTSIDE }.let { outsider ->
            outsider.state shouldBe "failed"
            outsider.code shouldBe "domain_not_allowed"
            outsider.attempts shouldBe 0
        }
        awaitMessages(LISTED, expected = 1)
        // Sent in order by one sender: the listed box has its message, the outsider's row was never pending.
        messages(OUTSIDE).size shouldBe 0
    }

    @Test
    @Order(7)
    fun `a relay that cannot be reached is retried and the retry is accepted`() {
        val schedule = createSchedule("test/notify_transient", OK_PIPELINE, listOf(TRANSIENT), listOf("success"))
        val before = interposer.accepted.get()
        interposer.failNext(SmtpInterposer.Fault.REFUSE)
        val run = runNow(schedule)
        awaitRun(schedule, run)
        val delivery = awaitSettled(run, expected = 1).single()
        delivery.state shouldBe "accepted"
        delivery.attempts shouldBe 2
        (interposer.accepted.get() - before) shouldBe 2 // the refused connection, then the accepted one
        awaitMessages(TRANSIENT, expected = 1)
        trailKinds(run).filter { it.startsWith("notification_") } shouldContainExactly listOf("notification_enqueued", "notification_sent")
    }

    @Test
    @Order(8)
    fun `an ambiguous send is uncertain, and the resend warns and delivers a second copy under the same Message-ID`() {
        val schedule = createSchedule("test/notify_uncertain", OK_PIPELINE, listOf(UNCERTAIN), listOf("success"))
        interposer.failNext(SmtpInterposer.Fault.DROP_AFTER_DATA)
        val run = runNow(schedule)
        awaitRun(schedule, run)
        val delivery = awaitSettled(run, expected = 1).single()
        delivery.state shouldBe "uncertain"
        delivery.code shouldBe "io_after_connect"
        awaitMessages(UNCERTAIN, expected = 1) // the relay HAS it — the sender could not know

        val path = "/api/v1/schedules/$schedule/runs/$run/deliveries/${delivery.id}/resend"
        session(VIEWER_SESSION).post(path).then().statusCode(403)
        val resent =
            session(AUTHOR_SESSION)
                .post(path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        resent.getString("data.warning") shouldBe "may_duplicate"
        resent.getString("data.state") shouldBe "pending"
        resent.getInt("data.resend_count") shouldBe 1

        awaitSettled(run, expected = 1).single().state shouldBe "accepted"
        val copies = awaitMessages(UNCERTAIN, expected = 2)
        copies.map { it.messageID } shouldContainExactly listOf(delivery.messageId, delivery.messageId)
        scalar(
            "SELECT details_json->>'prior_state' FROM audit_log WHERE event = 'schedule.notification_resent' " +
                "AND details_json->>'delivery_id' = '${delivery.id}'",
        ) shouldBe "uncertain"
        scalar("SELECT count(*) FROM audit_log WHERE event = 'schedule.notification_resent' AND details_json::text LIKE '%@%'") shouldBe "0"
    }

    @Test
    @Order(9)
    fun `a replayed reconciliation writes no second event and sends nothing`() {
        // Rewind leg 1's finished run to the state its terminal callback found: the reconciler's next
        // tick replays the whole transition through the real ledger and outbox.
        sql("UPDATE schedule_runs SET state = 'running' WHERE id = '$okRun' AND state = 'succeeded'")
        poll("the replayed terminal transition of run $okRun") {
            runDetail(okSchedule, okRun).takeIf { detail ->
                detail["state"].asText() == "succeeded" && detail["trail"].count { it["kind"].asText() == "finished" } == 2
            }
        }
        scalar("SELECT count(*) FROM schedule_notification_events WHERE run_id = '$okRun'") shouldBe "2"
        scalar(
            "SELECT count(*) FROM schedule_notification_deliveries d JOIN schedule_notification_events e ON e.id = d.event_id " +
                "WHERE e.run_id = '$okRun'",
        ) shouldBe "4"
        trailKinds(okRun).count { it == "notification_enqueued" } shouldBe 2
    }

    @Test
    @Order(10)
    fun `rows a stopped instance left - the pending one is sent next tick, the claimed one is uncertain and never sent`() {
        val event = checkNotNull(scalar("SELECT id FROM schedule_notification_events WHERE run_id = '$okRun' AND outcome = 'completed'"))
        sql(
            """
            INSERT INTO schedule_notification_deliveries
                (id, event_id, recipient, state, attempts, next_attempt_at, sending_since, message_id) VALUES
                ('${UUID.randomUUID()}', '$event', '$CRASH_PENDING', 'pending', 0, now(), NULL, '<${UUID.randomUUID()}@e2e.test>'),
                ('${UUID.randomUUID()}', '$event', '$CRASH_CLAIMED', 'pending', 1, now() - interval '1 hour',
                 now() - interval '1 hour', '<${UUID.randomUUID()}@e2e.test>')
            """.trimIndent(),
        )
        val settled = awaitSettled(okRun, expected = 6)
        settled.single { it.recipient == CRASH_PENDING }.state shouldBe "accepted"
        settled.single { it.recipient == CRASH_CLAIMED }.let { claimed ->
            claimed.state shouldBe "uncertain"
            claimed.code shouldBe "interrupted"
        }
        awaitMessages(CRASH_PENDING, expected = 1).single().subject shouldBe "test/notify_ok — Completed"
        messages(CRASH_CLAIMED).size shouldBe 0
        trailKinds(okRun).count { it == "notification_uncertain" } shouldBe 1
        // The replayed reconciliation sent nothing: the first recipients still hold two messages each.
        listOf(OPS, LEAD).forEach { messages(it).size shouldBe 2 }
    }

    @Test
    @Order(11)
    fun `no scheduler event line carries an address - the domain is there instead`() {
        lines.captured.filter { ADDRESS.containsMatchIn(it) } shouldBe emptyList()
        withClue("non-vacuity: the legs above logged their sends") {
            lines.captured
                .filter { it.contains("event=scheduler.notification.sent") && it.contains("domain=example.com") }
                .shouldNotBeEmpty()
        }
        // The Security list: a line names its schedule and run, so an operator can find the notice
        // without the address. backlog_full is about the queue, not one schedule.
        val scoped =
            lines.captured.filter { it.contains("event=scheduler.notification.") && !it.contains("notification.backlog_full") }
        scoped.shouldNotBeEmpty()
        scoped.filterNot { SCHEDULE_ID.containsMatchIn(it) && RUN_ID.containsMatchIn(it) } shouldBe emptyList()
    }

    // ------------------------------------------------------------------------------- the message

    private fun assertNotice(
        message: MimeMessage,
        box: String,
        schedule: String,
        run: String,
        subject: String? = null,
    ) {
        subject?.let { message.subject shouldBe it }
        message.getRecipients(Message.RecipientType.TO).map { (it as InternetAddress).address } shouldContainExactly listOf(box)
        message.getRecipients(Message.RecipientType.CC) shouldBe null
        (message.from.single() as InternetAddress).address shouldBe "noreply@datapipelines.test"
        withClue("$box: the Message-ID is that recipient's delivery's") {
            scalar(
                "SELECT count(*) FROM schedule_notification_deliveries WHERE recipient = '$box' AND message_id = '${message.messageID}'",
            ) shouldBe "1"
        }
        val text = part(message, "text/plain")
        val html = part(message, "text/html")
        val link = "$BASE_URL/schedules?id=$schedule&run=$run"
        text shouldContain link
        val execution = scalar("SELECT execution_id::text FROM schedule_runs WHERE id = '$run'")
        if (execution != null && execution != "") text shouldContain "Execution: $execution"
        val started = scalar("SELECT started_at::text FROM schedule_runs WHERE id = '$run'")
        val ended = scalar("SELECT finished_at::text FROM schedule_runs WHERE id = '$run'")
        // STARTED notices may precede the eventual finish; terminal notices carry known times.
        if (started != null && started != "") text shouldContain "Started:"
        if (ended != null && ended != "" && message.subject?.endsWith("Started") != true) text shouldContain "Ended:"
        println(
            "IMAP notice subject=${message.subject} message_id=${message.messageID} to=$box " +
                "started=${"Started:" in text} ended=${"Ended:" in text} execution=${"Execution:" in text}",
        )
        URI(LINK.find(text)!!.value).host shouldBe URI(BASE_URL).host
        listOf(text, html).forEach { body ->
            ADDRESS.containsMatchIn(body) shouldBe false
            body shouldNotContain MARKER
            body.uppercase() shouldNotContain "SELECT "
        }
    }

    // ------------------------------------------------------------------------------- the box (IMAP)

    /** Every message in [address]'s box, read over IMAP (auth disabled on the container: any password). */
    private fun messages(address: String): List<MimeMessage> {
        val props =
            Properties().apply {
                setProperty("mail.store.protocol", "imap")
                setProperty("mail.imap.host", greenmail.host)
                setProperty("mail.imap.port", greenmail.getMappedPort(IMAP_PORT).toString())
            }
        val store = Session.getInstance(props).getStore("imap")
        store.connect(greenmail.host, greenmail.getMappedPort(IMAP_PORT), address, "any-password")
        return try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            inbox.messages.map { MimeMessage(it as MimeMessage) }
        } finally {
            store.close()
        }
    }

    /** Synchronises on the box, never on a clock; a dropped IMAP poll counts as "not yet" (#247). */
    private fun awaitMessages(
        address: String,
        expected: Int,
    ): List<MimeMessage> {
        val deadline = System.nanoTime() + AWAIT.toNanos()
        var found: List<MimeMessage> = emptyList()
        var lastReadError: Exception? = null
        while (System.nanoTime() < deadline) {
            try {
                found = messages(address)
            } catch (e: MessagingException) {
                lastReadError = e
            } catch (e: IOException) {
                lastReadError = e
            }
            if (found.size >= expected) {
                found.size shouldBe expected
                return found
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError(
            "expected $expected message(s) in $address's box within $AWAIT, found ${found.size}" +
                (lastReadError?.let { "; last IMAP read error: $it" } ?: ""),
        )
    }

    private fun part(
        message: Message,
        mime: String,
    ): String {
        fun walk(mp: Multipart): String? {
            for (i in 0 until mp.count) {
                val body = mp.getBodyPart(i)
                if (body.isMimeType(mime)) return body.content.toString()
                (body.content as? Multipart)?.let { nested -> walk(nested)?.let { return it } }
            }
            return null
        }
        return checkNotNull(walk(message.content as Multipart)) { "no $mime part in '${message.subject}'" }
    }

    // ------------------------------------------------------------------------------- the product

    private fun session(jwt: String) = given().port(port).asSession(jwt)

    private fun createSchedule(
        name: String,
        pipeline: String,
        recipients: List<String>,
        events: List<String>? = null,
    ): String {
        val notifications = mutableMapOf<String, Any>("recipients" to recipients)
        events?.let { notifications["events"] = it }
        val body =
            mapOf(
                "name" to name,
                "payload" to mapOf("pipeline" to pipeline, "version" to "current"),
                "parameters" to mapOf("marker" to MARKER),
                "cron" to YEARLY,
                "timezone" to "UTC",
                "notifications" to notifications,
            )
        val created = session(ADMIN_SESSION).contentType(ContentType.JSON).body(mapper.writeValueAsString(body)).post("/api/v1/schedules")
        check(created.statusCode == 201) { "schedule $name → ${created.statusCode}: ${created.body().asString().take(DIAGNOSTIC_CHARS)}" }
        return created.jsonPath().getString("data.id")
    }

    private fun runNow(schedule: String): String {
        val accepted = session(ADMIN_SESSION).post("/api/v1/schedules/$schedule/run")
        check(accepted.statusCode == 202) { "Run now → ${accepted.statusCode}: ${accepted.body().asString().take(DIAGNOSTIC_CHARS)}" }
        return accepted.jsonPath().getString("data.id")
    }

    private fun runDetail(
        schedule: String,
        run: String,
    ): JsonNode = mapper.readTree(session(ADMIN_SESSION).get("/api/v1/schedules/$schedule/runs/$run").asString())["data"]

    private fun awaitRun(
        schedule: String,
        run: String,
    ): JsonNode = poll("run $run to reach a final state") { runDetail(schedule, run).takeIf { it["state"].asText() !in ACTIVE_STATES } }

    /** The run's execution id once its statement is running — the cancellation and the sweeper's write need it live. */
    private fun awaitExecution(
        schedule: String,
        run: String,
    ): String =
        poll("run $run to be running") {
            runDetail(schedule, run)
                .takeIf { it["state"].asText() == "running" }
                ?.get("execution_id")
                ?.asText()
        }

    private data class DeliveryRow(
        val id: String,
        val outcome: String,
        val recipient: String,
        val state: String,
        val attempts: Int,
        val code: String?,
        val messageId: String,
    )

    /** The run's deliveries once exactly [expected] exist and none is pending — the sender is done with them. */
    private fun awaitSettled(
        run: String,
        expected: Int,
    ): List<DeliveryRow> =
        poll("$expected settled deliveries of run $run") {
            deliveries(run).takeIf { rows -> rows.size == expected && rows.none { it.state == "pending" } }
        }

    private fun deliveries(run: String): List<DeliveryRow> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT d.id, e.outcome, d.recipient, d.state, d.attempts, d.last_error_code, d.message_id " +
                            "FROM schedule_notification_deliveries d JOIN schedule_notification_events e ON e.id = d.event_id " +
                            "WHERE e.run_id = '$run' ORDER BY e.sequence, d.recipient",
                    ).use { row ->
                        generateSequence {
                            if (!row.next()) {
                                null
                            } else {
                                DeliveryRow(
                                    row.getString(1),
                                    row.getString(2),
                                    row.getString(3),
                                    row.getString(4),
                                    row.getInt(5),
                                    row.getString(6),
                                    row.getString(7),
                                )
                            }
                        }.toList()
                    }
            }
        }

    private fun trailKinds(run: String): List<String> = runDetail(scheduleOf(run), run)["trail"].map { it["kind"].asText() }

    private fun scheduleOf(run: String): String = checkNotNull(scalar("SELECT schedule_id FROM schedule_runs WHERE id = '$run'"))

    private fun <T : Any> poll(
        what: String,
        probe: () -> T?,
    ): T =
        assertTimeoutPreemptively(Duration.ofSeconds(POLL_BUDGET_SECONDS), { "timed out waiting for $what" }) {
            var found: T? = null
            while (found == null) {
                found = probe()
                if (found == null) Thread.sleep(POLL_MILLIS)
            }
            found
        }

    // ------------------------------------------------------------------------------- fixtures

    private fun seed() {
        sql(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE_ID', '$WORKSPACE', 'Notification delivery E2E')",
        )
        sql(
            """
            INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES
                ('$ADMIN_ID', 'notify-admin@e2e.test', 'Notify Admin', 'test', 'notify-admin-sub', TRUE, TRUE),
                ('$AUTHOR_ID', 'notify-author@e2e.test', 'Notify Author', 'test', 'notify-author-sub', TRUE, FALSE),
                ('$VIEWER_ID', 'notify-viewer@e2e.test', 'Notify Viewer', 'test', 'notify-viewer-sub', TRUE, FALSE)
            """.trimIndent(),
        )
        sql(
            """
            INSERT INTO workspace_members (workspace_id, user_id, role) VALUES
                ('$WORKSPACE_ID', '$ADMIN_ID', 'workspace_admin'),
                ('$WORKSPACE_ID', '$AUTHOR_ID', 'author'),
                ('$WORKSPACE_ID', '$VIEWER_ID', 'viewer')
            """.trimIndent(),
        )
        DriverManager.getConnection(H2_JDBC_URL, H2_USER, H2_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE notify_rows (id INT PRIMARY KEY, label VARCHAR(64) NOT NULL)")
                (1..SEEDED_ROWS).forEach { statement.execute("INSERT INTO notify_rows VALUES ($it, 'row-$it')") }
                // A statement that runs long enough to be cancelled or swept while it runs.
                statement.execute("CREATE ALIAS NOTIFY_SLEEP FOR 'java.lang.Thread.sleep(long)'")
            }
        }
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .body(
                """
                {"name": "$H2_DATASOURCE", "display_name": "Notification E2E H2", "dialect": "H2",
                 "jdbc_url": "$H2_JDBC_URL", "username": "$H2_USER", "password": "$H2_PASSWORD"}
                """.trimIndent(),
            ).post("/api/v1/datasources")
            .then()
            .log()
            .ifValidationFails()
            .statusCode(201)
        TEMPLATES.forEach { (id, body) -> createTemplate(id, body) }
        createPipeline(OK_PIPELINE, OK_TEMPLATE)
        createPipeline(FAIL_PIPELINE, FAIL_TEMPLATE)
        createPipeline(SLOW_PIPELINE, SLOW_TEMPLATE)
    }

    private fun createTemplate(
        id: String,
        body: String,
    ) {
        val template =
            session(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(
                    mapper.writeValueAsString(
                        mapOf(
                            "id" to id,
                            "dialect" to "H2",
                            "display_name" to "Notification rows",
                            "description" to "Rows for the notification legs",
                            "imports" to emptyList<Any>(),
                            "body" to body,
                        ),
                    ),
                ).post("/api/v1/templates")
                .then()
                .statusCode(201)
                .extract()
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header(IF_MATCH, template.jsonPath().getString("data.body_hash"))
            .body("""{"name": "$id"}""")
            .post("/api/v1/templates/release")
            .then()
            .statusCode(200)
    }

    private fun createPipeline(
        name: String,
        template: String,
    ): String {
        val body =
            mapOf(
                "schema_version" to 1,
                "name" to name,
                "display_name" to "Notification $name",
                "description" to "A notification leg",
                "parameters" to
                    mapOf("marker" to mapOf("type" to "STRING", "required" to true, "description" to "A value no mail may carry.")),
                "nodes" to
                    listOf(
                        mapOf(
                            "id" to "rows",
                            "type" to "DQL",
                            "source" to H2_DATASOURCE,
                            "description" to "Reads the seeded rows",
                            "template" to mapOf("id" to template, "version" to 1),
                            "depends_on" to emptyList<String>(),
                        ),
                    ),
            )
        val created =
            session(ADMIN_SESSION)
                .contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(body))
                .post("/api/v1/pipelines")
                .then()
                .statusCode(201)
                .extract()
        val id = created.jsonPath().getString("data.id")
        session(ADMIN_SESSION)
            .contentType(ContentType.JSON)
            .header(IF_MATCH, created.jsonPath().getString("data.body_hash"))
            .post("/api/v1/pipelines/$id/release")
            .then()
            .statusCode(200)
        return id
    }

    private fun scalar(query: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().executeQuery(query).use { row -> if (row.next()) row.getString(1) else null }
        }

    private fun sql(statement: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute(statement) }
        }
    }

    /** Keeps the formatted text of every `event=` line the application logs — nothing else, so it stays small. */
    private class EventLines : AppenderBase<ILoggingEvent>() {
        val captured = CopyOnWriteArrayList<String>()

        override fun append(event: ILoggingEvent) {
            val line = event.formattedMessage ?: return
            if (line.contains("event=")) captured += line
        }
    }

    companion object {
        private const val SMTP_PORT = 3025
        private const val IMAP_PORT = 3143
        private const val IF_MATCH = "If-Match"
        private const val DIAGNOSTIC_CHARS = 2_000
        private const val POLL_BUDGET_SECONDS = 90L
        private const val POLL_MILLIS = 250L
        private val AWAIT: Duration = Duration.ofSeconds(60)
        private val ACTIVE_STATES = setOf("queued", "starting", "running")

        /** Never due during the suite: every run here is a Run now. */
        private const val YEARLY = "0 3 1 1 *"

        /** The link host every message must name — the configured base URL, never a request's host. */
        private const val BASE_URL = "https://scheduler-mail.e2e.test"
        private val LINK = Regex("""https?://[^\s"<]+/schedules\?id=[0-9a-f-]+&run=[0-9a-f-]+""")

        /** A parameter value the pipeline receives and no message may carry. */
        private const val MARKER = "marker-zq-never-mailed"

        /** Any address of the two test domains. */
        private val ADDRESS = Regex("""[A-Za-z0-9._%+-]+@(deliver|elsewhere)\.test""")
        private val SCHEDULE_ID = Regex("""schedule_id=[0-9a-f]{8}-[0-9a-f-]{27}""")
        private val RUN_ID = Regex("""run_id=[0-9a-f]{8}-[0-9a-f-]{27}""")

        private const val OPS = "ops@example.com"
        private const val LEAD = "lead@example.com"
        private const val FAIL = "fail@example.com"
        private const val REFUSED = "refused@example.com"
        private const val CANCEL = "cancel@example.com"
        private const val UNKNOWN = "unknown@example.com"
        private const val LISTED = "listed@example.com"
        private const val OUTSIDE = "outside@example.org"
        private const val TRANSIENT = "transient@example.com"
        private const val UNCERTAIN = "uncertain@example.com"
        private const val CRASH_PENDING = "crash-pending@example.com"
        private const val CRASH_CLAIMED = "crash-claimed@example.com"
        private val BOXES =
            listOf(OPS, LEAD, FAIL, REFUSED, CANCEL, UNKNOWN, LISTED, OUTSIDE, TRANSIENT, UNCERTAIN, CRASH_PENDING, CRASH_CLAIMED)

        private const val H2_DATASOURCE = "h2-notify-e2e"
        private const val H2_JDBC_URL = "jdbc:h2:mem:notifydeliverye2e;DB_CLOSE_DELAY=-1"
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = "sa"
        private const val SEEDED_ROWS = 5

        private const val OK_TEMPLATE = "test/notify_rows.sql"
        private const val FAIL_TEMPLATE = "test/notify_fails.sql"
        private const val SLOW_TEMPLATE = "test/notify_slow.sql"
        private val TEMPLATES =
            mapOf(
                OK_TEMPLATE to "SELECT id, label FROM notify_rows WHERE label <> :marker ORDER BY id",
                // Parses, plans, and fails on the first row: 'row-1' is no integer.
                FAIL_TEMPLATE to "SELECT CAST(label AS INT) AS n FROM notify_rows WHERE label <> :marker",
                // One row by its key, so the pause runs once.
                SLOW_TEMPLATE to "SELECT id FROM notify_rows WHERE id = 1 AND label <> :marker AND NOTIFY_SLEEP(8000) IS NULL",
            )
        private const val OK_PIPELINE = "test/notify_ok"
        private const val FAIL_PIPELINE = "test/notify_fails"
        private const val SLOW_PIPELINE = "test/notify_slow"
        private const val REFUSED_PIPELINE = "test/notify_discarded"

        private const val WORKSPACE = "notify-e2e"
        private val WORKSPACE_ID = UUID.randomUUID().toString()
        private val ADMIN_ID = UUID.randomUUID().toString()
        private val AUTHOR_ID = UUID.randomUUID().toString()
        private val VIEWER_ID = UUID.randomUUID().toString()

        private var okSchedule: String = ""
        private var okRun: String = ""

        /** Per-run secrets — registered below and used to sign every session (#215 B2). */
        private val JWT_SECRET = E2eSession.newSecret()
        private val ENCRYPTION_KEY = E2eSession.newSecret()
        private val ADMIN_SESSION get() = E2eSession.jwt(JWT_SECRET, ADMIN_ID, "notify-admin@e2e.test", WORKSPACE)
        private val AUTHOR_SESSION get() = E2eSession.jwt(JWT_SECRET, AUTHOR_ID, "notify-author@e2e.test", WORKSPACE)
        private val VIEWER_SESSION get() = E2eSession.jwt(JWT_SECRET, VIEWER_ID, "notify-viewer@e2e.test", WORKSPACE)

        private val postgres get() = SharedE2e.postgres
        private val redis get() = SharedE2e.redis
        private val oidc = OidcDiscoveryStub()
        private val lines = EventLines()

        /**
         * GreenMail 2.1.13 (MailNoticesE2eTest's image): SMTP 3025, IMAP 3143, auth disabled. Every
         * mailbox is PROVISIONED at start (#317: GreenMail's first-AUTH auto-create races itself), the
         * app's SMTP login `dp` included; the off-list box exists too, so its emptiness is the
         * application's doing, not the relay refusing an unknown user.
         */
        @JvmStatic
        val greenmail: GenericContainer<*> =
            GenericContainer("greenmail/standalone:2.1.13")
                .withExposedPorts(SMTP_PORT, IMAP_PORT)
                .withEnv(
                    "GREENMAIL_ADDITIONAL_OPTS",
                    "-Dgreenmail.users.login=email -Dgreenmail.users=dp:dp-any," +
                        BOXES.joinToString(",") { "${it.substringBefore('@')}:$it" },
                ).waitingFor(Wait.forListeningPorts(SMTP_PORT, IMAP_PORT).withStartupTimeout(Duration.ofMinutes(2)))

        private val interposer: SmtpInterposer

        init {
            greenmail.start()
            interposer = SmtpInterposer(greenmail.host, greenmail.getMappedPort(SMTP_PORT))
        }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("management.server.port") { "0" }
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("datapipelines.redis.password") { "" }
            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedE2e.redisPort }
            registry.add("datapipelines.jwt.secret") { JWT_SECRET }
            registry.add("datapipelines.db.encryption-key") { ENCRYPTION_KEY }
            registry.add("datapipelines.auth.oidc.providers[0].name") { "google" }
            registry.add("datapipelines.auth.oidc.providers[0].client-id") { "test-google-client-id" }
            registry.add("datapipelines.auth.oidc.providers[0].client-secret") { "test-google-client-secret" }
            registry.add("datapipelines.auth.oidc.providers[0].issuer-uri") { oidc.issuer }
            registry.add("datapipelines.auth.oidc.providers[0].display-name") { "Test google" }
            registry.add("datapipelines.auth.base-url") { BASE_URL }

            // Mail is configured (host + from), through the interposer in front of GreenMail.
            registry.add("datapipelines.mail.host") { "127.0.0.1" }
            registry.add("datapipelines.mail.port") { interposer.port }
            registry.add("datapipelines.mail.username") { "dp" }
            registry.add("datapipelines.mail.password") { "dp-any" }
            registry.add("datapipelines.mail.starttls") { false }
            registry.add("datapipelines.mail.from") { "Data Pipelines <noreply@datapipelines.test>" }

            // This context DISPATCHES on a one-second clock, and sends: the flag, an allowlist (the
            // development posture requires one), a one-second retry.
            registry.add("datapipelines.scheduler.enabled") { "true" }
            registry.add("datapipelines.scheduler.tick-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.polling-interval-seconds") { "1" }
            registry.add("datapipelines.scheduler.notifications.enabled") { "true" }
            registry.add("datapipelines.scheduler.notifications.recipient-domains") { "example.com" }
            registry.add("datapipelines.scheduler.notifications.retry-backoff-seconds") { "1" }
            registry.add("datapipelines.scheduler.notifications.max-attempts") { "3" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(lines)
            lines.stop()
            interposer.close()
            oidc.close()
            greenmail.stop()
        }
    }
}
