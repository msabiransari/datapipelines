package co.datapipelines.browser

import com.microsoft.playwright.Page

/**
 * #9 slice 2 — REST-seeded fixtures for the Schedules suites, through the browser's OWN session
 * and CSRF cookie (the in-page fetch [EditorRunFixtures] and `LifecycleDialogBrowserTest` use),
 * so nothing here bypasses the product's authorization.
 *
 * The pipeline a schedule runs is ONE `CALCULATOR` node with literal inputs: it needs no
 * datasource and no credential, finishes in milliseconds and still emits the full durable event
 * sequence (`execution_started` → `node_*` → `pipeline_completed`), which is what the merged
 * Messages pane reads. It declares one REQUIRED parameter so the form has a field to fill and
 * the binder has something to refuse.
 */
internal object ScheduleFixtures {
    /** Once a year, 03:00 UTC on 1 January — never due while a suite runs. */
    const val YEARLY = "0 3 1 1 *"

    const val PARAMETER = "batch_size"

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

    private fun idIn(body: String): String =
        requireNotNull(Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(body)) { "no id in: ${body.take(300)}" }.groupValues[1]

    private fun hashIn(body: String): String =
        requireNotNull(Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(body)) { "no body_hash in: ${body.take(300)}" }.groupValues[1]

    /** A released calculator pipeline named [name] (a folder path); returns its id. */
    fun releasedPipeline(
        page: Page,
        name: String,
    ): String {
        val created =
            send(
                page,
                "POST",
                "/api/v1/pipelines",
                """{"name":"$name","display_name":"${name.substringAfterLast('/')}","description":"scheduler-2 fixture",""" +
                    """"parameters":{"$PARAMETER":{"type":"INTEGER","required":true,"description":"How many rows one batch holds."}},""" +
                    """"nodes":[{"id":"fq","type":"CALCULATOR","kind":"fiscal_quarter","context_key":"run_fiscal_quarter",""" +
                    """"inputs":{"date":"2026-08-14","fiscal_start":"09-15"}}]}""",
            )
        check(created.status == 201) { "pipeline create ${created.status}: ${created.body.take(400)}" }
        val id = idIn(created.body)
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

    /** §20.4's ETag — the revision an edit or a delete must carry. */
    fun etag(
        page: Page,
        scheduleId: String,
    ): String = requireNotNull(send(page, "GET", "/api/v1/schedules/$scheduleId").etag) { "no ETag on §20.4" }
}
