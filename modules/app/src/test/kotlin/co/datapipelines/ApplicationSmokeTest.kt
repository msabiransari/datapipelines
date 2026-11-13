package co.datapipelines

import co.datapipelines.config.SharedRedis
import co.datapipelines.metrics.MeterCatalogue
import co.datapipelines.web.health.ScratchPathKind
import co.datapipelines.web.health.ScratchSpaceReader
import co.datapipelines.web.ratelimit.RateLimiter
import co.datapipelines.web.ratelimit.RedisRateLimiter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/**
 * The P0 smoke test (module-structure.md §9.3 / §5.10), now the P7 full-context test:
 * the WHOLE application boots — auth's real security chain, web's REST/SSE surface,
 * the engine, and the MCP autoconfiguration — Flyway migrates a clean database, and
 * the three root probes answer with the shapes their specs promise.
 *
 * OIDC discovery is real but aimed at [OidcDiscoveryStub]: `OidcConfig`'s production
 * bean builds `ClientRegistration`s from the configured providers exactly as shipped
 * (auth.md §5.2), against an in-process loopback discovery document — no network, no
 * container, no test-only substitute bean.
 *
 * Self-contained by design — it runs against the module's shared Postgres and
 * Redis containers rather than assuming `deploy/compose.laptop-infra.yml` is up, so
 * CI and a fresh checkout behave identically.
 *
 * Deliberately sets no posture. That is the `local` deployment (configuration.md §3.23):
 * `datapipelines.env` binds its shipped default `local`, §7 resolves it to `development`,
 * and no posture profile is active — the base file's defaults ARE that column. It is also
 * deliberately NOT a laptop profile: a test that depended on one developer's infrastructure
 * values would be the back door through which literal secrets returned to the repository
 * after the 2026-08-07 HIGH-2 finding. The secrets below are generated per-run and never
 * written down. [ApplicationHardenedSmokeTest] is the other posture's boot.
 *
 * `@AutoConfigureObservability(tracing = false)`: a Spring Boot test switches metrics export
 * OFF by default (`management.defaults.metrics.export.enabled=false`), which would leave the
 * Prometheus registry and its scrape endpoint unbuilt. Production keeps Boot's default (on), so
 * the smoke boot re-enables export to boot the shipped registry shape (#708). Tracing stays off:
 * the application ships no tracer (observability.md §5).
 */
@AutoConfigureObservability(tracing = false)
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(ApplicationSmokeTest.PinnedScratchSpace::class)
class ApplicationSmokeTest {
    @Autowired
    private lateinit var rest: TestRestTemplate

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @LocalManagementPort
    private var managementPort: Int = 0

    private val http: HttpClient = HttpClient.newHttpClient()

    /** A plain anonymous GET on loopback — no session, no TestRestTemplate defaults. */
    private fun get(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())

    private val mapper = ObjectMapper()

    @Test
    fun `context loads and health reports every component UP`() {
        val response = rest.getForEntity("/health", String::class.java)

        response.statusCode.value() shouldBe 200
        val json = mapper.readTree(response.body)

        // Same key-set assertion as the unit test, but against the real serialized
        // response — this is what actually catches a Jackson config change in the app.
        json.fieldNames().asSequence().toList() shouldContainExactly listOf("status", "version", "components")
        json["components"].fieldNames().asSequence().toList() shouldContainExactly
            listOf("database", "redis", "h2_factory")

        // `database` UP is the real proof Flyway migrated and the pool works.
        json["status"].asText() shouldBe "UP"
        json["components"]["database"].asText() shouldBe "UP"
        json["components"]["redis"].asText() shouldBe "UP"
        json["components"]["h2_factory"].asText() shouldBe "UP"
        json["version"].asText().shouldNotBeBlank()
    }

    @Test
    fun `the rate limiter's Redis is the SAME Redis health reports`() {
        // 083 §A: the limiter now fails CLOSED, so its Redis being down refuses every metered
        // request — which means an operator watching `/health` must see that outage there and
        // not have to infer it. `/health` reports Boot's `redis` indicator, which is built from
        // the ONE `RedisConnectionFactory` in the context; the limiter is built from the
        // `StringRedisTemplate` that sits on that same factory.
        //
        // So the property to hold is "there is exactly one Redis connection in this application".
        // A second factory — a limiter given its own client, say — would make the limiter's
        // failure a signal `/health` cannot see, which is the whole thing the ruling forbids.
        //
        // The types are named as strings rather than imported because this module declares no
        // Redis dependency of its own (§5.10: `app` depends on `web` and nothing else internal);
        // they are on the test RUNTIME classpath, which is exactly where a wiring assertion needs
        // them. Bean NAMES are all the count needs.
        val factories = applicationContext.getBeanNamesForType(Class.forName(REDIS_CONNECTION_FACTORY))
        withClue("Redis connection factories in the context: ${factories.toList()}") {
            factories.size shouldBe 1
        }
        val templates = applicationContext.getBeanNamesForType(Class.forName(STRING_REDIS_TEMPLATE))
        withClue("StringRedisTemplates in the context: ${templates.toList()}") {
            templates.size shouldBe 1
        }

        // And the limiter really is the Redis one — a fallback in-process limiter would meter
        // per instance and would not be covered by the reasoning above.
        applicationContext.getBean(RateLimiter::class.java).shouldBeInstanceOf<RedisRateLimiter>()
    }

    @Test
    fun `ready returns 200 once the context is up`() {
        rest.getForEntity("/ready", String::class.java).statusCode.value() shouldBe 200
    }

    @Test
    fun `info reports the version at the root path, and no build time unless the build supplied one`() {
        val response = rest.getForEntity("/info", String::class.java)

        response.statusCode.value() shouldBe 200
        val json: JsonNode = mapper.readTree(response.body)

        json["version"].asText().shouldNotBeBlank()
        // `build_time` and `commit` are each present only when the build was given
        // -Pdatapipelines.buildTime / -Pdatapipelines.commit (observability §6.3), and neither
        // the gate nor CI supplies them. A `build_time` in a plain build means the plugin's
        // per-invocation Instant.now() is back — the stamp that kept the app's jar new on every
        // Gradle run and re-ran everything downstream of it (#292).
        withClue("/info carried build_time in a build that supplied no -Pdatapipelines.buildTime: ${response.body}") {
            json.has("build_time") shouldBe false
        }
    }

    /**
     * 188 (#188): the headers the app STATES, read off the REAL routes of the assembled app
     * — the login page, the public site root, an API list, `/mcp` and an SSE route. The
     * 401s are the point: an entry point that wrote its envelope before the header writers
     * ran would ship the refusal bare. The auth module proves the same over its probes
     * (`AuthHttpBoundaryTest`, an open SSE stream included); this is the assembled app.
     */
    @Test
    fun `every real route states the security headers - login, site root, api, mcp and sse`() {
        val routes =
            listOf(
                "/login" to 200,
                "/" to 200,
                "/api/v1/pipelines" to 401,
                "/mcp" to 401,
                "/api/v1/executions/00000000-0000-0000-0000-000000000000/events" to 401,
            )
        routes.forEach { (path, expected) ->
            val response = rest.getForEntity(path, String::class.java)
            withClue("GET $path") {
                response.statusCode.value() shouldBe expected
                val h = response.headers
                h.getFirst("Content-Security-Policy") shouldBe CSP_POLICY
                h.getFirst("X-Frame-Options") shouldBe "SAMEORIGIN"
                h.getFirst("X-Content-Type-Options") shouldBe "nosniff"
                h.getFirst("Referrer-Policy") shouldBe "strict-origin-when-cross-origin"
                h.getFirst("Permissions-Policy") shouldBe "camera=(), microphone=(), geolocation=()"
                h.getFirst("X-XSS-Protection") shouldBe "0"
                h.getFirst("Strict-Transport-Security") shouldBe null
            }
        }
        // 195: the editor's route takes the SAME policy — the eval exemption 188
        // scoped onto it is retired (the page runs Alpine's CSP build), and the one
        // `<style>` hash Cytoscape injects rides `style-src` on every route.
        val editor = rest.getForEntity("/pipelines/00000000-0000-0000-0000-000000000000/editor", String::class.java)
        editor.headers.getFirst("Content-Security-Policy") shouldBe CSP_POLICY
        editor.headers.getFirst("Content-Security-Policy") shouldNotContain "unsafe-eval"
        // And the one route with none: the sitemap (an XML data document).
        val sitemap = rest.getForEntity("/sitemap.xml", String::class.java)
        sitemap.headers.getFirst("Content-Security-Policy") shouldBe null
    }

    @Test
    fun `no actuator endpoint is reachable on the application port`() {
        // observability.md §6.4: only /health, /ready and /info are public on the app
        // port; /actuator/prometheus lives on the separate management port (§4.2).
        rest.getForEntity("/actuator/health", String::class.java).statusCode.is2xxSuccessful shouldBe false
        rest.getForEntity("/actuator/env", String::class.java).statusCode.is2xxSuccessful shouldBe false
    }

    /**
     * #708 / #231 item 1 (failure-flow record §17 row 11): the MANAGEMENT port serves the Prometheus scrape and
     * health ANONYMOUSLY — the port is the boundary, not a credential (observability.md §4.2) — while the
     * application port keeps every `/actuator` path closed (the case above). Read over plain HTTP with no
     * session, from the port Boot reports for the management child context (`@LocalManagementPort`).
     *
     * The index is pinned to exactly `health` and `prometheus` (the `-path` template links are the same
     * endpoints, as in `EntryInventoryE2eTest`), so widening `exposure.include` is a red here, not a silent
     * change. `datapipelines_executions_concurrent` is the execution-slot gauge registered at boot (#569);
     * the two abandoned gauges prove A.3's prefix on the wire.
     */
    @Test
    fun `the management port serves prometheus and health anonymously - and only those two endpoints`() {
        val index = get(managementPort, "/actuator")
        withClue("anonymous GET /actuator on the management port: ${index.body()}") { index.statusCode() shouldBe 200 }
        println("event=scrape.evidence management_index=${index.body()}")
        val links =
            mapper
                .readTree(index.body())["_links"]
                .fieldNames()
                .asSequence()
                .filterNot { it.endsWith("-path") }
                .toSortedSet()
        links shouldBe sortedSetOf("self", "health", "prometheus")

        get(managementPort, "/actuator/health").statusCode() shouldBe 200

        val scrape = get(managementPort, "/actuator/prometheus")
        scrape.statusCode() shouldBe 200
        scrape.headers().firstValue("Content-Type").orElse("") shouldStartWith "text/plain"
        val series = scrape.body().lines().filterNot { it.startsWith("#") }
        println(
            "event=scrape.evidence head=\n" +
                scrape
                    .body()
                    .lines()
                    .take(SCRAPE_EVIDENCE_LINES)
                    .joinToString("\n"),
        )
        listOf(
            "datapipelines_executions_concurrent",
            "datapipelines_script_evaluations_abandoned",
            "datapipelines_parameters_selectors_abandoned",
        ).forEach { family ->
            withClue("a `$family` series in the anonymous scrape") {
                series.any { it.startsWith("$family ") || it.startsWith("$family{") } shouldBe true
            }
        }

        // An endpoint the index does not list is refused anonymously on the management port, and the
        // application port answers neither the scrape nor the index (status recorded in the evidence).
        val appScrape = rest.getForEntity("/actuator/prometheus", String::class.java).statusCode
        val managementEnv = get(managementPort, "/actuator/env").statusCode()
        println("event=scrape.evidence app_port_actuator_prometheus=${appScrape.value()} management_env=$managementEnv")
        appScrape.is2xxSuccessful shouldBe false
        managementEnv shouldBe 401
    }

    /**
     * #806 (owner ruling 2026-11-12): the `duckdb_scratch` indicator joins the management port's aggregate, status
     * only — the body is `{"status":"UP"}`, no component, no path, no number — and the four scratch gauges are on
     * the anonymous scrape. The space reader is pinned ([PinnedScratchSpace]), so the case never depends on the
     * host's free disk; the pinned usable figure on the wire proves the gauge reads that bean. No DuckDB execution
     * runs in this class, so the leftover sweep has not run and `leftover` is `NaN`, never `0`. The public `/health`
     * keeping exactly three components is the first case above.
     */
    @Test
    fun `806 - the management health stays status only and the scratch gauges are on the anonymous scrape`() {
        val health = get(managementPort, "/actuator/health")
        withClue("anonymous GET /actuator/health on the management port: ${health.body()}") {
            health.statusCode() shouldBe 200
            mapper.readTree(health.body()).fieldNames().asSequence().toList() shouldContainExactly listOf("status")
            mapper.readTree(health.body())["status"].asText() shouldBe "UP"
            health.body() shouldNotContain "dp-staging"
            health.body().any(Char::isDigit) shouldBe false
        }

        val series = get(managementPort, "/actuator/prometheus").body().lines().filterNot { it.startsWith("#") }

        fun value(family: String): Double {
            val line = series.singleOrNull { it.startsWith("$family ") }
            withClue("exactly one untagged `$family` series in the scrape") { line shouldNotBe null }
            return checkNotNull(line).substringAfter(' ').trim().toDouble()
        }
        println("event=scrape.evidence scratch=" + series.filter { it.startsWith("datapipelines_staging_scratch_") })
        value("datapipelines_staging_scratch_capacity_bytes") shouldBe (20L shl 30).toDouble()
        value("datapipelines_staging_scratch_reserved_bytes") shouldBe 0.0
        value("datapipelines_staging_scratch_leftover_bytes").isNaN() shouldBe true
        value("datapipelines_staging_scratch_usable_bytes") shouldBe PinnedScratchSpace.USABLE.toDouble()
    }

    /** #806: the scratch readings pinned to a healthy directory with a fixed, ample usable figure. */
    @TestConfiguration
    class PinnedScratchSpace {
        @Bean
        @Primary
        fun pinnedScratchSpaceReader(): ScratchSpaceReader =
            object : ScratchSpaceReader {
                override fun kind(path: Path): ScratchPathKind = ScratchPathKind.DIRECTORY

                override fun canWrite(directory: Path): Boolean = true

                override fun usableBytes(path: Path): Long = USABLE
            }

        companion object {
            /** 1 TiB: above one default reservation (3 GiB) on any configuration this class boots. */
            const val USABLE = 1L shl 40
        }
    }

    /**
     * #708 (failure-flow record §13): the booted registry is the second reader of `MeterCatalogueParityTest`'s
     * static sweep. Every `datapipelines.*` meter the running application holds must be in the swept set — a
     * registration form the sweep does not recognise is red here as soon as its meter is eager — and so has an
     * observability.md §4.1 row.
     */
    @Test
    fun `every datapipelines meter the booted registry holds is in the swept catalogue`() {
        val runtime =
            meterRegistry.meters
                .map { it.id.name }
                .filter { it.startsWith(MeterCatalogue.PREFIX) }
                .toSortedSet()
        val swept = MeterCatalogue.codedSites().mapNotNull { it.name }.toSet()
        println("event=meter.catalogue runtime=${runtime.size} ${runtime.joinToString(",")}")
        withClue("meters registered at runtime that the static sweep did not find") { (runtime - swept).toList() shouldBe emptyList() }
        runtime.size shouldBeGreaterThanOrEqual RUNTIME_METER_FLOOR
    }

    /**
     * #715: every `datapipelines.*` meter the booted application holds carries a description, read twice — at
     * the registry (`id.description`) and on the wire, where the exporter OMITS the `# HELP` line of an
     * undescribed family. Both lists are collected before the one assertion, so neither half hides the other.
     * Eager meters only: a meter created on its first event is not in a freshly booted registry. Also prints
     * the context's registry beans (#716 evidence).
     */
    @Test
    fun `every datapipelines meter the booted registry holds carries a description - registry and scrape`() {
        println("event=meter.registries shape=production ${meterRegistryShape(applicationContext)}")
        val runtime = meterRegistry.meters.filter { it.id.name.startsWith(MeterCatalogue.PREFIX) }
        val undescribed =
            runtime
                .filter { it.id.description.isNullOrBlank() }
                .map { it.id.name }
                .toSortedSet()
        val (typed, withoutHelp) = scrapeFamilies()
        println("event=meter.undescribed registry=${undescribed.size} ${undescribed.joinToString(",")}")
        println("event=meter.scrape type_families=${typed.size} without_help=${withoutHelp.size} ${withoutHelp.joinToString(",")}")
        withClue("registry undescribed: ${undescribed.toList()}; scrape families without HELP: ${withoutHelp.toList()}") {
            (undescribed.toList() to withoutHelp.toList()) shouldBe (emptyList<String>() to emptyList())
        }
        runtime.size shouldBeGreaterThanOrEqual RUNTIME_METER_FLOOR
        typed.size shouldBeGreaterThanOrEqual RUNTIME_METER_FLOOR
    }

    /**
     * #716: the production shape holds ONE meter registry — Boot's Prometheus registry, the one the scrape
     * reads — and no composite. A second registry bean (the user `SimpleMeterRegistry` `EngineConfiguration`
     * declared until #716) makes Boot wrap both in a `@Primary` composite, so every meter is held twice and
     * the in-memory copy is read by nobody.
     */
    @Test
    fun `the booted application holds exactly one meter registry`() {
        val beans = applicationContext.getBeansOfType(MeterRegistry::class.java)
        withClue("MeterRegistry beans: ${beans.mapValues { it.value::class.java.name }}") {
            beans.values.map { it::class.java.name } shouldBe listOf(PROMETHEUS_REGISTRY)
        }
    }

    /**
     * The `datapipelines_*` families of the management scrape, taken from their `# TYPE` lines, and the
     * subset with no `# HELP` line or a blank one. The exporter OMITS the HELP line of an undescribed
     * family rather than writing a blank one, so the families come from TYPE, independently of HELP.
     */
    private fun scrapeFamilies(): Pair<Set<String>, Set<String>> {
        val lines = get(managementPort, "/actuator/prometheus").body().lines()

        fun families(kind: String) =
            lines
                .filter { it.startsWith("# $kind $SCRAPE_PREFIX") }
                .map { it.removePrefix("# $kind ").split(' ', limit = 2) }

        val typed = families("TYPE").map { it[0] }.toSortedSet()
        val helped = families("HELP").filter { it.size == 2 && it[1].isNotBlank() }.map { it[0] }.toSet()
        return typed to (typed - helped).toSortedSet()
    }

    /**
     * The composition wiring (design 2026-08-13-pipeline-node-type §4.1), asserted at the bean
     * graph the assembled app actually boots: the `SubPipelineRunner` port and the
     * repository-backed `PipelineResolver` must both exist, or a PIPELINE node would fail closed
     * in production while module tests stayed green. (By name, not by type: `app` may only
     * depend on `web`, module-structure §4.2 — the behavioral half of this wiring is covered in
     * `web`'s `RepositoryPipelineResolverTest`.)
     */
    @Test
    fun `composition beans are wired - sub-pipeline runner and repository-backed resolver`() {
        applicationContext.getBean("subPipelineRunner") shouldNotBe null
        applicationContext.getBean("pipelineResolver") shouldNotBe null
    }

    private companion object {
        /**
         * The policy as a LITERAL, not `SecurityHeaders.CSP_POLICY`: `app` depends on `web`
         * only (module-structure §4.2), and a wire assertion should read the header the way
         * an operator does — as the string the browser receives (188). Since 195 the
         * `style-src` hash of the one `<style>` Cytoscape injects rides on EVERY route
         * (a hash admits exactly that sheet; the class KDoc says why one policy).
         */
        const val CSP_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self' 'sha256-pgvDUBa4IjFA2yuSJ2cqcyxmNYJMborsd0ORcRv9vw8='; " +
                "img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'self'; base-uri 'self'; " +
                "form-action 'self'; object-src 'none'"

        /** The module's shared containers — started on first touch, migrated by the first context's Flyway. */
        private val postgres get() = SharedPostgres.postgres
        private val redis get() = SharedRedis.redis

        /** Named, not imported — see the health/limiter test for why. */
        const val REDIS_CONNECTION_FACTORY = "org.springframework.data.redis.connection.RedisConnectionFactory"
        const val STRING_REDIS_TEMPLATE = "org.springframework.data.redis.core.StringRedisTemplate"

        private const val SECRET_BYTES = 32

        /** The scrape's first lines printed as lane evidence (#708). */
        private const val SCRAPE_EVIDENCE_LINES = 20

        /** Named, not imported: the Prometheus registry is a runtime-only dependency of `app` (#708). */
        const val PROMETHEUS_REGISTRY = "io.micrometer.prometheusmetrics.PrometheusMeterRegistry"

        /** `MeterCatalogue.PREFIX` as the Prometheus exporter spells a family name. */
        private const val SCRAPE_PREFIX = "datapipelines_"

        /** The eagerly-registered `datapipelines.*` meters a booted app holds (gauges and eager counters). */
        private const val RUNTIME_METER_FLOOR = 10

        /**
         * In-process OIDC discovery (auth.md §5.2) for the configured providers — the
         * real `OidcConfig` fetches this at startup; nothing here authenticates anyone.
         */
        private val oidc = OidcDiscoveryStub()

        /** A fresh, valid, base64 32-byte secret. Never a literal — see the class KDoc. */
        private fun randomSecret(): String =
            Base64
                .getEncoder()
                .encodeToString(ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) })

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            // Random management port (observability.md §4.2 puts actuator on its own
            // port). Fixed 9090 would collide between concurrently running tests, and
            // TestRestTemplate targets the application port regardless.
            registry.add("management.server.port") { "0" }

            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("datapipelines.redis.host") { redis.host }
            registry.add("datapipelines.redis.port") { SharedRedis.port }
            registry.add("datapipelines.redis.password") { SharedRedis.PASSWORD }

            registry.add("datapipelines.jwt.secret") { randomSecret() }
            registry.add("datapipelines.db.encryption-key") { randomSecret() }

            // OIDC (auth.md §5.1/§5.2): the provider LIST is re-declared here in full —
            // Spring takes a bound list wholesale from the highest-precedence property
            // source that contains any element of it, so overriding just the issuer-uri
            // would shadow application.yml's entries and leave their names blank; and
            // BOTH indices must be redeclared, because an Environment lookup for index 1
            // otherwise falls through to application.yml's `${GOOGLE_CLIENT_ID}`
            // placeholder, which cannot resolve here. (application.yml ships google
            // only; the second entry merely exercises multi-provider discovery.)
            // Both entries discover from the in-process stub; the base-url the
            // redirect URIs are built from is set explicitly.
            listOf("google", "microsoft").forEachIndexed { index, name ->
                registry.add("datapipelines.auth.oidc.providers[$index].name") { name }
                registry.add("datapipelines.auth.oidc.providers[$index].client-id") { "test-$name-client-id" }
                registry.add("datapipelines.auth.oidc.providers[$index].client-secret") { "test-$name-client-secret" }
                registry.add("datapipelines.auth.oidc.providers[$index].issuer-uri") { oidc.issuer }
                registry.add("datapipelines.auth.oidc.providers[$index].display-name") { "Test $name" }
            }
            registry.add("datapipelines.auth.base-url") { "http://localhost:8080" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            oidc.close()
        }
    }
}
