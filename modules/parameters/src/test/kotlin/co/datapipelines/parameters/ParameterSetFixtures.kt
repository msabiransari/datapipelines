package co.datapipelines.parameters

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Parameter-set documents as JSON trees, built the way an author writes them — the record's §3
 * examples, so a test reads like the spec it pins. Every builder returns a fresh tree a test may
 * mutate.
 */
internal object ParameterSetFixtures {
    private val mapper = ParameterSetJson.mapper

    fun tree(json: String): JsonNode = mapper.readTree(json)

    /** The record's §3.3 constants select: a country picker, USA marked default. */
    fun countryJson(): String =
        """
        { "name": "country", "label": "Country", "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE",
          "required": true,
          "source": { "constants": [
            { "value": "USA", "display_value": "United States", "is_default": true },
            { "value": "CAN", "display_value": "Canada", "is_default": false } ] },
          "presentation": { "control": "dropdown" } }
        """.trimIndent()

    /** The record's §3.2 template select: states of the selected country, disabled until a country is chosen. */
    fun stateJson(): String =
        """
        { "name": "state", "label": "State", "description": "States of the selected country.",
          "type": "STRING", "kind": "SELECT", "cardinality": "SINGLE", "required": true, "default_value": null,
          "source": { "template": { "id": "acme/sales/states_of_country.sql", "version": 3 }, "datasource": "warehouse" },
          "depends_on": ["country"],
          "hidden_expression": null,
          "disabled_expression": { "op": "is_null", "arg": { "ref": "country" } },
          "constraints": null,
          "presentation": { "control": "dropdown" } }
        """.trimIndent()

    /** The record's §3.6 amount example, complete. */
    fun amountJson(): String =
        """
        { "name": "min_order_amount", "label": "Minimum order amount", "type": "DECIMAL",
          "precision": 12, "scale": 2, "kind": "INPUT", "cardinality": "SINGLE", "required": false,
          "default_value": 0, "constraints": { "min": 0 },
          "presentation": { "control": "number", "format": { "kind": "currency" } } }
        """.trimIndent()

    /** A set of [parameters] (raw JSON objects), named like the record's §3.1 example. */
    fun setJson(
        vararg parameters: String,
        name: String = "acme/sales/region_filters",
    ): String =
        """
        { "name": "$name", "display_name": "Region filters",
          "description": "Country → state → city cascade plus a minimum order amount.",
          "parameters": [ ${parameters.joinToString(",")} ] }
        """.trimIndent()

    /** The three-parameter set every reader test starts from. */
    fun fullSet(): ObjectNode = tree(setJson(countryJson(), stateJson(), amountJson())) as ObjectNode

    fun ObjectNode.parameter(index: Int): ObjectNode = (this["parameters"] as ArrayNode)[index] as ObjectNode

    /** A constants-only `SELECT` named [name] with [values] as its options (the first marked default when [firstDefault]). */
    fun constantsSelect(
        name: String,
        values: List<String>,
        dependsOn: List<String> = emptyList(),
        firstDefault: Boolean = false,
        cardinality: String = "SINGLE",
    ): String {
        val options =
            values.mapIndexed {
                i,
                v,
                ->
                """{ "value": "$v", "display_value": "${v.uppercase()}", "is_default": ${firstDefault && i == 0} }"""
            }
        return """
            { "name": "$name", "label": "${name.replaceFirstChar { it.uppercase() }}", "type": "STRING", "kind": "SELECT",
              "cardinality": "$cardinality", "source": { "constants": [ ${options.joinToString(",")} ] },
              "depends_on": [ ${dependsOn.joinToString(",") { "\"$it\"" }} ] }
            """.trimIndent()
    }

    /** A plain `STRING` `INPUT` named [name]. */
    fun textInput(
        name: String,
        dependsOn: List<String> = emptyList(),
    ): String =
        """
        { "name": "$name", "label": "${name.replaceFirstChar { it.uppercase() }}", "type": "STRING", "kind": "INPUT",
          "depends_on": [ ${dependsOn.joinToString(",") { "\"$it\"" }} ] }
        """.trimIndent()
}
