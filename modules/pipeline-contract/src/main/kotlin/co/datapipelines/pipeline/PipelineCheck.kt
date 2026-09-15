package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One release check (pipeline-contract §3.3, 140): a read-only statement the SERVER runs
 * against a datasource the workspace can read, plus the value the author expects it to
 * produce.
 *
 * The shape is the whole point: the author (usually an agent) supplies the query and the
 * expectation — never an observed value. `observed` exists only on a `pipeline_check_runs`
 * row (metadata-db §4.20), written by the server's own run; there is no field here one could
 * carry it in on, and no tool that records one from a caller.
 *
 * Versioned with the body like `nodes` (§15.2 additive): an existing pipeline's canonical
 * JSON is byte-identical after this type exists, because [Pipeline.checks] defaults to an
 * empty list and serializes under `NON_EMPTY` inclusion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PipelineCheck(
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String = "",
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String = "",
    @field:JsonProperty("datasource") @get:JsonProperty("datasource") @param:JsonProperty("datasource")
    val datasource: String = "",
    @field:JsonProperty("sql") @get:JsonProperty("sql") @param:JsonProperty("sql")
    val sql: String = "",
    @field:JsonProperty("expected") @get:JsonProperty("expected") @param:JsonProperty("expected")
    val expected: CheckExpectation = CheckExpectation(),
)

/**
 * What a check expects its statement to produce (§3.3).
 *
 * `kind` is the closed list `value` / `range` / `rows`:
 *
 *  - `value` — the statement returns exactly one row and one column, compared NUMERICALLY
 *    against [value] with absolute [tolerance] (default 0);
 *  - `range` — the same single cell must fall in `[[min], [max]]`, inclusive;
 *  - `rows` — the statement's ROW COUNT must equal [rows].
 *
 * A multi-cell or non-numeric result is not a save-time verdict: it is a failed check at RUN
 * time with the reason in the run row's `message` (§12.12). `kind` is a String, not an enum,
 * for the same reason [Node]'s creator is lenient: an out-of-catalog wire value must reach
 * §12.12's `check_invalid` and be collected with the body's other failures (§17.2), not abort
 * binding. Every member defaults so an absent one is reported by the rule that owns it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CheckExpectation(
    @field:JsonProperty("kind") @get:JsonProperty("kind") @param:JsonProperty("kind")
    val kind: String = "",
    @field:JsonProperty("value") @get:JsonProperty("value") @param:JsonProperty("value")
    val value: Double? = null,
    @field:JsonProperty("min") @get:JsonProperty("min") @param:JsonProperty("min")
    val min: Double? = null,
    @field:JsonProperty("max") @get:JsonProperty("max") @param:JsonProperty("max")
    val max: Double? = null,
    @field:JsonProperty("rows") @get:JsonProperty("rows") @param:JsonProperty("rows")
    val rows: Long? = null,
    @field:JsonProperty("tolerance") @get:JsonProperty("tolerance") @param:JsonProperty("tolerance")
    val tolerance: Double? = null,
) {
    companion object {
        /** The closed `kind` list (§12.12). */
        val KINDS: Set<String> = setOf("value", "range", "rows")

        const val KIND_VALUE = "value"
        const val KIND_RANGE = "range"
        const val KIND_ROWS = "rows"
    }
}
