package co.datapipelines.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The `ObjectMapper` the pipeline contract is bound with.
 *
 * Deliberately **not** a shared/global mapper: an application-wide mapper collects naming
 * strategies, feature flags and modules from every surface that touches it, and pipeline
 * JSON is a frozen wire contract (§15.1). Every field in the model carries an explicit
 * `@JsonProperty` on all three use-site targets for the same reason — a naming strategy
 * configured anywhere upstream cannot silently rewrite `display_name` into `displayName`
 * (the Java-Beans `^[a-z][A-Z]` trap that shipped an entire feature broken once already).
 *
 * No naming strategy is set here; the annotations are the contract.
 */
object PipelineJson {
    /**
     * Builds a mapper configured for pipeline JSON.
     *
     * Null-omission is declared **per class** (`@JsonInclude(NON_NULL)` on [Node] and
     * [Parameter]) rather than as a mapper-wide default, because absence is meaningful in
     * this contract and the classes it is meaningful for should say so themselves: an omitted
     * `output` means "caller" (§4.7), an omitted `precision` means unbounded (type-system
     * §7.3). Writing `"output": null` would assert something §4.7 does not define — and a
     * setting on the mapper is lost the moment someone serializes with a different one.
     */
    fun objectMapper(): ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(NodeOutputModule.create())
            .build()
}

/**
 * Writes pipeline JSON (pipeline-contract §17.1).
 *
 * The output is the **portable body** of §11.1 — the exact field set metadata-db §4.5
 * defines for `pipeline_versions.body_json` and §14 defines for a create/update payload.
 * Server-assigned fields are not part of this type at all (see [Pipeline]), so there is no
 * mode in which they can be written out by accident.
 */
class PipelineSerializer(
    private val mapper: ObjectMapper = PipelineJson.objectMapper(),
) {
    /** Serializes [pipeline] to compact JSON — the form stored in `body_json`. */
    fun write(pipeline: Pipeline): String = mapper.writeValueAsString(pipeline)

    /** Serializes [pipeline] to indented JSON — the form exported for humans and diffing. */
    fun writePretty(pipeline: Pipeline): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pipeline)
}
