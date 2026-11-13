package co.datapipelines.web.config

import co.datapipelines.lake.LakeCatalogs
import java.net.URI

/**
 * #862 — the ONE derivation of the managed lake store's wire authority, shared by the boot check
 * ([ManagedLakeEgressCheck]), the managed datasource's DuckDB secret ([ManagedLakeDatasourceRegistrar]) and the
 * egress proxy's renderer ([EgressProxyConfigMain]), so every consumer derives the same host the engine puts on the
 * wire (deployment.md §4.3).
 *
 * The forms come from probe A (lane 862 evidence, measured 2026-11-08 with DuckDB httpfs v1.5.5 through a recording proxy): a virtual
 * host style authority prefixes the bucket; an explicitly declared non-default port travels verbatim; a CONNECT
 * tunnel always carries its port (an https store is therefore `host:443`); a plain-http store on its default port
 * puts a BARE host on the wire — and so does an explicitly declared `:80` (probe A2: the engine drops it from the
 * absolute-form URL), while a declared `:443` is the CONNECT line it would carry anyway. A blank endpoint resolves
 * to the REGIONAL `s3.<region>.<partition DNS suffix>` (default region us-east-1), and the derivation emits it
 * explicitly to the engine too, so engine, boot probe and proxy share one form
 * even where the engine's own blank-endpoint default would name another partition's host.
 *
 * The partition table is [PARTITIONS], copied from the AWS SDK's own partition metadata (primary source:
 * `software/amazon/awssdk/regions/internal/region/endpoints.json` in `software.amazon.awssdk:regions:2.46.7`, the
 * SDK this build pins): each partition's `regionRegex` and `dnsSuffix`, for the three partitions whose S3 regional
 * endpoints are `s3.<region>.<dnsSuffix>`. A region outside them (an isolated partition, a typo, an alias such as
 * `aws-global`) is refused with a request to declare the endpoint — never widened to a guessed or wildcard host.
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

    /** The plain-HTTP default port; the engine never puts it in an absolute-form URL (probe A2, case L). */
    private const val HTTP_PORT = 80

    /** The inclusive top of the port range; the bottom (1) needs no name. */
    private const val MAX_PORT = 65_535

    /** A derived or declared host: letters, digits, dots and dashes, starting and ending alphanumeric. */
    private val HOST_GRAMMAR = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

    /** The RFC 1035/2396 host bound; a longer value cannot resolve the way the engine would dial it. */
    private const val MAX_HOST_LENGTH = 253

    /**
     * One AWS partition as the SDK's partition metadata names it: the regions it owns ([regionRegex], verbatim from
     * `endpoints.json`) and the DNS suffix its S3 regional endpoints use.
     */
    internal data class Partition(
        val name: String,
        val regionRegex: Regex,
        val dnsSuffix: String,
    )

    /**
     * `endpoints.json` (AWS SDK 2.46.7) `partitions[].{partition, regionRegex, dnsSuffix}` for the partitions the
     * blank-endpoint form supports. The regexes are disjoint (`us-gov-west-1` does not match the `aws` pattern:
     * `\w` admits no `-`). Lane 862 evidence keeps the extraction command and its output.
     */
    internal val PARTITIONS =
        listOf(
            Partition("aws", Regex("^(us|eu|ap|sa|ca|me|af|il|mx)\\-\\w+\\-\\d+$"), "amazonaws.com"),
            Partition("aws-cn", Regex("^cn\\-\\w+\\-\\d+$"), "amazonaws.com.cn"),
            Partition("aws-us-gov", Regex("^us\\-gov\\-\\w+\\-\\d+$"), "amazonaws.com"),
        )

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
        val (scheme, host, declaredPort) =
            when {
                trimmed.isEmpty() -> Triple("https", regionalAwsHost(effectiveRegion), null)
                trimmed.startsWith("https://", ignoreCase = true) -> parseAuthority("https", trimmed.substring("https://".length))
                trimmed.startsWith("http://", ignoreCase = true) -> parseAuthority("http", trimmed.substring("http://".length))
                else -> error("the endpoint '$endpoint' carries no http:// or https:// scheme (§3.36)")
            }
        // A declared DEFAULT port is the no-port form on the wire (probe A2, lane 862 review R5): the engine writes
        // `http://host/` for `host:80` (case L — Envoy routes on that URL authority, so `host:80` would never match)
        // and `CONNECT host:443` for `host:443` (case M, the same line as no port). One configuration, one form.
        val port = declaredPort?.takeUnless { it == (if (scheme == "https") HTTPS_PORT else HTTP_PORT) }
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

    /**
     * The S3 regional host for a blank endpoint: `s3.<region>.<dnsSuffix>` of the ONE partition whose region regex
     * matches; any other region is refused rather than guessed (the engine would dial another partition's host).
     */
    private fun regionalAwsHost(region: String): String {
        val partition =
            PARTITIONS.singleOrNull { it.regionRegex.matches(region) }
                ?: error(
                    "the region '$region' is in none of the supported AWS partitions " +
                        "(${PARTITIONS.joinToString { it.name }}), so a blank endpoint has no verified store host: " +
                        "declare datapipelines.lake.storage.endpoint explicitly (§3.36)",
                )
        return "s3.$region.${partition.dnsSuffix}"
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
        // A leading zero is a second spelling of the same number: the engine puts the declared text on the wire, so
        // the derived authority could not equal it — refused rather than normalized into a form nobody dials.
        if (port.length > 1 && port.startsWith('0')) error("the endpoint's port ':$port' has a leading zero (§3.36)")
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
