package co.datapipelines.config

import co.datapipelines.web.config.CaptureDurations
import java.time.Instant

/**
 * §7 / §3.22 (#1043) — the runtime capture window and detail retention. Owns its file like `UiRules`, so the validator's
 * companion stays the registry. The rules themselves are [CaptureDurations]'s — the same copy the binder enforces — and
 * are judged here on the RAW text, so zero, a negative value, an integer overflow or a deadline past the representable
 * range is a violation naming the key, not a binder stack trace. An unset key takes its documented default.
 */
internal object EndpointCaptureRules {
    fun checkEndpointsCaptureDurations(
        snapshot: ConfigSnapshot,
        violations: MutableList<String>,
        now: Instant = Instant.now(),
    ) {
        snapshot.endpointsCaptureWindowSeconds?.let { raw ->
            CaptureDurations.problemOfText(CaptureDurations.WINDOW_KEY, raw, now)?.let { violations += "$it; §3.22." }
        }
        snapshot.endpointsCaptureDetailRetentionHours?.let { raw ->
            CaptureDurations.problemOfText(CaptureDurations.RETENTION_KEY, raw, now)?.let { violations += "$it; §3.22." }
        }
    }
}
