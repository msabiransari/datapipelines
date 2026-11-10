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
 * A step that refuses (E.0: an API whose pipeline has no current version to pin) throws; the throw fails the context
 * refresh, so the server REFUSES TO START with a non-zero exit and the step's structured list in the log.
 */
class ConversionStartup(
    private val steps: List<StartupConversion>,
) {
    @PostConstruct
    fun convertBeforeServing() = steps.forEach { it.convert() }
}
