package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager

/**
 * #1040 (published-mutation-apis §3, §7) — authoring a published mutation in a real browser, then calling it from an
 * external HTTP client through the application's own listener (the supported ingress; no proxy is bundled):
 *
 *  1. a GET and a POST at ONE path, each over its own pipeline: the POST's version page shows its method, exact pin, the
 *     request-body mode and the transformer that reads the body, and names the keys granted POST on the route; the GET's
 *     shows no body and the keys bound at the path; both release through the page's dialog; the draft dialog shows the
 *     route's fixed method and each pickable version's body contract BEFORE a save;
 *  2. the Keys page grants a write-only key exactly the mutation routes (one checkbox per route and method) — the
 *     database holds exact grants and no GET node; a GET-only key minted on the same page reads and cannot mutate;
 *  3. the external client posts a chunked nested order and polls its own run (another key's poll is not found), repeats
 *     a PUT, deletes with a declared body and without one, and follows a 202 to completion; un-ticking one grant on the
 *     Keys page refuses exactly that method (405, Allow naming the rest) and leaves the sibling method served; deleting
 *     the key on the page ends its access.
 */
class PublishedMutationAuthoringBrowserTest : BrowserSuite() {
    @Test
    fun `a GET and a POST at one path - authored, granted on the Keys page, and called from an external client`() {
        startTrace()
        val suffix = generatedPassword("s").take(8).lowercase().filter { it.isLetterOrDigit() }
        val user = seedLocalUser(uniqueEmail("pm1040-$suffix"), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("pm1040-$suffix")
        page.navigate("$baseUrl/api-console")
        val fx = PublishedMutationAuthoringFixtures(page, baseUrl, suffix)
        val ids = seed(fx, suffix)

        walkVersionPages(ids, suffix)

        // 2 — the Keys page: a write-only key granted exactly the mutation routes; a GET-only key bound at the node.
        page.navigate("$baseUrl/api-keys")
        val writer = mint("pm-writer", bind = null)
        val reader = mint("pm-reader", bind = "/pm1040")
        val routes = listOf("POST $ORDERS", "DELETE $ORDERS", "PUT $STATE", "DELETE $STATE", "POST $SLOW")
        editGrants("pm-writer") { form -> routes.forEach { form.locator("input[name=grants][value=\"$it\"]").check() } }
        withClue("the database holds exact method grants, and no GET node, for the write-only key") {
            grantsOf(writer) shouldBe routes.sorted()
        }
        assertConsoleKeyAssignments(ids)

        // 3 — the external client.
        callRoutes(fx, writer, reader)
        page.navigate("$baseUrl/executions")
        page.locator("#execution-table").innerText().shouldContain("pm-reader (API key)")

        // Un-tick ONE grant: exactly that method is refused, the sibling method at the path is still served.
        page.navigate("$baseUrl/api-keys")
        editGrants("pm-writer") { form -> form.locator("input[name=grants][value=\"DELETE $STATE\"]").uncheck() }
        grantsOf(writer) shouldBe (routes - "DELETE $STATE").sorted()
        val refused = fx.call(writer, "DELETE", "/api$STATE", "pm-delete-revoked")
        withClue(refused.body()) {
            refused.statusCode() shouldBe 405
            refused.headers().firstValue("Allow").orElse("") shouldBe "PUT"
        }
        withClue(
            "the PUT grant at the same path is untouched",
        ) { fx.call(writer, "PUT", "/api$STATE", "pm-put-3").statusCode() shouldBe 200 }

        // Deleting the key on the page ends its access (bounded wait on a read of its own run — never a write).
        page.onceDialog { it.accept() }
        page.waitForResponse({ it.url().contains("/partials/api-keys/") && it.request().method() == "DELETE" }) {
            rowFor("pm-writer").locator("[data-verb=key-revoke]").click()
        }
        awaitUnauthorized(fx, writer)

        mint("pm-nearest", bind = ORDERS)
        page.navigate("$baseUrl/api-console")
        val readKeys = consoleKeys(ids.getValue("GET $ORDERS"))
        readKeys shouldContain "pm-nearest"
        readKeys shouldNotContain "pm-reader"
        readKeys shouldNotContain "Inherited from"
    }

    private fun assertConsoleKeyAssignments(ids: Map<String, String>) {
        page.navigate("$baseUrl/api-console")
        val readKeys = consoleKeys(ids.getValue("GET $ORDERS"))
        readKeys shouldContain "pm-reader"
        readKeys shouldContain "Inherited from /api/pm1040"
        readKeys shouldNotContain "pm-writer"

        val writeKeys = consoleKeys(ids.getValue("POST $ORDERS"))
        writeKeys shouldContain "pm-writer"
        writeKeys shouldNotContain "pm-reader"
        writeKeys shouldNotContain "Inherited from"
    }

    @Test
    fun `the console shows path parameter bindings and their fallback for other values`() {
        startTrace()
        val suffix = generatedPassword("s").take(8).lowercase().filter { it.isLetterOrDigit() }
        val user = seedLocalUser(uniqueEmail("api-regions-$suffix"), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("api-regions-$suffix")
        val fx = PublishedMutationAuthoringFixtures(page, baseUrl, suffix)
        val prefix = "/reports-$suffix/v1"
        val summaryId = seedRegionalRoutes(fx, prefix, suffix)

        page.navigate("$baseUrl/api-console")
        consoleKeys(summaryId) shouldContain "none"
        page.navigate("$baseUrl/api-keys")
        val east = mint("east-reader", bind = "$prefix/east")
        fx.call(east, "GET", "/api$prefix/east/summary").statusCode() shouldBe 200
        fx.call(east, "GET", "/api$prefix/west/summary").statusCode() shouldBe 403

        page.navigate("$baseUrl/api-console")
        val eastOnly = consoleKeys(summaryId)
        eastOnly shouldContain "east-reader"
        eastOnly shouldContain "region = east"
        eastOnly shouldContain "/api$prefix/east"
        eastOnly shouldNotContain "none"

        page.navigate("$baseUrl/api-keys")
        val all = mint("regional-reader", bind = prefix)
        fx.call(all, "GET", "/api$prefix/east/summary").statusCode() shouldBe 403
        fx.call(all, "GET", "/api$prefix/west/summary").statusCode() shouldBe 200

        page.navigate("$baseUrl/api-console")
        val both = consoleKeys(summaryId)
        both shouldContain "east-reader"
        both shouldContain "region = east"
        both shouldContain "regional-reader"
        both shouldContain "Other values"

        page.navigate("$baseUrl/api-keys")
        val eastSummary = mint("east-summary-reader", bind = "$prefix/east/summary")
        fx.call(eastSummary, "GET", "/api$prefix/east/summary").statusCode() shouldBe 200
        fx.call(east, "GET", "/api$prefix/east/summary").statusCode() shouldBe 403
        page.navigate("$baseUrl/api-console")
        val deeper = consoleKeys(summaryId)
        deeper shouldContain "east-summary-reader"
        deeper shouldContain "/api$prefix/east/summary"
        deeper shouldNotContain "east-reader"
    }

    private fun seedRegionalRoutes(
        fx: PublishedMutationAuthoringFixtures,
        prefix: String,
        suffix: String,
    ): String {
        fx.seedTarget()
        val templateId = template(suffix, "regions")
        val name = pipeline(suffix, "regions")
        fx.sqlTemplate(templateId, "SELECT 1 AS n")
        fx.pipeline(
            name,
            "[${dql("summary", templateId, fx.datasource)}]",
            parameters = """{"region":{"type":"STRING","required":false}}""",
        )
        val summary = fx.publish("$prefix/{region}/summary", "GET", name)
        fx.publish("$prefix/east/detail", "GET", name)
        fx.publish("$prefix/east/summary/leaf", "GET", name)
        fx.releaseOverRest(summary)
        return summary
    }

    private fun consoleKeys(endpointId: String): String =
        page.locator("tr:has(a[href='/api-console/endpoints/$endpointId']) td").last().innerText()

    /** Step 1 — the POST and GET version pages at one path: method, exact pin, body contract, keys label; both released. */
    private fun walkVersionPages(
        ids: Map<String, String>,
        suffix: String,
    ) {
        // 1 — the POST's version page: method, exact pin, body contract and the keys label, before anything is released.
        page.setViewportSize(1440, 900)
        page.navigate("$baseUrl/api-console/endpoints/${ids.getValue("POST $ORDERS")}")
        withClue("the POST route's page shows what a caller must send, and which transformer reads it") {
            page.locator("[data-api-method]").innerText() shouldBe "POST"
            page.locator("[data-api-pin]").innerText() shouldBe "${pipeline(suffix, "orders")}@1"
            page.locator("[data-api-body-mode]").innerText() shouldBe "required"
            page.locator("[data-api-body-readers]").innerText() shouldContain "shape (${template(suffix, "lines")}@1)"
            page.locator("[data-api-keys-label]").innerText() shouldBe "API keys granted POST on this route:"
        }
        openDialog("release", "endpoint-release")
        submitAndWaitForPage("api_released")
        // A new draft (offered once nothing is in draft) starts from the released current version.
        openDialog("new-draft", "endpoint-draft")
        withClue("the draft dialog: the method is the route's, fixed; each version names its body contract") {
            page.locator("#ax-dialog [data-api-draft-method]").innerText() shouldBe "POST"
            page.locator("#ax-dialog [data-api-pipeline-version]").innerText() shouldContain
                "body required · read by shape (${template(suffix, "lines")}@1)"
        }
        page.locator("#ax-dialog [data-lifecycle-close]").first().click()

        page.navigate("$baseUrl/api-console/endpoints/${ids.getValue("GET $ORDERS")}")
        withClue("the GET sibling at the same path: its own pin, no body, the keys bound at the path") {
            page.locator("[data-api-method]").innerText() shouldBe "GET"
            page.locator("[data-api-pin]").innerText() shouldBe "${pipeline(suffix, "read")}@1"
            page.locator("[data-api-body-mode]").innerText() shouldBe "none"
            page.locator("[data-api-keys-label]").innerText() shouldBe "API keys bound at this path:"
        }
        openDialog("release", "endpoint-release")
        submitAndWaitForPage("api_released")
    }

    private fun seed(
        fx: PublishedMutationAuthoringFixtures,
        suffix: String,
    ): Map<String, String> {
        fx.seedTarget()
        val ds = fx.datasource
        fx.linesTemplate(template(suffix, "lines"))
        fx.sqlTemplate(template(suffix, "insert"), insertInto("pm_lines"))
        fx.sqlTemplate(template(suffix, "tombstone"), insertInto("pm_tombstones"))
        fx.sqlTemplate(template(suffix, "read"), "SELECT count(*) AS n FROM pm_lines")
        fx.sqlTemplate(template(suffix, "put"), "UPDATE pm_state SET state = 'set' WHERE name = 'put-target'")
        fx.sqlTemplate(template(suffix, "remove"), "DELETE FROM pm_state WHERE name = 'delete-target'")
        fx.sqlTemplate(template(suffix, "slow"), "SELECT 1 AS n FROM pg_sleep($SLOW_SECONDS)")
        fx.pipeline(pipeline(suffix, "read"), "[${dql("read", template(suffix, "read"), ds)}]")
        fx.pipeline(pipeline(suffix, "orders"), bodyNodes(suffix, "insert", ds), """{"required":true}""")
        fx.pipeline(pipeline(suffix, "tombstones"), bodyNodes(suffix, "tombstone", ds), """{"required":true}""")
        fx.pipeline(pipeline(suffix, "put"), "[${dml("write", template(suffix, "put"), ds, "update")}]")
        fx.pipeline(pipeline(suffix, "remove"), "[${dml("write", template(suffix, "remove"), ds, "delete")}]")
        fx.pipeline(pipeline(suffix, "slow"), "[${dql("slow", template(suffix, "slow"), ds)}]")
        val ids =
            mapOf(
                "GET $ORDERS" to fx.publish(ORDERS, "GET", pipeline(suffix, "read")),
                "POST $ORDERS" to fx.publish(ORDERS, "POST", pipeline(suffix, "orders")),
                "DELETE $ORDERS" to fx.publish(ORDERS, "DELETE", pipeline(suffix, "tombstones")),
                "PUT $STATE" to fx.publish(STATE, "PUT", pipeline(suffix, "put")),
                "DELETE $STATE" to fx.publish(STATE, "DELETE", pipeline(suffix, "remove")),
                "POST $SLOW" to fx.publish(SLOW, "POST", pipeline(suffix, "slow"), timeoutSeconds = 1),
            )
        ids.filterKeys { it != "GET $ORDERS" && it != "POST $ORDERS" }.values.forEach { fx.releaseOverRest(it) }
        return ids
    }

    private fun callRoutes(
        fx: PublishedMutationAuthoringFixtures,
        writer: String,
        reader: String,
    ) {
        withClue("the GET-only key reads the GET route") { fx.call(reader, "GET", "/api$ORDERS").statusCode() shouldBe 200 }
        val notGranted = fx.call(reader, "POST", "/api$ORDERS", "pm-reader-post", json = true, body = ofString(order("RD-1")))
        withClue("a GET-only key cannot mutate: ${notGranted.body()}") {
            notGranted.statusCode() shouldBe 405
            notGranted.headers().firstValue("Allow").orElse("") shouldBe "GET"
            fx.count("SELECT count(*) FROM pm_lines WHERE sku = 'RD-1'") shouldBe 0
        }

        // A nested order streamed CHUNKED (no Content-Length): both lines arrive, as sent.
        val nested = """{"order_no":"PM-1","lines":[{"sku":"PM-1","qty":2},{"sku":"PM-1B","qty":1}]}"""
        val chunked = HttpRequest.BodyPublishers.fromPublisher(ofString(nested))
        val posted = fx.call(writer, "POST", "/api$ORDERS", "pm-post-1", chunked, json = true)
        withClue(posted.body()) { posted.statusCode() shouldBe 200 }
        fx.count("SELECT count(*) FROM pm_lines WHERE (sku, qty) IN (('PM-1', 2), ('PM-1B', 1))") shouldBe 2
        val statusUrl = field(posted, "status_url")
        withClue("the originating grant polls its run; another key of the workspace gets not-found") {
            fx.call(writer, "GET", statusUrl).statusCode() shouldBe 200
            fx.call(writer, "GET", field(posted, "result_url")).statusCode() shouldBe 200
            fx.call(reader, "GET", statusUrl).statusCode() shouldBe 404
        }

        val puts = listOf("pm-put-1", "pm-put-2").map { fx.call(writer, "PUT", "/api$STATE", it) }
        withClue("a repeated PUT: two runs, one final state") {
            puts.map { it.statusCode() } shouldBe listOf(200, 200)
            puts.map { field(it, "execution_id") }.toSet().size shouldBe 2
            fx.count("SELECT count(*) FROM pm_state WHERE name = 'put-target' AND state = 'set'") shouldBe 1
        }
        val bodyless = fx.call(writer, "DELETE", "/api$STATE", "pm-delete-1")
        withClue("a bodyless DELETE: ${bodyless.body()}") { bodyless.statusCode() shouldBe 200 }
        fx.count("SELECT count(*) FROM pm_state WHERE name = 'delete-target'") shouldBe 0
        val declared = fx.call(writer, "DELETE", "/api$ORDERS", "pm-delete-2", ofString(order("PM-2")), json = true)
        withClue("a DELETE with a declared body: ${declared.body()}") { declared.statusCode() shouldBe 200 }
        fx.count("SELECT count(*) FROM pm_tombstones WHERE sku = 'PM-2'") shouldBe 1

        val accepted = fx.call(writer, "POST", "/api$SLOW", "pm-slow-1")
        withClue("past the route's timeout: 202 with its status URL — ${accepted.body()}") { accepted.statusCode() shouldBe 202 }
        awaitStatus(fx, writer, field(accepted, "status_url"), "SUCCESS")
    }

    private fun mint(
        name: String,
        bind: String?,
    ): String {
        page.click("text=New API key")
        page.check("#createKeyForm input[name=kind][value=endpoint]")
        page.fill("#key-name", name)
        bind?.let { page.check("#createKeyForm input[name=bindings][value=\"$it\"]") }
        page.waitForResponse("**/partials/api-keys") { page.click("#createKeyForm button[type=submit]") }.status() shouldBe 200
        page.waitForSelector("#keyCreated")
        val credential = page.locator("#keyCreated input[type=text]").inputValue()
        // A fresh page for the next step: the once-shown panel is never needed again.
        page.reload()
        page.waitForSelector("#keys-table")
        return credential
    }

    private fun editGrants(
        name: String,
        change: (com.microsoft.playwright.Locator) -> Unit,
    ) {
        val row = rowFor(name)
        row.locator("details.app-assoc summary").first().click()
        val form = row.locator("form.app-assoc-form").first()
        change(form)
        page
            .waitForResponse({ it.url().contains("/bindings") && it.request().method() == "POST" }) {
                form.locator("button[type=submit]").click()
            }.status() shouldBe 200
        rowFor(name).waitFor()
    }

    private fun rowFor(name: String) = page.locator("#keys-table-body tr", Page.LocatorOptions().setHasText(name)).first()

    /** The key's grants and bindings as stored — the second client's read of the metadata database. */
    private fun grantsOf(credential: String): List<String> =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.prepareStatement("SELECT method || ' ' || path_prefix FROM endpoint_key_bindings WHERE api_key_id = ?").use { s ->
                s.setString(1, credential.substringBefore('.'))
                s.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }.sorted()
            }
        }

    private fun awaitStatus(
        fx: PublishedMutationAuthoringFixtures,
        credential: String,
        url: String,
        status: String,
    ) {
        val deadline = System.nanoTime() + AWAIT_NANOS
        var last = ""
        while (System.nanoTime() < deadline) {
            last = fx.call(credential, "GET", url).body()
            if (Regex(""""status"\s*:\s*"$status"""").containsMatchIn(last)) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("never reached $status: $last")
    }

    private fun awaitUnauthorized(
        fx: PublishedMutationAuthoringFixtures,
        credential: String,
    ) {
        val deadline = System.nanoTime() + AWAIT_NANOS
        while (System.nanoTime() < deadline) {
            if (fx.call(credential, "GET", "/api$ORDERS").statusCode() == 401) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("a deleted key still authenticated after the bound")
    }

    private fun openDialog(
        verb: String,
        dialog: String,
    ) {
        page.waitForResponse({ it.url().contains("/lifecycle/") }) { page.locator("[data-api-verb='$verb']").click() }
        page.locator("#ax-dialog [data-lifecycle-dialog='$dialog']").waitFor()
    }

    private fun submitAndWaitForPage(code: String) {
        page.locator("#ax-dialog [data-api-submit]").click()
        page.waitForURL("**ok=$code*")
    }

    private fun field(
        response: HttpResponse<String>,
        name: String,
    ): String =
        requireNotNull(Regex(""""$name"\s*:\s*"([^"]+)"""").find(response.body())) { "no $name in ${response.body()}" }.groupValues[1]

    private fun ofString(text: String) = HttpRequest.BodyPublishers.ofString(text)

    private fun order(sku: String) = """{"order_no":"$sku","lines":[{"sku":"$sku","qty":1,"note":{"gift":true}}]}"""

    private fun template(
        suffix: String,
        name: String,
    ) = "test/pm1040_${suffix}_$name" + if (name == "lines") ".jsonata" else ".sql"

    private fun pipeline(
        suffix: String,
        name: String,
    ) = "test/pm1040_${suffix}_$name"

    private fun insertInto(table: String) =
        "INSERT INTO $table (sku, qty, ext_id, ext_number) " +
            "VALUES (CAST(:sku AS text), CAST(:qty AS int), CAST(:ext_id AS text), CAST(:ext_number AS text))"

    private fun dql(
        id: String,
        template: String,
        ds: String,
    ) = """{"id":"$id","description":"Reads","type":"DQL","source":"$ds","template":{"id":"$template","version":1},""" +
        """"output":{"target":"caller"},"depends_on":[]}"""

    private fun dml(
        id: String,
        template: String,
        ds: String,
        operation: String,
    ) = """{"id":"$id","description":"One statement","type":"DML","source":"$ds","template":{"id":"$template","version":1},""" +
        """"operation":"$operation","output":{"target":"caller"},"depends_on":[]}"""

    /** The body reader (`shape`, the transformer) and the insert that writes its rows. */
    private fun bodyNodes(
        suffix: String,
        insert: String,
        ds: String,
    ) = """[{"id":"shape","description":"Turns the request body into lines","type":"SCRIPT",""" +
        """"template":{"id":"${template(suffix, "lines")}","version":1},"inputs":{"order":"@request_body"},""" +
        """"output":{"target":"tempdb","table":"lines_in"},"depends_on":[]},""" +
        """{"id":"insert","description":"Writes the lines","type":"DML","source":"$ds",""" +
        """"template":{"id":"${template(suffix, insert)}","version":1},"operation":"insert",""" +
        """"key":{"column":"line_id","type":"INTEGER"},"input":{"table":"lines_in"},"output":{"target":"caller"},""" +
        """"depends_on":["shape"]}]"""

    private companion object {
        const val ORDERS = "/pm1040/v1/orders"
        const val STATE = "/pm1040/v1/state"
        const val SLOW = "/pm1040/v1/slow"
        const val SLOW_SECONDS = 3
        const val POLL_MS = 500L
        const val AWAIT_NANOS = 90_000_000_000L

        /** Serving mutation routes is off by default (configuration.md §3.22); this class's context turns it on. */
        @DynamicPropertySource
        @JvmStatic
        fun mutations(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.endpoints.mutations-enabled") { "true" }
        }
    }
}
