package co.datapipelines.logging

import org.slf4j.LoggerFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * The normal-boot payload (#337-b banner ruling): a REAL [org.springframework.boot.SpringApplication]
 * run — environment post-processors, the logging listener, the banner printer and Boot's own
 * startup lines — around an empty context, then one planted line. [NormalBootOutputTest] spawns it
 * in a fresh JVM because logging initialisation is process-global; the format arrives as the
 * `datapipelines.observability.logging.format` system property, exactly as an operator's
 * environment would supply it.
 *
 * Deliberately a bare context, not the application: the proof is about what the LOGGING SYSTEM and
 * the banner write to stdout during a normal boot, so it needs no database, no credentials and no
 * production-like service.
 */
fun main(args: Array<String>) {
    val context =
        SpringApplicationBuilder(PlantBootConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .run(*args)
    LoggerFactory
        .getLogger("plant-boot")
        .info("boot plant password=\"\\\"planted-secret-boot\" tail")
    context.close()
}
