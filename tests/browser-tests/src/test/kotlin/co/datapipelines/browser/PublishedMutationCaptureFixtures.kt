package co.datapipelines.browser

import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration

/**
 * [PublishedMutationCaptureBrowserTest]'s own fixtures (owner ruling 2026-11-08: one browser class per family, its
 * fixtures in its own helper). Seeding goes through the product's REST and form routes from inside the signed-in page
 * (session cookie + the CSRF header) — the PublishedMutationAuthoringFixtures pattern; the CALLER of the published
 * route is an external `java.net.http` client against the application's own listener.
 *
 * The route: `POST` over a pipeline whose value-mode transformer `pick` keeps `inputs.order.selected` as `picked`, and
 * whose DML node writes it to a scratch database of the suite's shared Postgres server — this class's alone.
 */
class PublishedMutationCaptureFixtures(
    private val page: Page,
    private val baseUrl: String,
    private val suffix: String,
) {
    private val database = "cap1043_$suffix"
    private val datasource = "cap1043-$suffix"
    private val targetUrl = SharedBrowserE2e.jdbcUrl.substringBefore("?").substringBeforeLast("/") + "/$database"
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    /** Seeds the target, the two templates and the pipeline, publishes `POST` [path] and releases it; the entity id. */
    fun seedRoute(path: String): String {
        seedTarget()
        val pipeline = seedPipeline()
        val (published, publishedBody) =
            send(
                "POST",
                "/api/v1/endpoints",
                """{"path":"$path","method":"POST","pipeline":"$pipeline","pipeline_version":1,"timeout_seconds":60}""",
            )
        withClue("publish POST $path: $publishedBody") { published shouldBe 201 }
        val endpointId = idOf(publishedBody)
        send("POST", "/api/v1/endpoints/$endpointId/release", ifMatch = hashOf(send("GET", "/api/v1/endpoints/$endpointId/versions/1")))
            .first shouldBe 200
        return endpointId
    }

    /** The scratch target database with its one table, registered as this class's datasource. */
    private fun seedTarget() {
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { it.execute("""CREATE DATABASE "$database"""") }
        }
        DriverManager.getConnection(targetUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE cap_rows (picked text NOT NULL)") }
        }
        val form =
            mapOf(
                "name" to datasource,
                "displayName" to "Capture target",
                "dialect" to "POSTGRES",
                "jdbcUrl" to targetUrl,
                "credentialKind" to "password",
                "username" to SharedBrowserE2e.username,
                "password" to SharedBrowserE2e.password,
                "description" to "#1043 browser write target",
            ) + SharedBrowserE2e.SOURCE_DATASOURCE_POOL_FIELDS
        withClue("datasource registration") { send("POST", "/partials/datasources", form = form).first shouldBe 200 }
    }

    /** The two released templates and the released v1 of the pipeline over them; the pipeline's name. */
    private fun seedPipeline(): String {
        val pick = "test/cap1043_${suffix}_pick.jsonata"
        val write = "test/cap1043_${suffix}_write.sql"
        releaseTemplate(
            pick,
            """{"id":"$pick","display_name":"$pick","description":"#1043 value transformer","type":"jsonata",""" +
                """"body":"inputs.order.selected","contract":$VALUE_CONTRACT,"invariants":[],""" +
                """"tests":[{"name":"selects","input":{"inputs":{"order":{"selected":"s"}}},"expect":{"output":"s"}}]}""",
        )
        releaseTemplate(
            write,
            """{"id":"$write","dialect":"POSTGRES","display_name":"$write","description":"#1043 fixture","imports":[],""" +
                """"body":"INSERT INTO cap_rows (picked) VALUES (CAST(:picked AS text))"}""",
        )
        val pipeline = "test/cap1043_${suffix}_orders"
        val nodes =
            """[{"id":"pick","description":"Selects one body field","type":"SCRIPT",""" +
                """"template":{"id":"$pick","version":1},"inputs":{"order":"@request_body"},"context_key":"picked",""" +
                """"depends_on":[]},""" +
                """{"id":"write","description":"Writes the selected value","type":"DML","source":"$datasource",""" +
                """"template":{"id":"$write","version":1},"operation":"update","output":{"target":"discard"},""" +
                """"depends_on":["pick"]}]"""
        val (created, createdBody) =
            send(
                "POST",
                "/api/v1/pipelines",
                """{"name":"$pipeline","display_name":"orders","description":"#1043 fixture","parameters":{},""" +
                    """"nodes":$nodes,"request_body":{"required":true}}""",
            )
        withClue("create $pipeline: $createdBody") { created shouldBe 201 }
        val pipelineId = idOf(createdBody)
        send("POST", "/api/v1/pipelines/$pipelineId/release", ifMatch = hashOf(send("GET", "/api/v1/pipelines/$pipelineId")))
            .first shouldBe 200
        return pipeline
    }

    /** Mints an endpoint key granted exactly `POST` [path]; its credential (the REST routes the Keys page uses). */
    fun mintWriter(path: String): String {
        val (status, body) = send("POST", "/api/v1/auth/api-keys", """{"name":"cap1043-writer","kind":"endpoint","bindings":[]}""")
        withClue("mint: $body") { status shouldBe 201 }
        val id = field(requireNotNull(body), "id")
        val credential = field(body, "key")
        val (granted, grantBody) =
            send("POST", "/api/v1/endpoints/bindings", """{"api_key_id":"$id","path_prefix":"$path","method":"POST"}""")
        withClue("grant: $grantBody") { granted shouldBe 201 }
        return credential
    }

    /** One external call of the published route with a JSON [body]. */
    fun post(
        credential: String,
        path: String,
        idempotencyKey: String,
        body: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("$baseUrl/api$path"))
                .timeout(Duration.ofSeconds(CLIENT_TIMEOUT_S))
                .header("DP-API-Key", credential)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    /** One count over the scratch database — the second client's view of what the route wrote. */
    fun count(sql: String): Int =
        DriverManager.getConnection(targetUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    /** One scalar from the metadata database — the stored capture decision and the private rows. */
    fun metadata(sql: String): String =
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun releaseTemplate(
        id: String,
        payload: String,
    ) {
        val (status, body) = send("POST", "/api/v1/templates", payload)
        withClue("template $id: $body") { status shouldBe 201 }
        send("POST", "/api/v1/templates/release", """{"name":"$id"}""", ifMatch = hashOf(200 to body)).first shouldBe 200
    }

    /** The first UUID-shaped `id` — a pipeline's node ids (`pick`, `write`) are `id`s too. */
    private fun idOf(body: String?): String =
        requireNotNull(Regex(""""id"\s*:\s*"([0-9a-f-]{36})"""").find(requireNotNull(body))) { "no entity id in $body" }.groupValues[1]

    private fun field(
        body: String,
        name: String,
    ): String = requireNotNull(Regex(""""$name"\s*:\s*"([^"]+)"""").find(body)) { "no $name in $body" }.groupValues[1]

    private fun hashOf(response: Pair<Int, String?>): String {
        response.first shouldBe 200
        return requireNotNull(Regex(""""body_hash"\s*:\s*"([0-9a-f]+)"""").find(requireNotNull(response.second))).groupValues[1]
    }

    @Suppress("UNCHECKED_CAST")
    private fun send(
        method: String,
        url: String,
        body: String? = null,
        ifMatch: String? = null,
        form: Map<String, String>? = null,
    ): Pair<Int, String?> {
        val result =
            page.evaluate(
                """async (args) => {
                  const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
                  const headers = {'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : ''};
                  let payload = args.body ?? undefined;
                  if (args.form) {
                    headers['Content-Type'] = 'application/x-www-form-urlencoded';
                    payload = new URLSearchParams(args.form);
                  } else {
                    headers['Content-Type'] = 'application/json';
                  }
                  if (args.ifMatch) headers['If-Match'] = args.ifMatch;
                  const res = await fetch(args.url, {method: args.method, credentials: 'same-origin', headers, body: payload});
                  const text = await res.text();
                  return {status: res.status, body: text.length < 8192 ? text : null};
               }""",
                mapOf("method" to method, "url" to url, "body" to body, "ifMatch" to ifMatch, "form" to form),
            ) as Map<String, Any?>
        return (result["status"] as Number).toInt() to (result["body"] as String?)
    }

    private companion object {
        const val CLIENT_TIMEOUT_S = 60L
        const val VALUE_CONTRACT =
            """{"mode":"value","inputs":{"order":{"kind":"body"}},"output":{"kind":"value","type":"STRING"}}"""
    }
}
