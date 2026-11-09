package co.datapipelines.web.config

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * #862 — the egress proxy's whole Envoy configuration, rendered by one deterministic JVM main so Compose and the
 * Helm chart ship the SAME allowlist, derived from the SAME values the app reads (deployment.md §4.3). The
 * non-Spring main runs through `PropertiesLauncher` with `-Dloader.main` (the Dockerfile's `JavaScriptResourcesMain`
 * precedent): no database, no network, no secrets — five non-secret lake keys and the max-age decide the output.
 *
 * The output owns the allowlist: a `store` virtual host whose exact domains are [EgressStoreTarget]'s authority
 * forms, and a catch-all `deny` virtual host that answers 403 before DNS or connect, stamped [REFUSAL_HEADER] —
 * a dynamic-forward-proxy cluster only ever sees the configured store, and the boot check can tell the proxy's
 * refusal from the store's own 403. The `/dp-egress` declaration route lives in BOTH virtual hosts: the
 * boot check ([ManagedLakeEgressCheck]) addresses the proxy itself, whose authority lands in the deny vhost. With
 * the lake disabled there is no store vhost at all — the proxy still declares its cap and refuses everything else,
 * never a wildcard allowance. Options beyond the allowlist mirror the shipped #845 config byte-for-byte in
 * meaning: the max-age cuts streams and retires keep-alives, `timeout: 0s` disables Envoy's 15 s route timeout,
 * no admin listener, no access log, no credentials (S3 signing stays in DuckDB).
 *
 * Inputs are the container environment: [ENV_ENABLED], [ENV_BUCKET], [ENV_ENDPOINT], [ENV_REGION],
 * [ENV_PATH_STYLE], [ENV_MAX_AGE]. Missing or invalid values fail closed — a nonzero exit with an
 * `egress_config:` line on stderr, before Envoy starts (`depends_on: service_completed_successfully`).
 * The output file is written 0644 so the proxy's non-root user reads it; the one summary line names the
 * store authority and the max-age, never a credential (there are none on the input).
 */
object EgressProxyConfigMain {
    /** The renderer's five non-secret inputs and the cap, exactly the names the app binds (application.yml §5). */
    const val ENV_ENABLED = "DATAPIPELINES_LAKE_ENABLED"
    const val ENV_BUCKET = "DATAPIPELINES_LAKE_S3_BUCKET"
    const val ENV_ENDPOINT = "DATAPIPELINES_LAKE_S3_ENDPOINT"
    const val ENV_REGION = "DATAPIPELINES_LAKE_S3_REGION"
    const val ENV_PATH_STYLE = "DATAPIPELINES_LAKE_S3_PATH_STYLE"
    const val ENV_MAX_AGE = "DATAPIPELINES_LAKE_READS_EGRESS_MAX_AGE_SECONDS"

    /** argv[1], where the Envoy config lands (JSON, read by Envoy as YAML); the deployment's volume mount names it. */
    const val DEFAULT_OUTPUT = "/dp-egress-config/envoy.yaml"

    /** The first line of every refusal, so a failed render is greppable in the container's logs. */
    const val REFUSAL_PREFIX = "egress_config:"

    /**
     * The header the proxy stamps on its own allowlist refusal (and strips from every store answer): the boot check's
     * evidence that a 403 is the proxy's, not the store's.
     */
    const val REFUSAL_HEADER = "x-dp-egress-refused"
    const val REFUSAL_VALUE = "store-allowlist"

    /** The proxy's listener; the chart's `datapipelines.egressProxy.port` and compose's service address name it. */
    const val LISTENER_PORT = 3128

    private const val FORWARD_CLUSTER = "dynamic_forward_proxy"
    private const val FORWARD_CLUSTER_TYPE = "type.googleapis.com/envoy.extensions.clusters.dynamic_forward_proxy.v3.ClusterConfig"
    private const val HTTP_OK = 200
    private const val HTTP_FORBIDDEN = 403
    private val DNS_CACHE = mapOf("name" to "egress_dns", "dns_lookup_family" to "V4_PREFERRED")

    /** Pretty, `\n`-terminated, two-space JSON: the same bytes on every platform. */
    private val WRITER =
        ObjectMapper().writer(
            DefaultPrettyPrinter().withObjectIndenter(DefaultIndenter("  ", "\n")).withArrayIndenter(DefaultIndenter("  ", "\n")),
        )

    /**
     * The Envoy configuration for one store: [target] null = the lake is disabled, the deny-only shape.
     *
     * Built as a value tree and SERIALIZED (JSON, which Envoy's YAML loader reads as YAML): no input text is spliced
     * into configuration syntax, so a value can only ever be a JSON string, never structure. Deterministic: the same
     * inputs give byte-identical output (insertion-ordered maps, `\n` line ends) — the helm/compose parity premise.
     */
    fun render(
        maxAgeSeconds: Long,
        target: EgressStoreTarget.Target?,
    ): String {
        val connectionManager =
            mapOf(
                "@type" to "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager",
                "stat_prefix" to "egress",
                "common_http_protocol_options" to
                    mapOf(
                        "max_connection_duration" to "${maxAgeSeconds}s",
                        "max_stream_duration" to "${maxAgeSeconds}s",
                    ),
                "upgrade_configs" to listOf(mapOf("upgrade_type" to "CONNECT")),
                "route_config" to
                    mapOf(
                        "name" to "egress",
                        "virtual_hosts" to listOfNotNull(target?.let { storeVhost(it, maxAgeSeconds) }, denyVhost(maxAgeSeconds)),
                    ),
                "http_filters" to
                    listOf(
                        mapOf(
                            "name" to "envoy.filters.http.dynamic_forward_proxy",
                            "typed_config" to
                                mapOf(
                                    "@type" to "type.googleapis.com/envoy.extensions.filters.http.dynamic_forward_proxy.v3.FilterConfig",
                                    "dns_cache_config" to DNS_CACHE,
                                ),
                        ),
                        mapOf(
                            "name" to "envoy.filters.http.router",
                            "typed_config" to mapOf("@type" to "type.googleapis.com/envoy.extensions.filters.http.router.v3.Router"),
                        ),
                    ),
            )
        val config =
            mapOf(
                "static_resources" to
                    mapOf(
                        "listeners" to
                            listOf(
                                mapOf(
                                    "name" to "egress",
                                    "address" to mapOf("socket_address" to mapOf("address" to "0.0.0.0", "port_value" to LISTENER_PORT)),
                                    "filter_chains" to
                                        listOf(
                                            mapOf(
                                                "filters" to
                                                    listOf(
                                                        mapOf(
                                                            "name" to "envoy.filters.network.http_connection_manager",
                                                            "typed_config" to connectionManager,
                                                        ),
                                                    ),
                                            ),
                                        ),
                                ),
                            ),
                        "clusters" to
                            listOf(
                                mapOf(
                                    "name" to FORWARD_CLUSTER,
                                    "lb_policy" to "CLUSTER_PROVIDED",
                                    "cluster_type" to
                                        mapOf(
                                            "name" to "envoy.clusters.dynamic_forward_proxy",
                                            "typed_config" to
                                                mapOf(
                                                    "@type" to FORWARD_CLUSTER_TYPE,
                                                    "dns_cache_config" to DNS_CACHE,
                                                ),
                                        ),
                                ),
                            ),
                    ),
            )
        return WRITER.writeValueAsString(config) + "\n"
    }

    /**
     * The `/dp-egress` declaration: header-gated (a path-style bucket literally named `dp-egress` is still forwarded)
     * and answered by the proxy itself in every virtual host — it never forwards.
     */
    private fun declarationRoute(maxAgeSeconds: Long) =
        mapOf(
            "match" to
                mapOf(
                    "path" to ManagedLakeEgressCheck.DECLARATION_PATH,
                    "headers" to listOf(mapOf("name" to ManagedLakeEgressCheck.DECLARATION_HEADER, "present_match" to true)),
                ),
            "direct_response" to mapOf("status" to HTTP_OK, "body" to mapOf("inline_string" to "max-age=$maxAgeSeconds")),
        )

    /**
     * The ONE forwarding virtual host: exactly the store's authority forms, CONNECT and absolute-form alike, route
     * timeout disabled (`0s` — Envoy's 15 s default would cut a healthy transfer; the max-age is the bound). A store
     * answer carrying the refusal marker loses it, so only the proxy's own refusal ever carries it.
     */
    private fun storeVhost(
        target: EgressStoreTarget.Target,
        maxAgeSeconds: Long,
    ) = mapOf(
        "name" to "store",
        "domains" to target.domains,
        "response_headers_to_remove" to listOf(REFUSAL_HEADER),
        "routes" to
            listOf(
                declarationRoute(maxAgeSeconds),
                mapOf(
                    "match" to mapOf("connect_matcher" to emptyMap<String, Any>()),
                    "route" to
                        mapOf(
                            "cluster" to FORWARD_CLUSTER,
                            "timeout" to "0s",
                            "upgrade_configs" to listOf(mapOf("upgrade_type" to "CONNECT", "connect_config" to emptyMap<String, Any>())),
                        ),
                ),
                mapOf("match" to mapOf("prefix" to "/"), "route" to mapOf("cluster" to FORWARD_CLUSTER, "timeout" to "0s")),
            ),
    )

    /**
     * Every other authority: answered 403 by the proxy before any DNS lookup or connect, in both request forms, with
     * [REFUSAL_HEADER] so the boot check ([ManagedLakeEgressCheck]) tells this refusal from a store's own 403. The
     * declaration route lives here too: the boot check addresses the proxy itself, whose authority lands here.
     */
    private fun denyVhost(maxAgeSeconds: Long) =
        mapOf(
            "name" to "deny",
            "domains" to listOf("*"),
            "routes" to
                listOf(
                    declarationRoute(maxAgeSeconds),
                    refusalRoute(mapOf("connect_matcher" to emptyMap<String, Any>()), "CONNECT to"),
                    refusalRoute(mapOf("prefix" to "/"), "requests to"),
                ),
        )

    private fun refusalRoute(
        match: Map<String, Any>,
        form: String,
    ) = mapOf(
        "match" to match,
        "direct_response" to
            mapOf(
                "status" to HTTP_FORBIDDEN,
                "body" to mapOf("inline_string" to "egress proxy: $form a host that is not the configured object store is refused"),
            ),
        "response_headers_to_add" to listOf(mapOf("header" to mapOf("key" to REFUSAL_HEADER, "value" to REFUSAL_VALUE))),
    )

    /** Reads the environment, renders and writes; the exit code is the container's verdict. */
    fun run(
        args: Array<String>,
        env: (String) -> String? = System::getenv,
        out: (String) -> Unit = ::println,
        err: (String) -> Unit = System.err::println,
    ): Int =
        try {
            val output = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_OUTPUT
            val enabled = boolean(env, ENV_ENABLED, err)
            val maxAge = maxAge(env, err)
            val target =
                if (enabled) {
                    // The derivation's refusals are named causes; as renderer input they are the same
                    // fail-closed verdict, carrying the cause onto stderr before the nonzero exit.
                    try {
                        EgressStoreTarget.derive(
                            endpoint = env(ENV_ENDPOINT) ?: "",
                            region = env(ENV_REGION) ?: "",
                            bucket = env(ENV_BUCKET) ?: "",
                            pathStyle = boolean(env, ENV_PATH_STYLE, err),
                        )
                    } catch (refusedInput: IllegalStateException) {
                        fail(err, refusedInput.message ?: "the store inputs are underivable")
                    }
                } else {
                    null
                }
            write(output, render(maxAge, target), err)
            out(
                "event=egress_config_written output=$output max_age_seconds=$maxAge enabled=$enabled " +
                    "store=${target?.domains?.singleOrNull() ?: "none"}",
            )
            0
        } catch (
            @Suppress("SwallowedException")
            refused: EgressConfigRefusal,
        ) {
            1
        }

    /** A strict boolean: only true/false bind; anything else refuses rather than guessing. */
    private fun boolean(
        env: (String) -> String?,
        name: String,
        err: (String) -> Unit,
    ): Boolean {
        val raw = env(name)?.trim()?.lowercase()
        return when (raw) {
            null, "" -> false
            "true" -> true
            "false" -> false
            else -> fail(err, "$name '$raw' is not a boolean")
        }
    }

    private fun maxAge(
        env: (String) -> String?,
        err: (String) -> Unit,
    ): Long {
        val raw = env(ENV_MAX_AGE)?.trim()
        val parsed = raw?.toLongOrNull() ?: fail(err, "$ENV_MAX_AGE '${raw ?: ""}' is not a positive integer")
        if (parsed <= 0) fail(err, "$ENV_MAX_AGE '$raw' must be positive")
        return parsed
    }

    /**
     * Writes the one fixed output. An unwritable target — the read-only file a root renderer left in an old
     * compose volume (#862 review R4, deployment.md §6.4), a path that is a directory — is the same fail-closed
     * refusal as a bad input: one `egress_config:` line naming the path and the cause, exit 1, never a stack trace.
     */
    private fun write(
        output: String,
        yaml: String,
        err: (String) -> Unit,
    ) {
        val path = Path.of(output)
        try {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                path,
                yaml,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        } catch (unwritable: IOException) {
            fail(err, "cannot write $output (${unwritable.javaClass.simpleName}): the config path must be writable by the renderer's user")
        }
    }

    private fun fail(
        err: (String) -> Unit,
        message: String,
    ): Nothing {
        err("$REFUSAL_PREFIX $message")
        throw EgressConfigRefusal()
    }

    /** The typed refusal; [fail] has already put the `egress_config:` line on stderr. */
    class EgressConfigRefusal : RuntimeException()

    /** The JVM entrypoint: a failed render is a nonzero exit, before Envoy starts. */
    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args))
    }
}
