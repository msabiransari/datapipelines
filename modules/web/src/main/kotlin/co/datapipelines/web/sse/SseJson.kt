package co.datapipelines.web.sse

import co.datapipelines.executor.ExecutorJson
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

/**
 * The mapper for the SSE event log and its replay stream.
 *
 * [ExecutorJson.mapper] plus `jackson-datatype-jsr310`: dag's mapper deliberately
 * omits the module (its KDoc — the artifact is not on dag's dependency list, and dag
 * itself only needs `Instant`), but SSE payloads carry the RESOLVED pipeline
 * parameters, and `DATE`/`TIME`/`TIMESTAMP` parameters arrive as `java.time` values.
 * Without the module, every execution declaring one aborted at
 * `SseEventLog.append` (T36, found by 023's demo E2E — `LocalDate not supported by
 * default`). `web` already ships the artifact via the Boot JSON starter.
 *
 * Dates serialize as ISO-8601 strings, matching ExecutorJson's own `Instant`
 * convention, not as numeric timestamps.
 */
object SseJson {
    val mapper: ObjectMapper =
        ExecutorJson.mapper
            .copy()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
