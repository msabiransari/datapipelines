package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Whether the AUTHORED document carried `bindings` — a chart's are derived when absent (design record §4.4). */
enum class BindingsPresence {
    /** The key was absent: derive the bindings after the save rules pass. */
    ABSENT,

    /** `"bindings": null` — the schema refuses it (`type` at `/bindings`). */
    NULL,

    /** An object (a stored or code-built document is always this): judged against the derived map. */
    PRESENT,
}

/**
 * A `chart` document's save layers (design record 2026-11-06 §4.4) over the body AS STORED: (1) the v1 JSON Schema
 * ([ChartSchemaValidator]), (2) the save rules ([ChartSaveRules]), (3) the D-A authority ([ChartLibrarySupport] —
 * the server library's table). [VisualizationValidator] runs them with the product's own rules in layer 2 and stops
 * at the first layer that refuses, so a document fails at exactly one layer.
 */
class ChartDocumentRules(
    private val schema: ChartSchemaValidator = ChartSchemaValidator(),
    val support: ChartLibrarySupport = ChartLibrarySupport.load(),
) {
    /** The schema's input: the body as [ArtifactJson.writeBody] stores it, `bindings` as the author sent it. */
    fun storedForm(document: VisualizationDocument): ObjectNode {
        val tree: ObjectNode = ArtifactJson.mapper.valueToTree(document.body)
        when (document.bindings) {
            BindingsPresence.ABSENT -> tree.remove("bindings")
            BindingsPresence.NULL -> tree.putNull("bindings")
            BindingsPresence.PRESENT -> Unit
        }
        return tree
    }

    /** Layer 1. */
    fun schema(tree: JsonNode): List<ChartSchemaProblem> = schema.validate(tree)

    /** Layer 2's chart rules; [columns] = the contract the chart reads, null when it cannot be known. */
    fun saveRules(
        tree: JsonNode,
        columns: List<InputColumn>?,
    ): ChartSaveVerdict = ChartSaveRules.check(tree, columns)

    /** Layer 3. */
    fun libraryRules(
        tree: JsonNode,
        columns: List<InputColumn>,
    ): ChartLibrarySupport.Verdict = support.judge(BarFieldSet.of(tree, columns), BarChannels.of(tree.path("config")))

    companion object {
        /** The production rules — the schema compiles once per JVM. */
        val DEFAULT: ChartDocumentRules by lazy { ChartDocumentRules() }
    }
}

/** JSON Pointer → the product's dotted failure path (`/config/encoding/y/field` → `config.encoding.y.field`, `[n]` for arrays). */
object ChartPaths {
    fun dotted(
        root: JsonNode,
        pointer: String,
    ): String {
        if (pointer.isEmpty() || pointer == "/") return ""
        var node: JsonNode = root
        val out = StringBuilder()
        pointer.removePrefix("/").split('/').forEach { raw ->
            val key = raw.replace("~1", "/").replace("~0", "~")
            val index = key.toIntOrNull()
            if (node.isArray && index != null) {
                out.append('[').append(index).append(']')
                node = node.path(index)
            } else {
                if (out.isNotEmpty()) out.append('.')
                out.append(key)
                node = node.path(key)
            }
        }
        return out.toString()
    }
}
