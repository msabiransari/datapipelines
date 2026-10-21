package co.datapipelines.pipeline

import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType

/**
 * The SCRIPT validator's shared fixtures — the record §2.2 example's contract, its §3.1 node,
 * a staging SQL node and the template stub that answers both — imported by member into
 * [ScriptRulesTest] (the record's cases) and [ScriptRulesArmsTest] (the refusal arms the
 * record's example never reaches). One home, so the two specs cannot drift on what "the example"
 * is.
 */
internal object ScriptRulesFixtures {
    const val SCRIPT_TEMPLATE = "acme/shape/order_lines.jsonata"
    const val READER_TEMPLATE = "acme/shape/read_back.jsonata"
    const val SQL_TEMPLATE = "test/stage_orders.sql"
    const val REPORT_TEMPLATE = "test/report.sql"

    /** The record §2.2 example's contract, as the validator's view. */
    val CONTRACT =
        ScriptContractView(
            mode = ScriptContractView.Mode.ROW,
            inputs =
                mapOf(
                    "orders" to
                        ScriptContractView.Input.Table(
                            listOf(
                                ScriptContractView.Column("order_id", LogicalType.INTEGER, nullable = false),
                                ScriptContractView.Column("amount_cents", LogicalType.INTEGER, nullable = false),
                                ScriptContractView.Column("customer_id", LogicalType.STRING, nullable = true),
                            ),
                        ),
                    "tz" to ScriptContractView.Input.Value(LogicalType.STRING),
                    "min_total" to ScriptContractView.Input.Value(LogicalType.DECIMAL),
                ),
            output =
                ScriptContractView.Output.Table(
                    listOf(
                        ScriptContractView.Column("order_id", LogicalType.INTEGER, nullable = false),
                        ScriptContractView.Column("amount", LogicalType.DECIMAL, nullable = false),
                        ScriptContractView.Column("customer_id", LogicalType.STRING, nullable = false),
                    ),
                ),
            rejects = true,
        )

    val VALUE_CONTRACT =
        ScriptContractView(
            mode = ScriptContractView.Mode.VALUE,
            inputs = mapOf("orders" to ScriptContractView.Input.Table(emptyList())),
            output = ScriptContractView.Output.Value(LogicalType.DECIMAL),
            rejects = false,
        )

    val OBJECT_CONTRACT =
        ScriptContractView(
            mode = ScriptContractView.Mode.VALUE,
            inputs = mapOf("orders" to ScriptContractView.Input.Table(emptyList())),
            output = ScriptContractView.Output.Obj,
            rejects = false,
        )

    /** A downstream reader's contract — one value input, one value output. */
    val READER_CONTRACT =
        ScriptContractView(
            mode = ScriptContractView.Mode.VALUE,
            inputs = mapOf("v" to ScriptContractView.Input.Value(LogicalType.STRING)),
            output = ScriptContractView.Output.Value(LogicalType.STRING),
            rejects = false,
        )

    fun stub(
        contract: ScriptContractView = CONTRACT,
        templateType: TemplateType = TemplateType.JSONATA,
        bound: Map<String, List<String>> = emptyMap(),
        readerContract: ScriptContractView? = null,
    ): StubTemplates =
        StubTemplates(
            lookups =
                mapOf(
                    SCRIPT_TEMPLATE to TemplateLookup.Found(dialect = null, type = templateType),
                    READER_TEMPLATE to TemplateLookup.Found(dialect = null, type = TemplateType.JSONATA),
                    SQL_TEMPLATE to TemplateLookup.Found(Dialect.POSTGRES, TemplateType.SQL),
                    REPORT_TEMPLATE to TemplateLookup.Found(Dialect.POSTGRES, TemplateType.SQL),
                ),
            contracts =
                mapOf(SCRIPT_TEMPLATE to contract) +
                    listOfNotNull(readerContract?.let { READER_TEMPLATE to it }),
            bound = bound,
        )

    /** The record's §3.1 example node. */
    fun scriptNode(
        id: String = "shape_orders",
        template: String = SCRIPT_TEMPLATE,
        inputs: Map<String, com.fasterxml.jackson.databind.JsonNode> =
            mapOf(
                "orders" to Fixtures.json("\"stg_orders\""),
                "tz" to Fixtures.json("\"\$org_timezone\""),
                "min_total" to Fixtures.json("\"\$min_total\""),
            ),
        output: NodeOutput? = NodeOutput.Tempdb("order_lines", rejects = "order_lines_rejected"),
        strict: Boolean? = null,
        dependsOn: List<String> = listOf("stage_orders"),
        kind: String? = null,
        contextKey: String? = null,
        contextKeys: Map<String, String>? = null,
        source: String = "",
    ): Node =
        Node(
            id = id,
            description = "Order lines at reporting grain.",
            type = NodeType.SCRIPT,
            source = source,
            template = TemplateRef(template, 3),
            output = output,
            dependsOn = dependsOn,
            inputs = inputs,
            contextKey = contextKey,
            contextKeys = contextKeys,
            kind = kind,
            strict = strict,
        )

    fun stageNode(
        table: String = "stg_orders",
        id: String = "stage_orders",
    ): Node =
        Fixtures.node(
            id = id,
            source = "pg-prod",
            template = TemplateRef(SQL_TEMPLATE, 1),
            output = NodeOutput.Tempdb(table),
        )

    val parameters =
        mapOf(
            "min_total" to
                Parameter(
                    type = LogicalType.DECIMAL,
                    required = true,
                    precision = 12,
                    scale = 2,
                    description = "Minimum total.",
                ),
        )
}
