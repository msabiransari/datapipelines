package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode

/**
 * The binding grammar — the path a `bindings` key names inside `config` (the spec's §3.1: "a map from a JSON
 * path into `config`"): a key, then any run of `.key` and `[index]` steps — `data[0].x`, `layout.title.text`,
 * `columns[2].values`. A key is `[A-Za-z_][A-Za-z0-9_]*` (Plotly's attribute names), an index `0..9999`
 * without leading zeros; at most [MAX_PATH_CHARS] characters. No wildcard, no filter, no root marker: a path
 * names exactly one place, and that place must EXIST in the stored `config` (the renderer's placeholder), so
 * the runtime's substitution (§8.3 `visualization_data.bindings`) can never create structure.
 */
object BindingPath {
    /** One step of a parsed path. */
    sealed interface Step {
        data class Key(
            val name: String,
        ) : Step

        data class Index(
            val index: Int,
        ) : Step
    }

    const val MAX_PATH_CHARS = 256

    private val GRAMMAR = Regex("""^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*|\[(?:0|[1-9][0-9]{0,3})])*$""")
    private val STEP = Regex("""([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)]""")

    /** The steps of [path], or null when it is not the grammar. */
    fun parse(path: String): List<Step>? {
        if (path.length > MAX_PATH_CHARS || !GRAMMAR.matches(path)) return null
        return STEP
            .findAll(path)
            .map { m -> if (m.groupValues[1].isNotEmpty()) Step.Key(m.groupValues[1]) else Step.Index(m.groupValues[2].toInt()) }
            .toList()
    }

    /** True when every step of [steps] exists in [config] — a key of an object, an index inside an array. */
    fun resolves(
        config: JsonNode,
        steps: List<Step>,
    ): Boolean {
        var node: JsonNode = config
        steps.forEach { step ->
            node =
                when (step) {
                    is Step.Key -> node.takeIf { it.isObject }?.get(step.name)
                    is Step.Index -> node.takeIf { it.isArray && step.index < it.size() }?.get(step.index)
                } ?: return false
        }
        return true
    }
}
