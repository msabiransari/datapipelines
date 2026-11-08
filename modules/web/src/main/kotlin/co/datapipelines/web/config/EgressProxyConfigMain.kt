package co.datapipelines.web.config

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
 * forms, and a catch-all `deny` virtual host that answers 403 before DNS or connect — a dynamic-forward-proxy
 * cluster only ever sees the configured store. The `/dp-egress` declaration route lives in BOTH virtual hosts: the
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

    /** argv[1], where the Envoy YAML lands; the deployment's volume mount names the same path. */
    const val DEFAULT_OUTPUT = "/dp-egress-config/envoy.yaml"

    /** The first line of every refusal, so a failed render is greppable in the container's logs. */
    const val REFUSAL_PREFIX = "egress_config:"

    private const val FORWARD_CLUSTER = "dynamic_forward_proxy"

    /**
     * The Envoy YAML for one configuration: [target] null = the lake is disabled, the deny-only shape.
     * Deterministic: the same inputs render byte-identical output (the helm/compose parity premise).
     */
    fun render(
        maxAgeSeconds: Long,
        target: EgressStoreTarget.Target?,
    ): String {
        val vhosts = StringBuilder()
        if (target != null) {
            val domains = target.domains.joinToString(", ") { "\"$it\"" }
            vhosts.append(
                """
                |                    - name: store
                |                      domains: [$domains]
                |                      routes:
                |                        - match: { path: /dp-egress, headers: [{ name: x-dp-egress-check, present_match: true }] }
                |                          direct_response: { status: 200, body: { inline_string: "max-age=$maxAgeSeconds" } }
                |                        - match: { connect_matcher: {} }
                |                          route:
                |                            cluster: $FORWARD_CLUSTER
                |                            timeout: 0s
                |                            upgrade_configs:
                |                              - upgrade_type: CONNECT
                |                                connect_config: {}
                |                        - match: { prefix: / }
                |                          route:
                |                            cluster: $FORWARD_CLUSTER
                |                            timeout: 0s
                """.trimMargin(),
            ).append('\n')
        }
        return """
            |static_resources:
            |  listeners:
            |    - name: egress
            |      address:
            |        socket_address: { address: 0.0.0.0, port_value: 3128 }
            |      filter_chains:
            |        - filters:
            |            - name: envoy.filters.network.http_connection_manager
            |              typed_config:
            |                "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
            |                stat_prefix: egress
            |                common_http_protocol_options:
            |                  max_connection_duration: ${maxAgeSeconds}s
            |                  max_stream_duration: ${maxAgeSeconds}s
            |                upgrade_configs:
            |                  - upgrade_type: CONNECT
            |                route_config:
            |                  name: egress
            |                  virtual_hosts:
            |$vhosts
            |                    - name: deny
            |                      domains: ["*"]
            |                      routes:
            |                        - match: { path: /dp-egress, headers: [{ name: x-dp-egress-check, present_match: true }] }
            |                          direct_response: { status: 200, body: { inline_string: "max-age=$maxAgeSeconds" } }
            |                        - match: { connect_matcher: {} }
            |                          direct_response: { status: 403, body: { inline_string: "egress proxy: CONNECT to a host that is not the configured object store is refused" } }
            |                        - match: { prefix: / }
            |                          direct_response: { status: 403, body: { inline_string: "egress proxy: requests to a host that is not the configured object store are refused" } }
            |                http_filters:
            |                  - name: envoy.filters.http.dynamic_forward_proxy
            |                    typed_config:
            |                      "@type": type.googleapis.com/envoy.extensions.filters.http.dynamic_forward_proxy.v3.FilterConfig
            |                      dns_cache_config: { name: egress_dns, dns_lookup_family: V4_PREFERRED }
            |                  - name: envoy.filters.http.router
            |                    typed_config:
            |                      "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
            |  clusters:
            |    - name: $FORWARD_CLUSTER
            |      lb_policy: CLUSTER_PROVIDED
            |      cluster_type:
            |        name: envoy.clusters.dynamic_forward_proxy
            |        typed_config:
            |          "@type": type.googleapis.com/envoy.extensions.clusters.dynamic_forward_proxy.v3.ClusterConfig
            |          dns_cache_config: { name: egress_dns, dns_lookup_family: V4_PREFERRED }
            """.trimMargin() + "\n"
    }

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
            write(output, render(maxAge, target))
            out(
                "event=egress_config_written output=$output max_age_seconds=$maxAge enabled=$enabled " +
                    "store=${target?.domains?.singleOrNull() ?: "none"}",
            )
            0
        } catch (refused: EgressConfigRefusal) {
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

    private fun write(
        output: String,
        yaml: String,
    ) {
        val path = Path.of(output)
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            path,
            yaml,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
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
