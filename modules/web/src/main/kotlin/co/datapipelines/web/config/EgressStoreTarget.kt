package co.datapipelines.web.config

import co.datapipelines.lake.LakeCatalogs
import java.net.URI

/**
 * #862 — the ONE derivation of the managed lake store's wire authority, shared by the boot check
 * ([ManagedLakeEgressCheck]), the managed datasource's DuckDB secret ([ManagedLakeDatasourceRegistrar]) and the
 * egress proxy's renderer ([EgressProxyConfigMain]), so every consumer derives the same host the engine puts on the
 * wire (deployment.md §4.3).
 *
 * The forms come from probe A (lane 862 evidence, DuckDB httpfs v1.5.5 driven through a recording proxy): a virtual
 * host style authority prefixes the bucket; an explicitly declared port travels verbatim; a CONNECT tunnel always
 * carries its port (an https store is therefore `host:443`); a plain-http store on its default port puts a BARE host
 * on the wire. A blank endpoint resolves to the REGIONAL `s3.<region>.amazonaws.com` (default region us-east-1),
 * byte-identical whether the engine picks it itself or the datasource declares it — the derivation emits it
 * explicitly so one form covers both.
 *
 * The domain list is exact, never wider than observed behavior: one form per configuration, no port aliases, so the
 * proxy's allowlist cannot admit a neighbor port a client would never dial.
 *
 * Refusals are [IllegalStateException]s with the cause named: the renderer turns them into a fail-closed exit before
 * Envoy starts; in the boot path they are practically unreachable behind the §7 endpoint grammar.
 */
object EgressStoreTarget {
    /** The HTTPS default port; a CONNECT tunnel always carries it (probe A). */
    private const val HTTPS_PORT = 443

    /** The inclusive top of the port range; the bottom (1) needs no name. */
    private const val MAX_PORT = 65_535

    /** A derived or declared host: letters, digits, dots and dashes, starting and ending alphanumeric. */
    private val HOST_GRAMMAR = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

    /** The RFC 1035/2396 host bound; a longer value cannot resolve the way the engine would dial it. */
    private const val MAX_HOST_LENGTH = 253

    /**
     * The store as every consumer of one configuration addresses it.
     * [domains] the exact Envoy vhost forms (one per configuration); [probeUri] the boot check's HEAD target, the
     * same scheme/host/port/path forms the engine dials; [duckdbEndpoint] `scheme://host[:port]` for the adapter's
     * secret, whose scheme decides USE_SSL; [region] the effective signing region (the default when blank).
     */
    data class Target(
        val domains: List<String>,
        val probeUri: URI,
        val duckdbEndpoint: String,
        val region: String,
    )

    /** Derives the store's authority from the four non-secret storage values; refuses an underivable configuration. */
    fun derive(
        endpoint: String,
        region: String,
        bucket: String,
        pathStyle: Boolean,
    ): Target {
        val name = bucket.trim()
        if (name.isEmpty()) error("the bucket is empty, so no store authority can be derived (§3.36)")
        val effectiveRegion = region.trim().ifEmpty { LakeCatalogs.DEFAULT_SIGNING_REGION }
        val trimmed = endpoint.trim().trimEnd('/')
        val (scheme, host, port) =
            when {
                trimmed.isEmpty() -> Triple("https", "s3.$effectiveRegion.amazonaws.com", null)
                trimmed.startsWith("https://", ignoreCase = true) -> parseAuthority("https", trimmed.substring("https://".length))
                trimmed.startsWith("http://", ignoreCase = true) -> parseAuthority("http", trimmed.substring("http://".length))
                else -> error("the endpoint '$endpoint' carries no http:// or https:// scheme (§3.36)")
            }
        val authorityHost = if (pathStyle) host else "$name.$host"
        validateHost(authorityHost)
        // The CONNECT port travels always; the plain-http default port is bare (probe A cases C/D).
        val effectivePort = port ?: if (scheme == "https") HTTPS_PORT else null
        val authority = if (effectivePort != null) "$authorityHost:$effectivePort" else authorityHost
        val suffix = port?.let { ":$it" } ?: ""
        val probeUri =
            if (pathStyle) {
                URI("$scheme://$host$suffix/$name")
            } else {
                URI("$scheme://$authorityHost$suffix/")
            }
        return Target(listOf(authority), probeUri, "$scheme://$host$suffix", effectiveRegion)
    }

    /** Splits a scheme-stripped `host[:port]` tail; a bracketed literal or malformed port is refused. */
    private fun parseAuthority(
        scheme: String,
        tail: String,
    ): Triple<String, String, Int?> {
        if (tail.contains('[') || tail.contains(']')) {
            error("an IPv6 literal endpoint is refused: declare a resolvable hostname (§3.36)")
        }
        val colon = tail.lastIndexOf(':')
        if (colon <= 0) return Triple(scheme, tail, null)
        val port = tail.substring(colon + 1)
        if (port.isEmpty() || port.any { !it.isDigit() }) {
            error("the endpoint's ':$port' is not a port (§3.36)")
        }
        val parsed = port.toInt()
        if (parsed !in 1..MAX_PORT) error("the endpoint's port $parsed is outside 1..65535 (§3.36)")
        return Triple(scheme, tail.substring(0, colon), parsed)
    }

    private fun validateHost(host: String) {
        if (host.length > MAX_HOST_LENGTH || !HOST_GRAMMAR.matches(host)) {
            error("the derived authority host '$host' is not a dialable hostname (letters, digits, '.', '-')")
        }
    }
}
