package co.datapipelines.visualization

import java.lang.reflect.Modifier

/**
 * The §13 section parser and reflection readers both families' catalog suites share — the
 * `ParameterErrorCodesSpecDriftTest` mechanism: the table is located by its heading, each row's first
 * cell is the code and its second the HTTP status, and a heading rename that emptied the parse fails
 * the floor the callers assert rather than passing every comparison vacuously.
 */
internal object CatalogSections {
    private const val SPEC_PATH = "docs/pipeline-contract.md"

    /** `| \`code\` | HTTP | … |` — group 1 the code, group 2 the status cell. */
    private val ROW = Regex("^\\|\\s*`([a-z0-9_]+(?:\\.[a-z0-9_]+)+)`\\s*\\|\\s*([^|]+?)\\s*\\|")

    /** The code → status rows of the section under [heading], up to the next heading. */
    fun parse(heading: String): Map<String, String> {
        val text = VisualizationTestFiles.read(SPEC_PATH)
        val start = text.indexOf(heading)
        check(start >= 0) { "'$heading' not found in $SPEC_PATH" }
        val rest = text.substring(start + heading.length)
        val end = listOf(rest.indexOf("\n### "), rest.indexOf("\n## ")).filter { it >= 0 }.minOrNull() ?: rest.length
        return rest
            .substring(0, end)
            .lineSequence()
            .mapNotNull { ROW.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Every `const val` String of [type], by constant name. */
    fun namedConstantsOf(type: Class<*>): Map<String, String> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.get(null) as String) }

    /** `{domain}.{entity}.{failure}`, or two segments for the entity's own `not_found`. */
    val SEGMENTATION = Regex("^[a-z0-9_]+\\.[a-z0-9_]+(\\.[a-z0-9_]+)?$")
}
