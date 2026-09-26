package co.datapipelines.mcp.docs

import co.datapipelines.calculators.CalculatorInput
import co.datapipelines.calculators.CalculatorKind
import co.datapipelines.calculators.CalculatorRegistry
import co.datapipelines.pipeline.ContextKeys

/**
 * Renders `pipelines-calculators` from [CalculatorRegistry] — every kind with its phrases,
 * typed inputs, output(s) and worked example (record §3.3 generator (b), the brief's column
 * list verbatim). The catalog is a property of the BUILD: a kind added to the registry is in
 * the rendered set with no hand edit, and `DocSetStructureTest` holds the render to the registry.
 *
 * The rules around the kinds — the `$name` reference grammar, the one-field-per-shape rule,
 * sequencing by `depends_on` — are judgment and live in the narrative (`templates`); this
 * document is the catalog itself, the same rows `calculators_list` projects to the wire.
 */
object CalculatorsReferenceGenerator {
    /** The generated `pipelines-calculators` document. */
    fun render(): Doc {
        val out = StringBuilder()
        out.append("# The calculator catalog\n\n")
        out
            .append(HEADER.trimIndent())
            .append("\n\nThere are **")
            .append(CalculatorRegistry.KINDS.size)
            .append(" kinds**, in registry order. The rules around them — the dollar-key reference grammar, ")
            .append("`context_key` vs `context_keys`, sequencing by `depends_on` — are in `templates`.\n")
        for (kind in CalculatorRegistry.KINDS) {
            out.append(kindCard(kind))
        }
        val markdown = out.toString()
        return Doc(
            name = "pipelines-calculators",
            area = DocArea.PIPELINES,
            layer = DocLayer.REFERENCE,
            title = "The calculator catalog",
            purpose =
                "Every calculator kind with its phrases, inputs, outputs and a worked example — " +
                    "open it when a question names a period or an arithmetic the SQL should not hand-roll.",
            markdown = markdown,
            sections = MarkdownSections.cut(markdown),
        )
    }

    /** One kind's catalog card: description, phrases, inputs, outputs and the worked example. */
    private fun kindCard(kind: CalculatorKind): String {
        val out = StringBuilder()
        out.append("\n## `").append(kind.kind).append("`\n\n")
        out.append(oneLine(kind.description)).append("\n\n")
        out
            .append("Everyday phrases it answers: ")
            .append(kind.phrases.joinToString(separator = ", ") { "`$it`" })
            .append("\n\n")
        out.append("| Input | Type | | What it is |\n|---|---|---|---|\n")
        for (input in kind.inputs) {
            out
                .append("| `")
                .append(input.name)
                .append("` | ")
                .append(typeOf(input))
                .append(" | ")
            out.append(if (input.required) "required" else "optional").append(" | ")
            out.append(cell(input.description)).append(" |\n")
        }
        out.append("\n")
        if (kind.outputs.isEmpty()) {
            out
                .append("Writes one `")
                .append(kind.output?.wire ?: CalculatorInput.ANY_TYPE)
                .append("` value to the node's `context_key`.\n")
        } else {
            out.append("Writes a named set — the node maps every output through `context_keys`:\n\n")
            out.append("| Output | Type | What it is |\n|---|---|---|\n")
            for (output in kind.outputs) {
                out
                    .append("| `")
                    .append(output.name)
                    .append("` | ")
                    .append(output.type?.wire ?: CalculatorInput.ANY_TYPE)
                    .append(" | ")
                    .append(cell(output.description))
                    .append(" |\n")
            }
        }
        out.append("\nWorked example — inputs ").append(oneLine(kind.example.inputs.toString()))
        out.append(", output ").append(oneLine(kind.example.output)).append(".\n")

        return out.toString()
    }

    private fun typeOf(input: CalculatorInput): String {
        val type = input.typeName
        return if (input.isList) "$type[]" else type
    }

    private fun cell(text: String): String = oneLine(text).replace("|", "\\|")

    private fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private val HEADER =
        """
        A `CALCULATOR` node evaluates one pure function the server ships and writes one typed
        value — or, on a multi-output kind, a named set of them — into the execution Context for
        downstream SQL to bind. Match the question's words against `phrases` to pick the kind;
        call `calculators_list` on the wire when you need the JSON the tool projects.
        """.trimIndent() +
            " The platform's own Context keys (" +
            ContextKeys.PLATFORM_TYPES.entries.joinToString(separator = ", ") { "`${it.key}`" } +
            ") are declared, not computed here."
}
