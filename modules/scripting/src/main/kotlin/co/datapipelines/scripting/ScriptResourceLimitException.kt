package co.datapipelines.scripting

/**
 * A declared resource limit other than time was hit (transform-nodes design §7:
 * `pipeline.transform.resource_limit`, detail names which).
 *
 * For the JSONata engine today this is [Kind.DEPTH] — the library's recursion bound,
 * enforced at every step. [Kind.HEAP] and [Kind.STATEMENTS] name limits no in-process
 * engine can enforce (`EngineCapabilities`); the kinds exist so the round-two isolate
 * engine reuses this exception unchanged.
 */
class ScriptResourceLimitException(
    val kind: Kind,
    detail: String,
) : ScriptingException(
        CODE,
        "resource limit hit ($kind): $detail",
        mapOf("kind" to kind.name),
    ) {
    /** Which declared limit fired. */
    enum class Kind { DEPTH, HEAP, STATEMENTS }

    companion object {
        /** pipeline-contract §13.18 — the resource-limit row (record §7). */
        const val CODE = "pipeline.transform.resource_limit"
    }
}
