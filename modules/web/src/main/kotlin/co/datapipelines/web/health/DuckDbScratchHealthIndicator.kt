package co.datapipelines.web.health

import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import java.util.concurrent.atomic.AtomicReference

/**
 * The DuckDB staging scratch directory's health on the management port's `/actuator/health` (#806, owner ruling
 * 2026-11-12): status only, never a component of the public `/health` or `/ready` (rest-api.md §11.1 keeps those
 * at three), so a full scratch volume never fails readiness. The bean name is pinned `duckdb_scratch`. The figures
 * are the `datapipelines.staging.scratch.*` gauges (observability.md §4.1), not health details.
 *
 * Every reading is taken first; then the first matching row wins:
 *  1. any reading throws, or the path can be confirmed neither present nor absent → `DOWN`;
 *  2. the path exists and is not a directory, or is a directory the process cannot write → `OUT_OF_SERVICE`;
 *  3. the path is absent and its nearest existing parent is not a directory the process can write → `OUT_OF_SERVICE`;
 *  4. usable bytes (of the directory, or of that parent) below one default reservation ([thresholdBytes]) →
 *     `OUT_OF_SERVICE`;
 *  5. anything else → `UP`.
 *
 * `UP` says the volume has room for one default reservation and the directory can be written or created. It does
 * NOT say a database can open: the staging module's own checks (a symbolic link, another owner, group or other
 * permissions) and the in-memory aggregate (`scratch-bytes`, read from the gauges) can still refuse.
 *
 * Never throws, opens no database and walks no directory. The [Health] carries **no details**: the path is
 * operator configuration and appears in the server log only — one WARN on the transition into a non-UP status
 * (naming the path and the numbers), one INFO on the return to `UP`, never one per probe.
 */
class DuckDbScratchHealthIndicator(
    private val space: ScratchSpace,
    /** One default reservation (`staging-memory-limit × staging-expansion-factor`), in bytes. */
    private val thresholdBytes: Long,
) : HealthIndicator {
    private val last = AtomicReference(Status.UP)

    override fun health(): Health {
        val verdict = evaluate()
        val previous = last.getAndSet(verdict.status)
        if (previous != verdict.status) report(verdict)
        return Health.status(verdict.status).build()
    }

    /** One probe's status and the reason the server log gives for it. */
    private data class Verdict(
        val status: Status,
        val reason: String,
        val usableBytes: Long? = null,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun evaluate(): Verdict {
        val nearest: ScratchSpace.Nearest
        val writable: Boolean
        val usable: Long
        try {
            nearest = space.nearestExisting()
            writable = space.canWrite(nearest.path)
            usable = space.usableBytes(nearest.path)
        } catch (e: Exception) {
            return Verdict(Status.DOWN, "the scratch directory could not be read (${e.javaClass.simpleName}: ${e.message})")
        }
        val isTarget = nearest.path == space.directory
        val target = if (isTarget) "the scratch directory" else "its nearest existing parent ${nearest.path}"
        return when {
            isTarget && nearest.kind != ScratchPathKind.DIRECTORY -> {
                Verdict(Status.OUT_OF_SERVICE, "the scratch path exists and is not a directory")
            }

            isTarget && !writable -> {
                Verdict(Status.OUT_OF_SERVICE, "the scratch directory is not writable by the process")
            }

            !isTarget && (nearest.kind != ScratchPathKind.DIRECTORY || !writable) -> {
                Verdict(Status.OUT_OF_SERVICE, "the scratch directory is absent and could not be created under ${nearest.path}")
            }

            usable < thresholdBytes -> {
                Verdict(
                    Status.OUT_OF_SERVICE,
                    "$target has $usable usable bytes, below one default reservation of $thresholdBytes bytes",
                )
            }

            else -> {
                Verdict(Status.UP, "$target has $usable usable bytes; one default reservation is $thresholdBytes bytes")
            }
        }.copy(usableBytes = usable)
    }

    private fun report(verdict: Verdict) {
        if (verdict.status == Status.UP) {
            log.info("event=staging.scratch.health status=UP path={} reason=\"{}\"", space.directory, verdict.reason)
        } else {
            log.warn(
                "event=staging.scratch.health status={} path={} usable_bytes={} threshold_bytes={} reason=\"{}\"",
                verdict.status.code,
                space.directory,
                verdict.usableBytes ?: "unknown",
                thresholdBytes,
                verdict.reason,
            )
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(DuckDbScratchHealthIndicator::class.java)
    }
}
