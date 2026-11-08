package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.visualization.ChartSchemas
import co.datapipelines.web.api.ApiErrors
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * `GET /schemas/chart/1/{name}.schema.json` — the chart document v1 schemas (design record
 * 2026-11-06-neutral-chart-grammar §4.2), served as the bytes the save path validates against.
 *
 * ## One copy
 *
 * The body is [ChartSchemas.bytes]: the same classpath resource [co.datapipelines.visualization.ChartSchemaValidator]
 * loads into its registry, keyed by the `$id` this URL answers (`https://datapipelines.co/schemas/chart/1/<file>`).
 * Only the names in [ChartSchemas.FILES] resolve; every other name is the §4.2 envelope's 404, so nothing else on
 * the classpath is reachable through this route.
 *
 * ## Why it is public
 *
 * It is the contract an agent or a host validates a chart document against before it saves one, like `/skill.md`
 * for the manual: constant bytes packaged in the jar, no principal, no workspace, no datastore. Requiring a key would
 * only mean a client cannot check its document until after it holds one.
 *
 * ## Why `application/schema+json`
 *
 * The media type the JSON Schema specification names for a schema document: a client that dispatches on it knows the
 * body is a schema, and every JSON reader still parses it because the type is `+json`.
 *
 * ## Why `@Controller` and not `@RestController`
 *
 * The [co.datapipelines.web.skill.SkillController] reasoning: the route is outside the scope-governed prefixes and
 * public by design, so it declares no §7.6 operation, and `RequiredScopeCoverageTest` governs `@RestController`s.
 *
 * ## Why the cache header is SET on the servlet response
 *
 * The security chain has already written its `Cache-Control: no-cache, no-store, …` on the response when this handler
 * runs. `ResponseEntity.cacheControl` ADDS a second header beside it, and a cache that combines the two obeys
 * `no-store` — measured on a running instance, 2026-11-08. `setHeader` replaces it, the
 * [co.datapipelines.web.skill.SkillController] way, so the route carries ONE header: `max-age=900, public`.
 */
@Controller
class ChartSchemaController {
    @GetMapping("/schemas/chart/1/{name}.schema.json")
    @ResponseBody
    fun schema(
        @PathVariable name: String,
        response: HttpServletResponse,
    ): ResponseEntity<ByteArray> {
        val file = "$name$SUFFIX"
        if (file !in ChartSchemas.FILES) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Execution.NOT_FOUND,
                message = "No chart schema '$file'. The v1 files are ${ChartSchemas.FILES.joinToString()}.",
                details = mapOf(ApiErrors.REASON to "chart_schema_not_found"),
            )
        }
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue,
        )
        return ResponseEntity
            .ok()
            .contentType(SCHEMA_JSON)
            .body(ChartSchemas.bytes(file))
    }

    private companion object {
        const val SUFFIX = ".schema.json"

        /** UTF-8 by the JSON spec; stated, because the schemas' descriptions carry non-ASCII punctuation. */
        val SCHEMA_JSON: MediaType = MediaType.parseMediaType("application/schema+json;charset=UTF-8")

        /** The [co.datapipelines.web.skill.SkillController] window: the bytes change only with a deploy. */
        const val MAX_AGE_MINUTES = 15L
    }
}
