package co.datapipelines.logging

import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * The packaged-output proof's payload (#337 D4b): initialises the REAL logback logging system the
 * way Boot does, from the SAME mapping the [ObservabilityLoggingFormatPostProcessor] applies, then
 * logs one line per redaction channel with obvious marker plants:
 *
 * - the MDC (`password` — layer 1, a structured member);
 * - a key-value pair (`jdbc_url` — layer 1, a member);
 * - the message in the `key=value` form (`password=...` — layer 2, rendered text);
 * - the message in the `"key": "value"` form (`"api_key": "..."` — layer 2, rendered text);
 * - an exception message quoting a JDBC URL (layer 2, the stack trace — the record's realistic
 *   driver leak);
 * - the shared [SyntheticPlants] corpus as a message and as an exception (#337-b), then one line
 *   with no MDC at all so the proof can check no id goes stale.
 *
 * Runs in its OWN JVM (the test spawns it over the test runtime classpath): logging initialisation
 * is process-global, and a test that re-initialises it would poison every later suite in the same
 * JVM. The format comes in as a system property so one main proves both values of the switch.
 */
fun main() {
    val format = System.getProperty(ObservabilityLoggingFormatPostProcessor.SWITCH_KEY) ?: "json"
    ObservabilityLoggingFormatPostProcessor
        .structuredProperties(format)
        .forEach { (key, value) -> System.setProperty(key, value) }

    val loggingSystem =
        org.springframework.boot.logging.LoggingSystem
            .get(ClassLoader.getSystemClassLoader())
    loggingSystem.beforeInitialize()
    // A StandardEnvironment exposes the system properties the mapping above wrote — the same
    // resolution path a bare boot gets through its own environment.
    loggingSystem.initialize(
        org.springframework.boot.logging.LoggingInitializationContext(
            org.springframework.core.env
                .StandardEnvironment(),
        ),
        null,
        null,
    )

    val log = LoggerFactory.getLogger("plant")
    System.err.println(
        "PLANT_PROBE prop-tail=>" +
            (System.getProperty("CONSOLE_LOG_PATTERN") ?: "<unset>").takeLast(80) +
            "< structured=" + System.getProperty("logging.structured.format.console"),
    )
    MDC.put(LogContextKeys.CORRELATION_ID, "3f2b8c1a-1111-4111-8111-111111111111")
    MDC.put(LogContextKeys.EXECUTION_ID, "3f2b8c1a-2222-4222-8222-222222222222")
    MDC.put("password", "planted-secret-mdc")
    log.info("boot line password=planted-secret-message and \"api_key\": \"planted-secret-json\" tail")
    log
        .atInfo()
        .addKeyValue("jdbc_url", "planted-secret-kvp")
        .log("kvp line with the pair attached")
    runCatching {
        throw IllegalStateException("connect failed jdbc_url=jdbc:postgresql://svc:planted-secret-exc@db.internal:5432/dp")
    }.onFailure { log.error("boom line quoting the driver", it) }
    // #337-b: the shared corpus, once as a message and once as an exception message, so the
    // child JVM's real appender proves every shape under whichever format is pinned.
    (SyntheticPlants.REDACTED + SyntheticPlants.KEPT).forEach { plant ->
        log.info("corpus message: ${plant.raw}")
        log.error("corpus exception", IllegalStateException(plant.raw))
    }
    MDC.remove("password")
    MDC.remove(LogContextKeys.CORRELATION_ID)
    MDC.remove(LogContextKeys.EXECUTION_ID)
    log.info("no-context line")
}

/** The MDC slots PlantMain plants — the same strings the executor's element uses. */
private object LogContextKeys {
    const val CORRELATION_ID: String = "correlation_id"
    const val EXECUTION_ID: String = "execution_id"
}
