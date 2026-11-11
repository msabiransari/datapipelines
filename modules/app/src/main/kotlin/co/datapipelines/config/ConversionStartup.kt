package co.datapipelines.config

import co.datapipelines.web.endpoints.StartupConversion
import jakarta.annotation.PostConstruct

/**
 * The upgrade's in-place conversion runner (#786 §E.1; owner ruling 2026-11-08: one server, convert in place, no
 * floor). It runs every [StartupConversion] once, in bean-creation of this component:
 *
 * - AFTER Flyway — each step reads through the JDBC template, which Boot makes depend on the Flyway initializer;
 * - BEFORE every `SmartInitializingSingleton` (the demo seeders, which then see converted APIs), before the web server
 *   starts in `finishRefresh` (so nothing is served from an unconverted row), and before the scheduler's
 *   `SmartLifecycle` admission starts and `ApplicationReadyEvent` fires (so nothing is dispatched).
 *
 * E.0 comes first for EVERY step (#786 §E.0, §D.5: "before any write, the runner inspects every version-less API
 * entity and every schema-1 schedule"): each step logs its own refused rows; when any step reported one, the runner
 * throws before ANY step writes. The throw fails the context refresh, so the server REFUSES TO START with a non-zero
 * exit and every step's structured list in the log. A step that refuses later (a row lost its CURRENT between the
 * precondition and its locked re-read) throws the same way.
 */
class ConversionStartup(
    private val steps: List<StartupConversion>,
) {
    @PostConstruct
    fun convertBeforeServing() {
        val refused = steps.sumOf { it.logPrecondition() }
        check(refused == 0) {
            "The upgrade's conversion refused to start: $refused API(s) or schedule(s) reference a pipeline with no current " +
                "version to pin (each is logged above). $OPERATOR_FIX"
        }
        steps.forEach { it.convert() }
    }

    companion object {
        /** The fix named when the combined precondition refuses (docs/deployment.md §8). */
        const val OPERATOR_FIX = "On the previous version, release the pipeline or delete the API or schedule, then upgrade again."
    }
}
