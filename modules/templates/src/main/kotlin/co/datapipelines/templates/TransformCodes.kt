package co.datapipelines.templates

/**
 * The transform record's §7 codes (pipeline-contract §13.18) that 7b must emit BEFORE lane 7c
 * declares them in `PipelineErrorCodes`: `transform.js.unavailable` at template save, and the
 * input/gate refusals the test runner and `templates_evaluate` produce (record §5.1, §4.3).
 *
 * PRIVATE ON PURPOSE: §13.18 is 7c's section and `PipelineErrorCodesSpecDriftTest` refuses a
 * constant with no doc row, so these strings live here until 7c's merge, when the orchestrator
 * unifies them into the catalog (named in 7b's handback). The scripting module's own codes
 * (`pipeline.transform.pool_exhausted` and friends) are already public constants on 7a's
 * exception types — use those, never a copy.
 */
internal object TransformCodes {
    /** §13.18 — a `javascript` template is refused at save until round two's engine ships. */
    const val JS_UNAVAILABLE = "transform.js.unavailable"

    /** §13.18 (record §5.1) — an evaluate/test input violates the contract's declared inputs. */
    const val INPUT_CONTRACT_VIOLATION = "pipeline.transform.input_contract_violation"

    /** §13.18 (record §4.3) — an input above `max-input-rows` (rows or any table input). */
    const val INPUT_TOO_LARGE = "pipeline.transform.input_too_large"

    /** §13.18 (record §5.3) — a row's key set is not exactly the declared column set. */
    const val ROW_SHAPE_MISMATCH = "pipeline.transform.row_shape_mismatch"

    /** §13.18 (record §5.3) — a value does not fit its column's wire form. */
    const val VALUE_TYPE_MISMATCH = "pipeline.transform.value_type_mismatch"

    /** §13.18 (record §5.3, R1) — a numeric value does not fit its declared precision/scale. */
    const val PRECISION_LOST = "pipeline.transform.precision_lost"

    /** §13.18 (record §4.3) — a returned value or string above the byte caps. */
    const val VALUE_TOO_LARGE = "pipeline.transform.value_too_large"
}
