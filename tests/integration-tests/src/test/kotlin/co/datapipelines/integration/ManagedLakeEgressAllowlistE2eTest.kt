package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.InetAddress
import java.net.Socket
import java.sql.DriverManager

/**
 * #862 (review R5) — the egress proxy's store allowlist against the SHIPPED proxy ([EgressProxyFixture]: compose's
 * image running the renderer's config for this suite's store), with RECORDING destinations, so a refusal is proven by
 * what the forbidden destination saw — zero accepted connections — not only by the status the client got.
 *
 * The store is a dotted bucket in virtual-host style on a non-default port (`dp.lake.al.store.dp862.test:<P>`, the
 * renderer's one allowlisted authority, from `EgressStoreTarget`). The container's `/etc/hosts` maps the store's names
 * to 127.0.0.1 and every attacker name to 127.0.0.2, where recorders listen on the store's port and on a wrong port; a
 * third recorder takes the store's own host on the wrong port. The store answers every response with a spoofed
 * `x-dp-egress-refused` stamp, which the proxy must strip.
 *
 * - **A1** healthy: a raw request and the REAL engine (DuckDB httpfs, settings from the same derivation) read the
 *   store through the proxy, and the store saw only the allowlisted authority.
 * - **A2** the bypass matrix: conflicting Host, wrong port, suffix/prefix/trailing-dot names, userinfo, IP literals,
 *   port normalization, header-based overrides, a store redirect, the declaration route on another authority, and
 *   CONNECT variants — each refused or kept on the permitted store, never at a forbidden recorder.
 * - **A3** the stamp: the proxy's own refusal carries it, and a store-sent stamp never reaches the client.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ManagedLakeEgressAllowlistE2eTest {
    @Test
    @Order(1)
    fun `A1 - a healthy read of the permitted store passes the proxy - raw request and the real engine`() {
        withClue("the derivation allowlists exactly the dotted virtual-host authority") {
            derived.domains shouldContainExactly listOf(PERMITTED)
        }
        val before = store.requests.size
        val raw = EgressProxyFixture.exchange(proxy, get("http://$PERMITTED/$OBJECT", host = PERMITTED))
        println("EGRESS_ALLOWLIST A1 raw=\"${raw.lineSequence().first()}\" store_requests=${store.requests.size - before}")
        raw shouldStartWith "HTTP/1.1 200"
        raw shouldContain CSV.trimEnd()

        val engineBefore = store.requests.size
        val answer = engineRead()
        val engineRequests = store.requests.drop(engineBefore)
        println("EGRESS_ALLOWLIST A1 engine=$answer store_requests=${engineRequests.map { "${it.method} ${it.target} host=${it.host}" }}")
        answer shouldBe "3/60"
        withClue("non-vacuity: the engine's reads reached the store through the proxy") { engineRequests.isEmpty() shouldBe false }
        withClue("every request the store saw named the allowlisted authority") {
            store.requests.map { it.host?.lowercase() }.distinct() shouldContainExactly listOf(PERMITTED)
        }
        forbiddenConnections() shouldBe 0
    }

    @Test
    @Order(2)
    fun `A2 - every bypass form is refused or stays on the permitted store, and no forbidden destination sees a connection`() {
        val failures = AllowlistMatrix.cases().mapNotNull { case -> run(case) }
        Thread.sleep(SETTLE_MILLIS)
        val leaked = forbidden.associate { it.label to it.connections.get() }
        println("EGRESS_ALLOWLIST A2 forbidden_connections=$leaked")
        withClue("cases that broke the allowlist") { failures.shouldBeEmpty() }
        withClue("no forbidden recorder accepted a connection") { leaked.values.sum() shouldBe 0 }
    }

    @Test
    @Order(3)
    fun `A3 - the proxy stamps its own refusal and strips a store-sent stamp`() {
        val refused = EgressProxyFixture.exchange(proxy, get("http://$EVIL:$storePort/", host = "$EVIL:$storePort"))
        println("EGRESS_ALLOWLIST A3 refused=\"${refused.replace("\r\n", " | ")}\"")
        refused shouldStartWith "HTTP/1.1 403"
        refused.lowercase() shouldContain "$REFUSAL_HEADER: store-allowlist"

        val stored = EgressProxyFixture.exchange(proxy, get("http://$PERMITTED/$OBJECT", host = PERMITTED))
        println("EGRESS_ALLOWLIST A3 store_answer=\"${stored.substringBefore("\r\n\r\n").replace("\r\n", " | ")}\"")
        stored shouldStartWith "HTTP/1.1 200"
        withClue("the store sends a spoofed stamp; the proxy strips it") { stored.lowercase().contains(REFUSAL_HEADER) shouldBe false }
    }

    /** One matrix case against the live proxy; null = it held, else the reason it did not. */
    private fun run(case: AllowlistMatrix.Case): String? {
        val storeBefore = store.requests.size
        val forbiddenBefore = forbiddenConnections()
        val answer = EgressProxyFixture.exchange(proxy, case.request, case.inTunnel)
        val status = answer.lineSequence().first()
        val reached = store.requests.drop(storeBefore)
        val leaked = forbiddenConnections() - forbiddenBefore
        val marked = answer.lowercase().contains("$REFUSAL_HEADER: store-allowlist")
        val problems =
            buildList {
                if (leaked != 0) add("forbidden recorders accepted $leaked connection(s)")
                reached.filterNot { it.host.equals(PERMITTED, ignoreCase = true) }.forEach { add("the store saw host=${it.host}") }
                addAll(case.expect.violations(status, marked, reached.size, answer))
            }
        val verdict = if (problems.isEmpty()) "HELD" else "BROKEN ${problems.joinToString("; ")}"
        println(
            "EGRESS_ALLOWLIST A2 case=\"${case.name}\" expect=${case.expect} status=\"$status\" marker=$marked " +
                "store_requests=${reached.size} forbidden_delta=$leaked verdict=$verdict",
        )
        return if (problems.isEmpty()) null else "${case.name}: ${problems.joinToString("; ")}"
    }

    private fun forbiddenConnections(): Int = forbidden.sumOf { it.connections.get() }

    /** DuckDB httpfs reading the store through the proxy, configured as the managed adapter would from [derived]. */
    private fun engineRead(): String =
        DriverManager.getConnection("jdbc:duckdb:").use { duck ->
            duck.createStatement().use { st ->
                st.execute("INSTALL httpfs")
                st.execute("LOAD httpfs")
                st.execute("SET http_proxy = '127.0.0.1:$proxy'")
                st.execute("SET http_retries = 0")
                val endpoint = derived.duckdbEndpoint
                st.execute(
                    "CREATE SECRET dp862 (TYPE s3, KEY_ID '$DUMMY_KEY', SECRET '$DUMMY_SECRET', REGION 'us-east-1', " +
                        "ENDPOINT '${endpoint.substringAfter("://")}', URL_STYLE 'vhost', USE_SSL ${endpoint.startsWith("https://")})",
                )
                st.executeQuery("SELECT count(*), sum(v) FROM read_csv('s3://$BUCKET/$OBJECT')").use { rs ->
                    rs.next()
                    "${rs.getLong(1)}/${rs.getLong(2)}"
                }
            }
        }

    companion object {
        const val BUCKET = "dp.lake.al"
        const val STORE_BASE = "store.dp862.test"
        const val STORE_HOST = "$BUCKET.$STORE_BASE"
        const val EVIL = "evil.dp862.test"
        const val OBJECT = "probe.csv"
        const val REFUSAL_HEADER = "x-dp-egress-refused"
        const val CSV = "id,v\n1,10\n2,20\n3,30\n"
        private const val MAX_AGE_SECONDS = 960L
        private const val SETTLE_MILLIS = 500L

        /** Clearly local dummy values: the store never checks a signature, and nothing leaves loopback. */
        private const val DUMMY_KEY = "dp862-local-dummy-key"
        private const val DUMMY_SECRET = "dp862-local-dummy-secret"

        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        private val ATTACKER: InetAddress = InetAddress.getByName("127.0.0.2")

        val storePort = RecordingServer.portFreeOn(LOOPBACK, ATTACKER)
        val wrongPort = RecordingServer.portFreeOn(LOOPBACK, ATTACKER)
        val PERMITTED = "$STORE_HOST:$storePort"

        private val store = RecordingServer(LOOPBACK, storePort, "store", AllowlistMatrix::storeAnswer)
        private val forbidden =
            listOfNotNull(
                RecordingServer(ATTACKER, storePort, "attacker-store-port"),
                RecordingServer(ATTACKER, wrongPort, "attacker-wrong-port"),
                RecordingServer(LOOPBACK, wrongPort, "store-host-wrong-port"),
                runCatching { RecordingServer(InetAddress.getByName("::1"), storePort, "ipv6-loopback-store-port") }.getOrNull(),
            )

        private val derived by lazy { EgressProxyFixture.derive("http://$STORE_BASE:$storePort", "us-east-1", BUCKET, pathStyle = false) }
        private val envoyPort by lazy { EgressProxyFixture.freePort() }

        private fun shippedConfig(listenerPort: Int): String =
            EgressProxyFixture.relistened(EgressProxyFixture.rendered(derived, MAX_AGE_SECONDS), listenerPort)

        private val envoyStarted =
            lazy {
                val hosts =
                    mapOf(STORE_HOST to "127.0.0.1", STORE_BASE to "127.0.0.1") +
                        AllowlistMatrix.ATTACKER_NAMES.associateWith { "127.0.0.2" }
                println(
                    "EGRESS_ALLOWLIST proxy image=${EgressProxyFixture.shippedImage()} listener=127.0.0.1:$envoyPort " +
                        "permitted=$PERMITTED wrong_port=$wrongPort recorders=${forbidden.map { it.label }}",
                )
                EgressProxyFixture.start(shippedConfig(envoyPort), hosts)
            }

        /** The proxy's loopback port, the proxy started on first use. */
        private val proxy: Int get() = envoyStarted.value.let { envoyPort }

        fun get(
            url: String,
            host: String,
            extra: String = "",
        ) = "GET $url HTTP/1.1\r\nHost: $host\r\n${extra}Connection: close\r\n\r\n"

        @AfterAll
        @JvmStatic
        fun closeAll() {
            if (envoyStarted.isInitialized()) envoyStarted.value.stop()
            store.close()
            forbidden.forEach(RecordingServer::close)
        }
    }
}

/**
 * The A2 cases and the recording store's answers. `P` = the store port, `W` = a wrong port; attacker names resolve to
 * 127.0.0.2, where recorders listen on both. Every case is also held to two invariants by the runner: no forbidden
 * recorder accepts a connection, and every request the store sees names the allowlisted authority.
 */
internal object AllowlistMatrix {
    private val p get() = ManagedLakeEgressAllowlistE2eTest.storePort
    private val w get() = ManagedLakeEgressAllowlistE2eTest.wrongPort
    private val permitted get() = ManagedLakeEgressAllowlistE2eTest.PERMITTED
    private const val HOST = ManagedLakeEgressAllowlistE2eTest.STORE_HOST
    private const val EVIL = ManagedLakeEgressAllowlistE2eTest.EVIL
    private const val BASE = ManagedLakeEgressAllowlistE2eTest.STORE_BASE
    private const val SUFFIX = "$HOST.evil.test"
    private const val PREFIX = "x$HOST"
    private const val OBJECT = ManagedLakeEgressAllowlistE2eTest.OBJECT
    private const val PORT_WRAP = 65_536
    val ATTACKER_NAMES = listOf(EVIL, SUFFIX, PREFIX)

    enum class Expect {
        /** The proxy's own deny route: 403 with its stamp; nothing forwarded. */
        REFUSED_BY_PROXY,

        /** Refused before routing (codec or route): any 4xx; nothing forwarded. */
        REJECTED,

        /** Forwarded to the permitted store, once. */
        STORE,

        /** The store's own redirect passed back to the client, never followed by the proxy. */
        STORE_REDIRECT,

        /** The proxy's declaration answer: 200 with the cap; nothing forwarded. */
        DECLARATION,

        /** CONNECT to the permitted authority: tunnel established, the tunnelled request reaches the store. */
        TUNNEL,
        ;

        fun violations(
            status: String,
            marked: Boolean,
            storeRequests: Int,
            answer: String,
        ): List<String> =
            buildList {
                fun need(
                    ok: Boolean,
                    what: String,
                ) {
                    if (!ok) add(what)
                }
                when (this@Expect) {
                    REFUSED_BY_PROXY -> {
                        need(status.startsWith("HTTP/1.1 403") && marked, "expected the proxy's stamped 403")
                        need(storeRequests == 0, "expected nothing forwarded")
                    }

                    REJECTED -> {
                        need(Regex("^HTTP/1\\.1 4\\d\\d").containsMatchIn(status), "expected a 4xx")
                        need(storeRequests == 0, "expected nothing forwarded")
                    }

                    STORE -> {
                        need(status.startsWith("HTTP/1.1 200") && !marked, "expected the store's 200")
                        need(storeRequests == 1, "expected exactly one store request")
                    }

                    STORE_REDIRECT -> {
                        need(status.startsWith("HTTP/1.1 302"), "expected the store's 302 passed back")
                        need(storeRequests == 1, "expected exactly one store request")
                    }

                    DECLARATION -> {
                        need(status.startsWith("HTTP/1.1 200") && answer.contains("max-age="), "expected the declaration")
                        need(storeRequests == 0, "expected nothing forwarded")
                    }

                    TUNNEL -> {
                        need(status.startsWith("HTTP/1.1 200"), "expected the tunnel established")
                        need(storeRequests == 1, "expected the tunnelled request at the store")
                    }
                }
            }
    }

    class Case(
        val name: String,
        val request: String,
        val expect: Expect,
        val inTunnel: ((Socket) -> Unit)? = null,
    )

    private fun get(
        url: String,
        host: String,
        extra: String = "",
    ) = ManagedLakeEgressAllowlistE2eTest.get(url, host, extra)

    private fun connect(
        target: String,
        host: String = target,
    ) = "CONNECT $target HTTP/1.1\r\nHost: $host\r\n\r\n"

    @Suppress("LongMethod")
    fun cases(): List<Case> =
        listOf(
            Case("permitted absolute-form", get("http://$permitted/$OBJECT", permitted), Expect.STORE),
            Case("URL wrong port, Host permitted", get("http://$HOST:$w/", permitted), Expect.REFUSED_BY_PROXY),
            Case("URL permitted, Host wrong port", get("http://$permitted/$OBJECT", "$HOST:$w"), Expect.STORE),
            Case("URL permitted, Host attacker", get("http://$permitted/$OBJECT", "$EVIL:$p"), Expect.STORE),
            Case("URL attacker, Host permitted", get("http://$EVIL:$p/", permitted), Expect.REFUSED_BY_PROXY),
            Case("origin-form, Host attacker", get("/", "$EVIL:$p"), Expect.REFUSED_BY_PROXY),
            Case("origin-form, Host permitted", get("/$OBJECT", permitted), Expect.STORE),
            Case("explicit :80 on the store host", get("http://$HOST:80/", "$HOST:80"), Expect.REFUSED_BY_PROXY),
            Case("suffix domain", get("http://$SUFFIX:$p/", "$SUFFIX:$p"), Expect.REFUSED_BY_PROXY),
            Case("prefix domain", get("http://$PREFIX:$p/", "$PREFIX:$p"), Expect.REFUSED_BY_PROXY),
            Case("trailing dot", get("http://$HOST.:$p/", "$HOST.:$p"), Expect.REFUSED_BY_PROXY),
            Case("mixed case", get("http://${mixedCase()}:$p/$OBJECT", "${mixedCase()}:$p"), Expect.STORE),
            Case("percent-encoded host", get("http://${HOST.replace(".", "%2E")}:$p/", "$HOST:$p"), Expect.REJECTED),
            Case("leading-zero port", get("http://$HOST:0$p/", "$HOST:0$p"), Expect.REFUSED_BY_PROXY),
            Case("port wrapping past 65535", get("http://$HOST:${p + PORT_WRAP}/", "$HOST:${p + PORT_WRAP}"), Expect.REJECTED),
            Case("store IP literal", get("http://127.0.0.1:$p/$OBJECT", "127.0.0.1:$p"), Expect.REFUSED_BY_PROXY),
            Case("store host without the bucket", get("http://$BASE:$p/dp.lake.al/$OBJECT", "$BASE:$p"), Expect.REFUSED_BY_PROXY),
            Case("IPv6 loopback absolute-form", get("http://[::1]:$p/", "[::1]:$p"), Expect.REFUSED_BY_PROXY),
            Case("userinfo on the permitted authority", get("http://user@$permitted/$OBJECT", permitted), Expect.STORE),
            Case("userinfo hiding an attacker authority", get("http://$permitted@$EVIL:$w/", permitted), Expect.REFUSED_BY_PROXY),
            Case(
                "destination override headers",
                get(
                    "http://$permitted/$OBJECT",
                    permitted,
                    "x-envoy-original-dst-host: 127.0.0.2:$p\r\nX-Forwarded-Host: $EVIL:$p\r\nForwarded: host=$EVIL:$p\r\n" +
                        "X-Host: $EVIL:$p\r\n",
                ),
                Expect.STORE,
            ),
            Case("duplicate Host, attacker second", get("http://$permitted/$OBJECT", permitted, "Host: $EVIL:$p\r\n"), Expect.STORE),
            Case("duplicate Host, origin-form, attacker first", get("/$OBJECT", "$EVIL:$p", "Host: $permitted\r\n"), Expect.REFUSED_BY_PROXY),
            Case("the store's redirect to an attacker", get("http://$permitted/redirect", permitted), Expect.STORE_REDIRECT),
            Case("following that redirect", get("http://$EVIL:$w/", "$EVIL:$w"), Expect.REFUSED_BY_PROXY),
            Case(
                "declaration route on an attacker authority",
                get("http://$EVIL:$w/dp-egress", "$EVIL:$w", "x-dp-egress-check: 1\r\n"),
                Expect.DECLARATION,
            ),
            Case("CONNECT permitted, a request in the tunnel", connect(permitted), Expect.TUNNEL, ::tunnelledRead),
            Case("CONNECT wrong port", connect("$HOST:$w"), Expect.REFUSED_BY_PROXY),
            Case("CONNECT attacker", connect("$EVIL:$p"), Expect.REFUSED_BY_PROXY),
            Case("CONNECT wrong port, Host permitted", connect("$HOST:$w", permitted), Expect.REFUSED_BY_PROXY),
            Case("CONNECT without a port", connect(HOST), Expect.REJECTED),
            Case("CONNECT with userinfo", connect("user@$permitted"), Expect.REJECTED),
            Case("CONNECT suffix domain", connect("$SUFFIX:$p"), Expect.REFUSED_BY_PROXY),
            Case("CONNECT IPv6 loopback", connect("[::1]:$p"), Expect.REFUSED_BY_PROXY),
        )

    private fun mixedCase(): String = HOST.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }.joinToString("")

    private fun tunnelledRead(socket: Socket) {
        socket.getOutputStream().apply {
            write("GET /$OBJECT HTTP/1.1\r\nHost: $permitted\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            flush()
        }
        socket.getInputStream().readAllBytes()
    }

    /** The recording store: the CSV object (HEAD, ranged and whole GET), a redirect to an attacker, else 404. */
    fun storeAnswer(request: RecordingServer.Request): ByteArray {
        val body = ManagedLakeEgressAllowlistE2eTest.CSV.toByteArray(Charsets.ISO_8859_1)
        val common = "x-amz-request-id: DP862STORE\r\n${ManagedLakeEgressAllowlistE2eTest.REFUSAL_HEADER}: spoof\r\n"
        val path = request.target.substringBefore('?')
        val head =
            when {
                path == "/redirect" -> {
                    return "HTTP/1.1 302 Found\r\n${common}Location: http://$EVIL:$w/\r\nContent-Length: 0\r\n\r\n".toByteArray()
                }

                path != "/$OBJECT" -> {
                    return "HTTP/1.1 404 Not Found\r\n${common}Content-Length: 0\r\n\r\n".toByteArray()
                }

                else -> {
                    "${common}ETag: \"dp862\"\r\nLast-Modified: Mon, 02 Nov 2026 00:00:00 GMT\r\nAccept-Ranges: bytes\r\n"
                }
            }
        val range = request.headers["range"]?.removePrefix("bytes=")?.split('-')
        val (status, slice, extra) =
            if (range != null) {
                val from = range[0].toInt()
                val to = range.getOrNull(1)?.toIntOrNull()?.coerceAtMost(body.size - 1) ?: (body.size - 1)
                Triple("206 Partial Content", body.copyOfRange(from, to + 1), "Content-Range: bytes $from-$to/${body.size}\r\n")
            } else {
                Triple("200 OK", body, "")
            }
        val response = "HTTP/1.1 $status\r\n$head${extra}Content-Length: ${slice.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        return if (request.method == "HEAD") response else response + slice
    }
}
