package co.datapipelines.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * R8/T46 — the client-address resolution behind the login limiter and every auth-side
 * `source_ip`, per the owner ruling's spoof-safe reading: an UNTRUSTED peer cannot forge
 * its way past the limiter by setting `X-Forwarded-For`; a trusted peer contributes the
 * rightmost XFF entry that is NOT itself trusted.
 */
class ClientAddressResolverTest {
    private fun request(
        remote: String,
        xff: String? = null,
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/login").apply {
            remoteAddr = remote
            xff?.let { addHeader("X-Forwarded-For", it) }
        }

    @Test
    fun `empty trusted list - the header is ignored entirely and the peer wins`() {
        val resolver = ClientAddressResolver(emptyList())

        resolver.clientAddressOf(request("203.0.113.7", xff = "198.51.100.99")) shouldBe "203.0.113.7"
    }

    @Test
    fun `peer in the list - the client is the rightmost XFF entry not in the list`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("10.0.0.5", xff = "203.0.113.9, 10.0.0.5")) shouldBe "203.0.113.9"
    }

    @Test
    fun `peer in the list but every XFF entry is trusted - falls back to the peer`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8", "192.168.0.0/16"))

        resolver.clientAddressOf(request("10.0.0.5", xff = "192.168.1.1, 10.0.0.5")) shouldBe "10.0.0.5"
    }

    @Test
    fun `peer in the list with no header at all - the peer wins`() {
        ClientAddressResolver(listOf("10.0.0.0/8")).clientAddressOf(request("10.0.0.5")) shouldBe "10.0.0.5"
    }

    /**
     * The spoofing case the whole design refuses: the header names a DIFFERENT client
     * (or a chain of them), the peer is trusted, and the chain's untrusted rightmost
     * hop IS the client — not whatever the leftmost claim says.
     */
    @Test
    fun `a trusted peer's untrusted rightmost hop wins over the leftmost claim`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("10.0.0.5", xff = "198.51.100.99, 198.51.100.1, 10.0.0.5")) shouldBe "198.51.100.1"
    }

    @Test
    fun `an untrusted peer cannot forge a client by setting the header`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("203.0.113.7", xff = "10.0.0.5")) shouldBe "203.0.113.7"
    }

    @Test
    fun `a malformed XFF entry from a trusted peer falls back to the peer`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("10.0.0.5", xff = "not-an-ip, 10.0.0.5")) shouldBe "10.0.0.5"
    }

    @Test
    fun `IPv6 peers and entries resolve through the same rules`() {
        val resolver = ClientAddressResolver(listOf("::1/128", "2001:db8::/32"))

        // Trusted peer, untrusted client claim: hostAddress renders v6 uncompressed —
        // the contract is "a literal", not a spelling.
        resolver.clientAddressOf(request("::1", xff = "fe80::9, ::1")) shouldBe "fe80:0:0:0:0:0:0:9"
        // Every entry trusted: falls back to the peer.
        resolver.clientAddressOf(request("::1", xff = "2001:db8::9, ::1")) shouldBe "::1"
        // Untrusted peer: the header is ignored.
        resolver.clientAddressOf(request("fe80::1", xff = "2001:db8::9")) shouldBe "fe80::1"
    }

    @Test
    fun `port-suffixed and bracketed XFF entries are parsed to their host`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("10.0.0.5", xff = "203.0.113.9:51444, 10.0.0.5")) shouldBe "203.0.113.9"
        resolver.clientAddressOf(request("10.0.0.5", xff = "[2001:db8::9]:443, 10.0.0.5")) shouldBe "2001:db8:0:0:0:0:0:9"
    }

    @Test
    fun `v4-mapped v6 peers match a plain v4 range`() {
        val resolver = ClientAddressResolver(listOf("10.0.0.0/8"))

        resolver.clientAddressOf(request("::ffff:10.0.0.5", xff = "203.0.113.9, ::ffff:10.0.0.5")) shouldBe "203.0.113.9"
    }

    @Test
    fun `a bare IP entry is a host CIDR`() {
        ClientAddressResolver(listOf("10.0.0.5"))
            .clientAddressOf(request("10.0.0.5", xff = "203.0.113.9, 10.0.0.5")) shouldBe "203.0.113.9"
        // A different address in the same /24 is NOT trusted when only the host is listed.
        ClientAddressResolver(listOf("10.0.0.5"))
            .clientAddressOf(request("10.0.0.6", xff = "203.0.113.9")) shouldBe "10.0.0.6"
    }

    /** §7 — a typo'd range refuses STARTUP (bean construction) rather than trusting it. */
    @Test
    fun `an entry that is not a CIDR refuses startup naming the key`() {
        val thrown = shouldThrow<IllegalArgumentException> { ClientAddressResolver(listOf("10.0.0.0/8", "not-a-cidr")) }

        thrown.message shouldContain "datapipelines.auth.trusted-proxies"
        thrown.message shouldContain "not-a-cidr"
        shouldThrow<IllegalArgumentException> { ClientAddressResolver(listOf("10.0.0.0/33")) }
        shouldThrow<IllegalArgumentException> { ClientAddressResolver(listOf("999.0.0.0/8")) }
    }
}
