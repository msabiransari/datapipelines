package co.datapipelines.config

/**
 * §7 / §3.31 (#279) — the platform's request-body cap. Owns its file like `TransformRules` and
 * `ParametersRules` — the validator's companion is the registry, not the home, of the §3.x
 * families, and this keeps the companion under the size the static analysis allows.
 *
 * A raw string so a malformed value is a NAMED violation rather than a binder crash; an unset
 * key is not a violation (application.yml always supplies the default). The bounds are spelled
 * as literals for the same reason the key-provider names are: `app` compiles against `web`
 * only, so it cannot import `pipeline-contract`'s `co.datapipelines.pipeline.RequestLimits`,
 * which owns the same numbers — `WebPropertiesSpecDriftTest` holds the binding class to the
 * doc, and this rule holds the operator's override to the same window.
 */
internal object RequestLimitsRules {
    /** `pipeline-contract`'s `RequestLimits.MIN_REQUEST_BYTES`..`MAX_REQUEST_BYTES` (64 KiB..64 MiB). */
    private const val REQUEST_BYTES_MIN = 65_536L
    private const val REQUEST_BYTES_MAX = 67_108_864L

    fun checkRequestBounds(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
    ) {
        val raw = snapshot.webMaxRequestBytes?.trim() ?: return
        val parsed = raw.toLongOrNull()
        when {
            parsed == null -> {
                violations +=
                    "datapipelines.web.max-request-bytes is '$raw'; §3.31 requires an integer number of bytes."
            }

            parsed < REQUEST_BYTES_MIN || parsed > REQUEST_BYTES_MAX -> {
                violations +=
                    "datapipelines.web.max-request-bytes is $parsed; §3.31 requires " +
                    "$REQUEST_BYTES_MIN..$REQUEST_BYTES_MAX (the cap bounds every JSON request body)."
            }
        }
    }
}
