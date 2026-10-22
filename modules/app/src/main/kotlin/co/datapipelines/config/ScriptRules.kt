package co.datapipelines.config

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * §7 / §3.28 (7b, #7) — the script evaluation budgets: one case must fit inside its
 * suite (`evaluate-timeout-seconds` ≤ `suite-timeout-seconds`), the abandonment grace must
 * be at least one second (a zero grace would abandon every slow case at the instant its own
 * wall clock ends, with no room for the engine's between-steps check to report first —
 * record §4.5), the pool admits at least one running evaluation, and the admitted total is
 * never smaller than the running set. Every cap is ≥ 1. Missing values are not a violation:
 * application.yml always supplies them.
 *
 * Owns its file like `PostureRules` and `MailRules` (§3.23/§3.27) — the validator's
 * companion is the registry, not the home, of the §3.x families.
 */
internal object ScriptRules {
    /** The nine `datapipelines.transform.*` keys the rename removed (configuration.md §3.28). */
    internal val REMOVED_TRANSFORM_KEY_SUFFIXES =
        listOf(
            "evaluate-timeout-seconds",
            "suite-timeout-seconds",
            "pool-size",
            "pool-queue",
            "abandon-grace-seconds",
            "max-input-rows",
            "max-value-bytes",
            "max-string-bytes",
            "max-depth",
        )

    /**
     * §7 / §3.28 — the keys the TRANSFORM→SCRIPT node-type rename REMOVED, refused BY NAME
     * (script-node-rename lane, owner ruling 2026-10-20).
     *
     * The script knobs moved from `datapipelines.transform.*` to `datapipelines.script.*`
     * with no alias: the properties class binds the new tree, so a stale `transform` key
     * would bind NOTHING and the deployment would silently run on defaults — the exact
     * failure mode `checkRemovedWorkspaceKeys` refuses to allow. Any of the nine keys set
     * at all is refused, naming the old key and the replacement.
     */
    fun checkRemovedTransformKeys(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        if (!snapshot.transformLegacyKeysSet) return
        violations +=
            "datapipelines.transform.* is set but was REMOVED by the TRANSFORM→SCRIPT node-type " +
            "rename (§3.28): the script knobs now live under datapipelines.script.* and there is " +
            "no alias — a datapipelines.transform.* key binds nothing. Rename the key(s): " +
            REMOVED_TRANSFORM_KEY_SUFFIXES.joinToString(", ") { "datapipelines.transform.$it" } + "."
    }

    fun checkScriptBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val values =
            listOf(
                "evaluate-timeout-seconds" to snapshot.scriptEvaluateTimeoutSeconds,
                "suite-timeout-seconds" to snapshot.scriptSuiteTimeoutSeconds,
                "abandon-grace-seconds" to snapshot.scriptAbandonGraceSeconds,
                "pool-size" to snapshot.scriptPoolSize,
                "pool-queue" to snapshot.scriptPoolQueue,
                "max-input-rows" to snapshot.scriptMaxInputRows,
                "max-value-bytes" to snapshot.scriptMaxValueBytes,
                "max-string-bytes" to snapshot.scriptMaxStringBytes,
                "max-depth" to snapshot.scriptMaxDepth,
            )
        // An unset block is not a violation (application.yml always supplies all nine);
        // a partial block checks what it has.
        if (values.all { (_, value) -> value == null } && snapshot.scriptJs.isEmpty()) return
        values.forEach { (key, value) ->
            if (value != null && value < 1) {
                violations += "datapipelines.script.$key ($value) must be >= 1 (§7)."
            }
        }
        javascriptBounds(snapshot, violations)
        val evaluate = snapshot.scriptEvaluateTimeoutSeconds
        val suite = snapshot.scriptSuiteTimeoutSeconds
        if (evaluate != null && suite != null && evaluate > suite) {
            violations +=
                "datapipelines.script.evaluate-timeout-seconds ($evaluate) must not exceed " +
                "datapipelines.script.suite-timeout-seconds ($suite) — one case must fit inside its suite (§7)."
        }
        val poolSize = snapshot.scriptPoolSize
        val poolQueue = snapshot.scriptPoolQueue
        if (poolSize != null && poolQueue != null && poolQueue < poolSize) {
            violations +=
                "datapipelines.script.pool-queue ($poolQueue) must be >= datapipelines.script.pool-size " +
                "($poolSize) — the admitted total cannot be smaller than the running set (§7)."
        }
    }

    /** All numeric keys are positive; output streams need at least 4096 bytes under UNTRUSTED. */
    private fun javascriptBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val prefix = "datapipelines.script.js."
        val numbers =
            snapshot.scriptJs
                .filterKeys { it != "${prefix}isolate-dir" }
                .mapValues { (key, raw) -> javascriptNumber(key, raw, violations) }
        val heap = numbers["${prefix}max-heap-mb"] ?: DEFAULT_HEAP_MB
        val pool = snapshot.scriptPoolSize ?: DEFAULT_POOL_SIZE
        val isolate = numbers["${prefix}isolate-memory-mb"]
        val concurrentHeap = java.math.BigInteger.valueOf(heap) * java.math.BigInteger.valueOf(pool)
        if (isolate != null && concurrentHeap >= java.math.BigInteger.valueOf(isolate)) {
            violations += "${prefix}max-heap-mb ($heap) × datapipelines.script.pool-size ($pool) must be < " +
                "${prefix}isolate-memory-mb ($isolate): all pool slots share one isolate."
        }
        if (isolate != null && isolate < MIN_ISOLATE_MB) {
            violations += "${prefix}isolate-memory-mb ($isolate) must be >= $MIN_ISOLATE_MB (GraalJS isolate floor)."
        }
        val derived =
            concurrentHeap + java.math.BigInteger.valueOf(ISOLATE_HEADROOM_MB)
        if (isolate == null && derived > java.math.BigInteger.valueOf(Long.MAX_VALUE / MIB)) {
            violations += "${prefix}max-heap-mb × datapipelines.script.pool-size + $ISOLATE_HEADROOM_MB overflows the isolate byte budget."
        }
        isolateAgreement(snapshot, violations)
    }

    /** Parse without a binder exception, preserving the offending key in every numeric refusal. */
    private fun javascriptNumber(
        key: String,
        raw: String?,
        violations: MutableList<String>,
    ): Long? {
        if (raw.isNullOrBlank()) return null
        val value = raw.toLongOrNull()
        val minimum = if (key.endsWith("max-output-bytes")) MIN_OUTPUT_BYTES else 1L
        val maximum = if (key.endsWith("max-statements")) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
        if (value == null || value !in minimum..maximum) {
            violations += "$key ($raw) must be an integer in $minimum..$maximum."
        }
        return value
    }

    /**
     * PropertiesLauncher 3.5.16's process-level precedence, from launch.SystemPropertyUtils:
     * system loader.path, then environment loader.path, loader_path, LOADER_PATH. An empty
     * higher-priority value still wins; Spring application properties do not control loading.
     */
    fun effectiveLoaderPath(
        systemProperty: String? = System.getProperty("loader.path"),
        environment: Map<String, String> = System.getenv(),
    ): String? = systemProperty ?: environment["loader.path"] ?: environment["loader_path"] ?: environment["LOADER_PATH"]

    /**
     * Spring Boot 3.5.16 PropertiesLauncher.getPaths/getProperty uses SystemPropertyUtils:
     * process-level precedence is [effectiveLoaderPath]; entries are comma-separated and trimmed.
     * This only verifies agreement; it never modifies the class path. A configured directory
     * must contain the image's exact js-isolate.jar name. An unset directory permits Gradle's
     * test-runtime isolate on the class path, or a jar with JavaScript disabled.
     */
    private fun isolateAgreement(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val key = "datapipelines.script.js.isolate-dir"
        val raw = snapshot.scriptJs[key]?.takeIf { it.isNotBlank() } ?: return
        val dir = runCatching { Path.of(raw) }.getOrNull()
        if (dir == null || !dir.isAbsolute) {
            violations += "$key ($raw) must be an absolute directory."
            return
        }
        val entries =
            snapshot.scriptJsLoaderPath.orEmpty().split(',').filter { it.isNotBlank() }.mapNotNull { entry ->
                runCatching { Path.of(entry.trim().removeSuffix("*")).toAbsolutePath().normalize() }.getOrNull()
            }
        if (dir.normalize() !in entries) {
            violations += "$key ($raw) must be one entry of the launcher's loader.path / LOADER_PATH list."
        }
        if (!dir.resolve("js-isolate.jar").isRegularFile()) {
            violations += "$key ($raw) must contain js-isolate.jar."
        }
    }

    private const val MIB = 1024L * 1024L
    private const val MIN_ISOLATE_MB = 32L
    private const val ISOLATE_HEADROOM_MB = 256L
    private const val MIN_OUTPUT_BYTES = 4096L
    private const val DEFAULT_HEAP_MB = 256L
    private const val DEFAULT_POOL_SIZE = 4L
}
