package co.datapipelines.auth

import jakarta.servlet.http.HttpServletRequest
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The single client-address resolution behind [LoginRateLimitFilter] and every auth-side
 * audit log / `source_ip` column (R8/T46, deployment.md §6.2): behind the documented load
 * balancer `request.remoteAddr` is the LB's address for EVERY request, so anything keyed or
 * recorded on it collapses to one deployment-wide value — the login limiter's per-IP budget
 * becomes a single bucket any client can 429 for everyone else.
 *
 * Resolution (the spoof-safe reading — an untrusted peer cannot forge its way past the
 * limiter by setting a header):
 *
 * 1. The direct peer (`remoteAddr`) is NOT in `datapipelines.auth.trusted-proxies` → the
 *    peer IS the client; `X-Forwarded-For` is ignored entirely. This is also the shipped
 *    default: the list is empty, so a bare deployment behaves exactly as before this class
 *    existed.
 * 2. The peer IS trusted → walk `X-Forwarded-For` **right to left** (rightmost entry = the
 *    nearest proxy, leftmost = the claimed original client) and return the first entry NOT
 *    in the trusted list. Everything left of that hop is an unauthenticated claim; the
 *    first untrusted hop is the effective client.
 * 3. The header is absent, empty, malformed, or entirely trusted → the peer itself.
 *
 * Constructed once as a bean: the constructor validates every configured entry parses as a
 * CIDR and REFUSES STARTUP otherwise (configuration.md §7) — a typo'd range must not
 * silently disable proxy trust. A bare IP is accepted as a host CIDR (`/32`, `/128`).
 */
class ClientAddressResolver(
    trustedProxies: List<String>,
) {
    private val trusted: List<Cidr> =
        trustedProxies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                Cidr.parse(entry)
                    ?: throw IllegalArgumentException(
                        "datapipelines.auth.trusted-proxies entry '$entry' is not a CIDR " +
                            "(configuration.md \u00a73.4 — e.g. 10.0.0.0/8 or 2001:db8::/32; a bare IP is a host CIDR). " +
                            "Startup is refused rather than trusting a misread range.",
                    )
            }

    /** The client address for [request], per the class KDoc's three rules. */
    fun clientAddressOf(request: HttpServletRequest): String = forwarded(request) ?: request.remoteAddr.orEmpty()

    /**
     * The forwarded client, or null when the peer is the client: no proxies trusted, an
     * unparseable or untrusted peer, or a header that resolves nothing usable.
     */
    private fun forwarded(request: HttpServletRequest): String? {
        val peer = request.remoteAddr.orEmpty()
        val peerIp = peer.toInetAddress() ?: return null
        if (trusted.isEmpty() || trusted.none { it.contains(peerIp) }) return null
        return request.getHeader(XFF_HEADER)?.let { forwardedClient(it) }
    }

    /**
     * The rightmost `X-Forwarded-For` hop that is not itself trusted, or null when the
     * chain is empty, malformed (trust nothing beyond the peer), or entirely trusted.
     */
    private fun forwardedClient(header: String): String? {
        for (entry in header.split(',').asReversed()) {
            val hop = entry.trim()
            if (hop.isEmpty()) continue
            val ip = hop.toInetAddress() ?: return null
            if (trusted.none { it.contains(ip) }) return ip.hostAddress
        }
        return null
    }

    private companion object {
        const val XFF_HEADER = "X-Forwarded-For"
    }
}

/**
 * One CIDR network. IP-family-aware: a v4 address never matches a v6 range (v4-mapped v6
 * addresses are normalized to v4 on both sides first, so Tomcat's mapped `remoteAddr`
 * forms still match a plain v4 range).
 */
private class Cidr private constructor(
    private val address: ByteArray,
    private val prefixBits: Int,
) {
    // Byte widths, prefix arithmetic and RFC 4291 marker offsets are the DOMAIN here —
    // naming 16 as V6_BYTES adds no information a reader does not already have.
    @Suppress("MagicNumber")
    fun contains(ip: InetAddress): Boolean {
        val candidate = ip.normalize().address
        if (candidate.size != address.size) return false
        val fullBytes = prefixBits / 8
        val remBits = prefixBits % 8
        for (i in 0 until fullBytes) {
            if (candidate[i] != address[i]) return false
        }
        if (remBits == 0) return true
        val mask = (0xFF shl (8 - remBits)).toByte()
        return (candidate[fullBytes].toInt() and mask.toInt()) == (address[fullBytes].toInt() and mask.toInt())
    }

    companion object {
        /** `10.0.0.0/8` (a bare IP = host CIDR), or null when [raw] is neither. */
        fun parse(raw: String): Cidr? {
            val (host, prefix) = splitHostPrefix(raw) ?: return null
            val addr = host.toLiteralInetAddress()?.normalize() ?: return null
            val maxBits = addr.address.size * BITS_PER_BYTE
            val bits = prefix ?: maxBits
            if (bits !in 0..maxBits) return null
            return Cidr(addr.address, bits)
        }

        private const val BITS_PER_BYTE = 8

        /**
         * `[v6]/bits`, `[v6]`, `v4/bits`, or a bare host — host plus prefix length (null =
         * host CIDR). A prefix that is not a plain integer makes the whole entry null.
         */
        private fun splitHostPrefix(raw: String): Pair<String, Int?>? {
            if (!raw.startsWith("[")) {
                val slash = raw.indexOf('/')
                return if (slash < 0) {
                    raw to null
                } else {
                    raw.substring(0, slash) to raw.substring(slash + 1).toIntOrNull()
                }
            }
            val close = raw.indexOf(']')
            val tail = if (close >= 1) raw.substring(close + 1) else ""
            val tailOk = tail.isEmpty() || (tail.startsWith("/") && tail.length > 1)
            if (close < 1 || !tailOk) return null
            val bits = if (tail.isEmpty()) null else tail.substring(1).toIntOrNull()
            return raw.substring(1, close) to bits
        }
    }
}

/**
 * Parses an IP **literal** — never a hostname, so no DNS lookup can hang a request thread
 * on attacker-controlled header content. Accepts the forms `X-Forwarded-For` realistically
 * carries: bare v4/v6, `[v6]`, and `host:port` / `[v6]:port`. Null for anything else.
 */
private fun String.toInetAddress(): InetAddress? = stripForwardedHost()?.toLiteralInetAddress()

/** Strips `[v6]` brackets and a trailing `:port` (single colon = v4 host:port). */
private fun String.stripForwardedHost(): String? {
    var host = trim()
    if (host.isEmpty()) return null
    if (!host.startsWith("[")) {
        if (host.count { it == ':' } == 1) host = host.substringBeforeLast(':')
        return host
    }
    val close = host.indexOf(']')
    val tail = if (close >= 1) host.substring(close + 1) else ""
    val tailOk = tail.isEmpty() || (tail.startsWith(":") && tail.length > 1)
    if (close < 1 || !tailOk) return null
    return host.substring(1, close)
}

/**
 * A strict literal: dotted-quad v4 with octets 0–255, or anything containing `:` (a v6
 * literal — hostnames cannot contain colons, and `InetAddress.getByName` parses colon-form
 * literals without DNS). Everything else is rejected before `getByName` is ever called.
 */
@Suppress("MagicNumber") // octet count, digit bound and 255 are the grammar of a dotted quad
private fun String.toLiteralInetAddress(): InetAddress? {
    if (!contains(':')) {
        val parts = split('.')
        // toIntOrNull covers empty and overlong parts: "" and "9999" both fail the range.
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
    }
    return runCatching { InetAddress.getByName(this) }.getOrNull()
}

/** v4-mapped v6 (`::ffff:10.0.0.1`) → v4, so mapped and plain forms of one address compare equal. */
@Suppress("MagicNumber") // the v4-mapped layout (10 zero bytes, two 0xFF markers, 4 payload) is RFC 4291
private fun InetAddress.normalize(): InetAddress {
    if (this is Inet4Address) return this
    val bytes = address
    if (bytes.size != 16 || !hasMappedLayout(bytes)) return this
    return InetAddress.getByAddress(bytes.copyOfRange(12, 16)) as Inet4Address
}

@Suppress("MagicNumber")
private fun hasMappedLayout(bytes: ByteArray): Boolean =
    bytes.take(10).all { it == 0.toByte() } &&
        bytes[10] == 0xFF.toByte() &&
        bytes[11] == 0xFF.toByte()
