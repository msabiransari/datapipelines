package co.datapipelines.web.ui

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * The `version` query parameter's ONE parse — every workspace model's `parseRequestedVersion`
 * delegates here (pipelines, dashboards, visualizations and, through the pipeline one, parameter
 * sets), so the refusal is written once (#421).
 *
 * Optional, and when supplied a positive integer — anything else is the house 400, never a
 * silent clamp to another version. The reason is the constant [BAD_VERSION] and NEVER carries the
 * input: an htmx caller renders the reason in a toast and the exception message reaches the logs,
 * so an echoed `?version=` would reflect caller-controlled text into both.
 *
 * Bound as a STRING by the callers on purpose: an `Int` binding would answer a non-numeric value
 * with the unhandled-mismatch 500; this way the caller's malformed input gets the 400.
 *
 * `VersionReasonEchoGuardTest` sweeps the main sources for a reason that echoes its input, so a
 * parser copied from an older family (the templates workspace, 398) goes red at its merge
 * instead of re-opening the finding.
 */
object RequestedVersion {
    /** The refusal's one reason — a constant, so nothing of the input can ride it. */
    const val BAD_VERSION = "The version parameter must be a positive integer."

    /** `null`/empty → null (no version asked for); a positive integer → it; anything else → the 400. */
    fun parse(raw: String?): Int? {
        if (raw.isNullOrEmpty()) return null
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed <= 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, BAD_VERSION)
        return parsed
    }
}
