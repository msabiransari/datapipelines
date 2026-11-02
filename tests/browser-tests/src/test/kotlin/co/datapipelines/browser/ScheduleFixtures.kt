package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.sql.DriverManager
import java.util.UUID

/**
 * #9 slice 2 — fixtures for the Schedules suites. REST helpers use the browser's own session
 * and CSRF cookie (the in-page fetch [EditorRunFixtures] and `LifecycleDialogBrowserTest` use).
 * #764's membership and latest-run helpers seed the isolated [SharedBrowserE2e] database
 * directly; their callers then sign in fresh members and exercise the page.
 *
 * The pipeline a schedule runs is ONE `CALCULATOR` node with literal inputs: it needs no
 * datasource and no credential, finishes in milliseconds and still emits the full durable event
 * sequence (`execution_started` → `node_*` → `pipeline_completed`), which is what the merged
 * Messages pane reads. It declares one REQUIRED parameter so the form has a field to fill and
 * the binder has something to refuse.
 */
internal object ScheduleFixtures {
    const val HIDDEN_PIPELINE = "schedules764/hidden"

    /** #764: no existing controlled blocking executor; seed state words just like OverviewSchedulesE2eTest. */
    fun seedLastRun(
        scheduleId: String,
        state: String,
    ) {
        require(state in setOf("failed", "queued"))
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO schedule_runs (id, schedule_id, workspace_id, origin, scheduled_at, reference_at,
                        reference_timezone, admit_by, executor_id, payload_schema_version, payload_json, actor_user_id, state)
                    SELECT ?, id, workspace_id, 'cron', NOW(), NOW(), 'UTC', NOW(), 'pipeline', 1, '{}'::jsonb, created_by, ?
                      FROM schedules WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, state)
                    statement.setObject(3, UUID.fromString(scheduleId))
                    check(statement.executeUpdate() == 1) { "missing schedule $scheduleId for $state fixture" }
                }
        }
    }

    /** Membership in the walk's isolated workspace, granted before the member's first login. */
    fun seedMembership(
        workspace: String,
        email: String,
        role: String,
    ) {
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO workspace_members (workspace_id, user_id, role)
                    SELECT w.id, u.id, ? FROM workspaces w, users u WHERE w.name = ? AND u.email = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, role)
                    statement.setString(2, workspace)
                    statement.setString(3, email)
                    check(statement.executeUpdate() == 1) { "missing workspace/member for $role fixture" }
                }
        }
    }

    /** Loopback higher environment already holding [HIDDEN_PIPELINE] at its released version. */
    fun promotionTarget(key: String): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { server ->
            server.createContext("/api/v1/promotion/inventory") { exchange ->
                val workspace =
                    exchange.requestURI.rawQuery
                        .orEmpty()
                        .split('&')
                        .firstOrNull { it.startsWith("workspace=") }
                        ?.substringAfter('=')
                        ?.let { URLDecoder.decode(it, Charsets.UTF_8) }
                        .orEmpty()
                val admitted = exchange.requestHeaders.getFirst("DP-Promotion-Key") == key
                val body =
                    if (admitted) {
                        """{"schema_version":1,"correlation_id":"stub","data":{"deployment":"uat",""" +
                            """"authoring_enabled":false,"workspace":"$workspace","pipelines":[{"name":"$HIDDEN_PIPELINE",""" +
                            """"current_version":1,"body_hash":"hash-on-target"}],"templates":[],"datasources":[],"parameter_sets":[]}}"""
                    } else {
                        """{"error":{"code":"promotion.key_invalid","message":"bad key"}}"""
                    }
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (admitted) 200 else 401, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

    /** Once a year, 03:00 UTC on 1 January — never due while a suite runs. */
    const val YEARLY = "0 3 1 1 *"

    const val PARAMETER = "batch_size"

    /** The DATE parameter the bindings suites bind (slice 3). */
    const val DATE_PARAMETER = "as_of_date"

    data class Response(
        val status: Int,
        val body: String,
        val etag: String?,
    )

    @Suppress("UNCHECKED_CAST")
    fun send(
        page: Page,
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
        idempotencyKey: String? = null,
    ): Response {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'Content-Type': 'application/json', 'Accept': 'application/json',
                                   'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  if (args.key) headers['Idempotency-Key'] = args.key;
                  const res = await fetch(args.url, {method: args.method, credentials: 'same-origin', headers,
                                                     body: args.body ?? undefined});
                  return {status: res.status, body: await res.text(), etag: res.headers.get('ETag')};
                }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch, "key" to idempotencyKey),
            ) as Map<String, Any?>
        return Response((result["status"] as Number).toInt(), result["body"] as String? ?: "", result["etag"] as String?)
    }

    /** The first `"id"` in a §4.1 envelope — the object's own (the envelope has none; the object writes its id first). */
    fun idIn(body: String): String =
        requireNotNull(Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(body)) { "no id in: ${body.take(300)}" }.groupValues[1]

    private fun hashIn(body: String): String =
        requireNotNull(Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(body)) { "no body_hash in: ${body.take(300)}" }.groupValues[1]

    /** A released calculator pipeline named [name] (a folder path); returns its id. */
    fun releasedPipeline(
        page: Page,
        name: String,
    ): String {
        val created = createPipeline(page, name, "scheduler-2 fixture")
        check(created.status == 201) { "pipeline create ${created.status}: ${created.body.take(400)}" }
        return release(page, idIn(created.body))
    }

    /**
     * A calculator pipeline that was NEVER released (#280): the draft is live, `current_version`
     * is null — the shape `PipelineJobExecutor.validate` refuses with
     * `schedule.validation.target_not_released` and the form names at pick time. Returns its id.
     */
    fun draftPipeline(
        page: Page,
        name: String,
    ): String {
        val created = createPipeline(page, name, "scheduler-280 fixture")
        check(created.status == 201) { "pipeline create ${created.status}: ${created.body.take(400)}" }
        return idIn(created.body)
    }

    /** The calculator body both pipeline fixtures share (one required INTEGER parameter). */
    private fun createPipeline(
        page: Page,
        name: String,
        description: String,
    ): ScheduleFixtures.Response =
        send(
            page,
            "POST",
            "/api/v1/pipelines",
            """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"$description",""" +
                """"parameters":{"$PARAMETER":{"type":"INTEGER","required":true,"description":"How many rows one batch holds."}},""" +
                """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                """"inputs":{"date":"2026-08-14","fiscal_start":"09-15"}}]}""",
        )

    /**
     * A released pipeline declaring one REQUIRED DATE parameter ([DATE_PARAMETER]) whose
     * calculator node reads it (`$as_of_date`, the same reference form `$current_date` uses) —
     * the field the bindings suites bind and the node proves the bound value reached the run.
     */
    fun releasedDatePipeline(
        page: Page,
        name: String,
    ): String {
        val created =
            send(
                page,
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"scheduler-3 fixture",""" +
                    """"parameters":{"$DATE_PARAMETER":{"type":"DATE","required":true,""" +
                    """"description":"The logical day the run is for."}},""" +
                    """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"${'$'}$DATE_PARAMETER","fiscal_start":"01-01"}}]}""",
            )
        check(created.status == 201) { "pipeline create ${created.status}: ${created.body.take(400)}" }
        return release(page, idIn(created.body))
    }

    /** Releases [id] (the one precondition a schedule's `current` needs) and returns the id. */
    fun release(
        page: Page,
        id: String,
    ): String {
        val read = send(page, "GET", "/api/v1/pipelines/$id")
        val released = send(page, "POST", "/api/v1/pipelines/$id/release", ifMatch = hashIn(read.body))
        check(released.status == 200) { "pipeline release ${released.status}: ${released.body.take(400)}" }
        return id
    }

    /** A schedule over [pipeline] through §20.2; returns its id. */
    fun createSchedule(
        page: Page,
        name: String,
        pipeline: String,
        cron: String = YEARLY,
        timezone: String = "UTC",
        batchSize: Int = 10,
    ): String {
        val created =
            send(
                page,
                "POST",
                "/api/v1/schedules",
                """{"name":"$name","payload":{"pipeline":"$pipeline","version":"current"},""" +
                    """"parameters":{"$PARAMETER":$batchSize},"cron":"$cron","timezone":"$timezone"}""",
            )
        check(created.status == 201) { "schedule create ${created.status}: ${created.body.take(400)}" }
        return idIn(created.body)
    }

    /**
     * A schedule whose payload binds [DATE_PARAMETER] to the [keyword] (slice 3, §20.2) — the
     * save passes with no literal parameters at all: the placeholder satisfies the required DATE.
     */
    fun createBoundSchedule(
        page: Page,
        name: String,
        pipeline: String,
        keyword: String = "TODAY",
        cron: String = YEARLY,
        timezone: String = "America/New_York",
    ): String {
        val created =
            send(
                page,
                "POST",
                "/api/v1/schedules",
                """{"name":"$name","payload":{"pipeline":"$pipeline","version":"current",""" +
                    """"parameter_bindings":{"$DATE_PARAMETER":{"source":"keyword","name":"$keyword"}}},""" +
                    """"cron":"$cron","timezone":"$timezone"}""",
            )
        check(created.status == 201) { "schedule create ${created.status}: ${created.body.take(400)}" }
        return idIn(created.body)
    }

    /** §20.4's ETag — the revision an edit or a delete must carry. */
    fun etag(
        page: Page,
        scheduleId: String,
    ): String = requireNotNull(send(page, "GET", "/api/v1/schedules/$scheduleId").etag) { "no ETag on §20.4" }
}
