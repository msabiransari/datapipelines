package co.datapipelines.web.config

import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * #862 — the renderer's structure is pinned against Envoy's matching semantics and probe A's wire forms: the store
 * vhost is EXACT (one domain, the derived authority), the deny vhost is `*` and answers 403 before DNS or connect,
 * the `/dp-egress` declaration lives in BOTH virtual hosts (the boot check addresses the proxy itself, whose
 * authority falls to deny), and with the lake disabled there is no store vhost at all — fail closed, never a
 * wildcard allowance. The file cases pin the contract the deployment owns: 0644 output, `egress_config:` refusals,
 * a nonzero exit before Envoy starts.
 */
class EgressProxyConfigMainTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the output path and refusal prefix are the deployment's contract`() {
        EgressProxyConfigMain.DEFAULT_OUTPUT shouldBe "/dp-egress-config/envoy.yaml"
        EgressProxyConfigMain.REFUSAL_PREFIX shouldBe "egress_config:"
    }

    @Test
    fun `the rendered config is deterministic - same inputs, byte-identical output`() {
        val first = EgressProxyConfigMain.render(960, minioVhost())
        val second = EgressProxyConfigMain.render(960, minioVhost())
        first shouldBe second
    }

    @Test
    fun `enabled - one exact store vhost, the deny vhost still refuses everything else, the declaration lives in both`() {
        val config = parse(EgressProxyConfigMain.render(3600, minioVhost()))
        val hcm = hcm(config)
        val vhosts = virtualHosts(hcm)
        withClue("two virtual hosts: store then deny") { vhosts.size shouldBe 2 }
        val store = vhosts[0]
        withClue("store name") { store["name"] shouldBe "store" }
        withClue("the exact authority, no alias") { store["domains"] shouldBe listOf("dp-lake.minio:9000") }
        val storeRoutes = routes(store)
        withClue("declaration, CONNECT, plain") { storeRoutes.size shouldBe 3 }
        withClue("declaration declares the baked cap") {
            declarationBody(storeRoutes[0]) shouldBe "max-age=3600"
        }
        withClue("CONNECT forwards with no timeout and the CONNECT upgrade") {
            val connect = storeRoutes[1]["route"] as Map<*, *>
            connect["cluster"] shouldBe "dynamic_forward_proxy"
            connect["timeout"] shouldBe "0s"
            @Suppress("UNCHECKED_CAST")
            val upgrade = (connect["upgrade_configs"] as List<*>)[0] as Map<String, Any>
            upgrade["upgrade_type"] shouldBe "CONNECT"
            upgrade shouldContainKey "connect_config"
        }
        withClue("plain requests forward too") {
            (storeRoutes[2]["route"] as Map<*, *>)["cluster"] shouldBe "dynamic_forward_proxy"
        }
        val deny = vhosts[1]
        withClue("deny is the catch-all") { deny["domains"] shouldBe listOf("*") }
        val denyRoutes = routes(deny)
        withClue("declaration, 403 CONNECT, 403 plain") { denyRoutes.size shouldBe 3 }
        withClue("the declaration still answers in deny - the boot check addresses the proxy itself") {
            declarationBody(denyRoutes[0]) shouldBe "max-age=3600"
        }
        withClue("CONNECT to any other host is refused") { status(denyRoutes[1]) shouldBe 403 }
        withClue("requests to any other host are refused") { status(denyRoutes[2]) shouldBe 403 }
        withClue("the cap cuts both streams and whole connections") {
            val options = hcm["common_http_protocol_options"] as Map<*, *>
            options["max_connection_duration"] shouldBe "3600s"
            options["max_stream_duration"] shouldBe "3600s"
        }
        withClue("the forward cluster is present") {
            clusters(config).map { it["name"] } shouldBe listOf("dynamic_forward_proxy")
        }
    }

    /**
     * Review R2: the proxy's own refusal is distinguishable from a store's 403 — every deny route stamps
     * [EgressProxyConfigMain.REFUSAL_HEADER], and the store vhost strips that header from every forwarded answer, so a
     * store cannot make its answer look like the proxy's (or the reverse).
     */
    @Test
    fun `the deny routes stamp the refusal header and the store vhost strips it from store answers`() {
        val vhosts = virtualHosts(hcm(parse(EgressProxyConfigMain.render(960, minioVhost()))))
        vhosts[0]["response_headers_to_remove"] shouldBe listOf("x-dp-egress-refused")
        val stamped =
            routes(vhosts[1]).drop(1).map { route ->
                @Suppress("UNCHECKED_CAST")
                val added = route["response_headers_to_add"] as List<Map<String, Map<String, String>>>
                added.single()["header"]
            }
        stamped shouldBe List(2) { mapOf("key" to "x-dp-egress-refused", "value" to "store-allowlist") }
        withClue("the declaration is never stamped: it is the proxy's own green answer") {
            routes(vhosts[1])[0].containsKey("response_headers_to_add") shouldBe false
        }
    }

    @Test
    fun `the output is serialized JSON, two-space indented with newline line ends - not spliced text`() {
        val rendered = EgressProxyConfigMain.render(960, minioVhost())
        rendered shouldStartWith "{\n  \"static_resources\" : {\n"
        withClue("no carriage return, one trailing newline") {
            rendered.contains('\r') shouldBe false
            rendered.endsWith("}\n") shouldBe true
        }
        withClue("the listener is the shipped one") {
            @Suppress("UNCHECKED_CAST")
            val listener = ((parse(rendered)["static_resources"] as Map<String, Any>)["listeners"] as List<Map<String, Any>>)[0]
            listener["address"] shouldBe mapOf("socket_address" to mapOf("address" to "0.0.0.0", "port_value" to 3128))
        }
    }

    @Test
    fun `disabled - no store vhost exists, the proxy declares its cap and refuses everything else`() {
        val config = parse(EgressProxyConfigMain.render(960, null))
        val vhosts = virtualHosts(hcm(config))
        withClue("exactly the deny vhost") { vhosts.size shouldBe 1 }
        withClue("deny is the catch-all") { vhosts[0]["domains"] shouldBe listOf("*") }
        val denyRoutes = routes(vhosts[0])
        withClue("the declaration is still served for the boot check") {
            declarationBody(denyRoutes[0]) shouldBe "max-age=960"
        }
        withClue("CONNECT is refused") { status(denyRoutes[1]) shouldBe 403 }
        withClue("requests are refused") { status(denyRoutes[2]) shouldBe 403 }
    }

    @Test
    fun `run writes the file 0644, prints one summary line and exits zero`() {
        val out = temp.resolve("envoy.yaml")
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val envMap =
            env(
                EgressProxyConfigMain.ENV_ENABLED to "true",
                EgressProxyConfigMain.ENV_BUCKET to "dp-lake",
                EgressProxyConfigMain.ENV_ENDPOINT to "http://minio:9000",
                EgressProxyConfigMain.ENV_PATH_STYLE to "false",
                EgressProxyConfigMain.ENV_MAX_AGE to "120",
            )
        val code =
            EgressProxyConfigMain.run(
                args = arrayOf(out.toString()),
                env = envMap,
                out = { stdout.appendLine(it) },
                err = { stderr.appendLine(it) },
            )
        code shouldBe 0
        withClue("the file exists and is world-readable") {
            Files.getPosixFilePermissions(out) shouldBe PosixFilePermissions.fromString("rw-r--r--")
        }
        withClue("the summary names the store and the cap, never a credential") {
            stdout.toString().trim() shouldBe
                "event=egress_config_written output=$out max_age_seconds=120 enabled=true store=dp-lake.minio:9000"
        }
        stderr.toString() shouldBe ""
        withClue("the written file parses and carries the exact vhost") {
            virtualHosts(hcm(parse(Files.readString(out)))).first()["domains"] shouldBe listOf("dp-lake.minio:9000")
        }
    }

    @Test
    fun `run refuses an enabled lake without a bucket - nonzero, egress_config on stderr, no file`() {
        val out = temp.resolve("never.yaml")
        val stderr = StringBuilder()
        val code =
            EgressProxyConfigMain.run(
                args = arrayOf(out.toString()),
                env =
                    env(
                        EgressProxyConfigMain.ENV_ENABLED to "true",
                        EgressProxyConfigMain.ENV_BUCKET to "",
                        EgressProxyConfigMain.ENV_MAX_AGE to "120",
                    ),
                err = { stderr.appendLine(it) },
            )
        code shouldBe 1
        stderr.toString().trim() shouldStartWith "egress_config:"
        Files.exists(out) shouldBe false
    }

    @Test
    fun `run refuses a malformed cap or a non-boolean - nothing is rendered`() {
        listOf(
            env(
                EgressProxyConfigMain.ENV_ENABLED to "true",
                EgressProxyConfigMain.ENV_BUCKET to "b",
                EgressProxyConfigMain.ENV_MAX_AGE to "abc",
            ),
            env(
                EgressProxyConfigMain.ENV_ENABLED to "true",
                EgressProxyConfigMain.ENV_BUCKET to "b",
                EgressProxyConfigMain.ENV_MAX_AGE to "0",
            ),
            env(
                EgressProxyConfigMain.ENV_ENABLED to "TRUE",
                EgressProxyConfigMain.ENV_BUCKET to "b",
                EgressProxyConfigMain.ENV_MAX_AGE to "120",
                EgressProxyConfigMain.ENV_PATH_STYLE to "yes",
            ),
        ).forEach { badEnv ->
            val out = temp.resolve("refused.yaml")
            val stderr = StringBuilder()
            val code =
                EgressProxyConfigMain.run(
                    args = arrayOf(out.toString()),
                    env = badEnv,
                    err = { stderr.appendLine(it) },
                )
            withClue(stderr.toString()) {
                code shouldBe 1
                stderr.toString().trim() shouldStartWith "egress_config:"
                Files.exists(out) shouldBe false
            }
        }
    }

    @Test
    fun `run with the lake disabled still renders the deny-only fail-closed file`() {
        val out = temp.resolve("deny-only.yaml")
        val code =
            EgressProxyConfigMain.run(
                args = arrayOf(out.toString()),
                env =
                    env(
                        EgressProxyConfigMain.ENV_ENABLED to "false",
                        EgressProxyConfigMain.ENV_MAX_AGE to "960",
                    ),
            )
        code shouldBe 0
        val vhosts = virtualHosts(hcm(parse(Files.readString(out))))
        vhosts.size shouldBe 1
        vhosts[0]["domains"] shouldBe listOf("*")
    }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map: Map<String, String> = pairs.toMap()
        return { name: String -> map[name] }
    }

    /** The stand-in is the REAL derivation, so the test travels the same path the deployment does. */
    private fun minioVhost() = EgressStoreTarget.derive("http://minio:9000", "", "dp-lake", pathStyle = false)

    /**
     * The renderer serializes JSON (Envoy's YAML loader reads it as YAML): parsed with the Jackson databind the
     * module already compiles against — no YAML library on the test classpath (review: no new dependency).
     */
    @Suppress("UNCHECKED_CAST")
    private fun parse(rendered: String): Map<String, Any> =
        com.fasterxml.jackson.databind
            .ObjectMapper()
            .readValue(rendered, Map::class.java) as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun hcm(config: Map<String, Any>): Map<String, Any> {
        val statics = config["static_resources"] as Map<String, Any>
        val listeners = statics["listeners"] as List<Map<String, Any>>
        val chains = listeners[0]["filter_chains"] as List<Map<String, Any>>
        val filters = chains[0]["filters"] as List<Map<String, Any>>
        return filters[0]["typed_config"] as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun virtualHosts(hcm: Map<String, Any>): List<Map<String, Any>> {
        val routeConfig = hcm["route_config"] as Map<String, Any>
        return routeConfig["virtual_hosts"] as List<Map<String, Any>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun routes(vhost: Map<String, Any>) = vhost["routes"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun clusters(config: Map<String, Any>) = (config["static_resources"] as Map<String, Any>)["clusters"] as List<Map<String, Any>>

    private fun declarationBody(route: Map<String, Any>): String {
        val response = route["direct_response"] as Map<*, *>
        val body = response["body"] as Map<*, *>
        return body["inline_string"] as String
    }

    private fun status(route: Map<String, Any>): Int {
        val response = route["direct_response"] as Map<*, *>
        return response["status"] as Int
    }
}
