package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * pipeline-contract §12.9 — the composition rules for PIPELINE nodes, one test per table row.
 *
 * Every test builds a parent with a single PIPELINE node and a resolver over in-memory child
 * bodies, then perturbs exactly one thing; the assertion names exactly the code that perturbation
 * must produce, so a green suite proves each row of §12.9 individually.
 */
class CompositionRulesTest {
    private val workspaceId = UUID.randomUUID()

    @Test
    fun `a valid PIPELINE node passes`() {
        validatorWith(resolver(child())).validate(parent(), workspaceId).failures shouldContainExactly emptyList()
    }

    @Test
    fun `a pipeline that does not exist is pipeline_not_found`() {
        val result = validatorWith(PipelineResolver { _, _, _ -> null }).validate(parent(), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_NOT_FOUND).single()
        failure.path shouldBe "nodes[0].pipeline"
        result.codes shouldContainExactly listOf(Validation.PIPELINE_NOT_FOUND)
    }

    @Test
    fun `a missing pipeline reference on a PIPELINE node is pipeline_not_found`() {
        // Mirrors absent-`template` reporting `template_not_found`: the field is required, and
        // §12.9 carries no separate "missing" code — the empty reference resolves to nothing.
        val result = validatorWith(resolver(child())).validate(parent(ref = null), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_NOT_FOUND)
    }

    @Test
    fun `an unknown pinned version of a known name is pipeline_version_not_found`() {
        // The name resolves at version 1 (every registered pipeline has one), so the resolver
        // returning null for the pinned 99 means the VERSION is what is missing.
        val result = validatorWith(resolver(child())).validate(parent(ref = PipelineNodeRef(CHILD, 99)), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_VERSION_NOT_FOUND)
    }

    @Test
    fun `referencing the containing pipeline by name is pipeline_self_reference`() {
        // The resolver answers every name here so the ONLY defect in play is the self-reference.
        val resolveAny = PipelineResolver { _, _, version -> if (version == 1) ResolvedPipeline(child(), false) else null }

        val result = validatorWith(resolveAny).validate(parent(ref = PipelineNodeRef(PARENT, 1)), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_SELF_REFERENCE)
    }

    @Test
    fun `a soft-deleted reference blocks the new save with pipeline_reference_deleted`() {
        // D7: soft-delete blocks NEW references only — existing pinned references still resolve.
        val result = validatorWith(resolver(child(), deleted = true)).validate(parent(), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_REFERENCE_DELETED)
    }

    @Test
    fun `a PIPELINE node carrying a source is pipeline_node_has_source`() {
        val result = validatorWith(resolver(child())).validate(parent(source = "pg-prod"), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_NODE_HAS_SOURCE)
    }

    @Test
    fun `a PIPELINE node carrying a template is pipeline_node_has_template`() {
        val result = validatorWith(resolver(child())).validate(parent(template = TemplateRef("t.sql", 1)), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_NODE_HAS_TEMPLATE)
    }

    @Test
    fun `an unsupplied required-without-default child parameter is pipeline_parameter_unmapped`() {
        val result = validatorWith(resolver(child())).validate(parent(parameters = null), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_UNMAPPED)
    }

    @Test
    fun `a supplied key the child does not declare is pipeline_parameter_unknown`() {
        val supplied = mapOf("start_date" to Fixtures.json("\"2026-08-01\""), "bogus" to Fixtures.json("1"))

        val result = validatorWith(resolver(child())).validate(parent(parameters = supplied), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_UNKNOWN)
    }

    @Test
    fun `a literal failing the child parameter's wire encoding is pipeline_parameter_type_mismatch`() {
        val supplied = mapOf("start_date" to Fixtures.json("\"not a date\""))

        val result = validatorWith(resolver(child())).validate(parent(parameters = supplied), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
    }

    @Test
    fun `a reference naming no parent parameter is pipeline_parameter_type_mismatch`() {
        val supplied = mapOf("start_date" to Fixtures.json("\"\${missing}\""))

        val result = validatorWith(resolver(child())).validate(parent(parameters = supplied), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
    }

    @Test
    fun `a reference to a parent parameter of a different type is pipeline_parameter_type_mismatch`() {
        val supplied = mapOf("start_date" to Fixtures.json("\"\${start_date}\""))
        val parentParameters = mapOf("start_date" to Parameter(LogicalType.STRING, required = true))

        val result =
            validatorWith(resolver(child()))
                .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
    }

    @Test
    fun `a reference to a parent parameter of the identical type passes`() {
        val supplied = mapOf("start_date" to Fixtures.json("\"\${start_date}\""))
        val parentParameters = mapOf("start_date" to Parameter(LogicalType.DATE, required = true))

        validatorWith(resolver(child()))
            .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `an output block on a zero-caller child is pipeline_output_on_sideeffect_child`() {
        val result =
            validatorWith(resolver(child(caller = false)))
                .validate(parent(output = NodeOutput.Caller), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_OUTPUT_ON_SIDEEFFECT_CHILD)
    }

    @Test
    fun `a reference chain deeper than the configured maximum is composition_too_deep`() {
        // parent -> child -> grandchild is a depth-3 tree; a max of 2 must reject it at save.
        val grandchild = Fixtures.pipeline(name = GRANDCHILD)
        val intermediate =
            Fixtures.pipeline(
                name = CHILD,
                parameters = childParameters(),
                nodes = listOf(pipelineNode(ref = PipelineNodeRef(GRANDCHILD, 1))),
            )
        val resolver =
            PipelineResolver { _, name, version ->
                when (name to version) {
                    CHILD to 1, CHILD to CHILD_VERSION -> ResolvedPipeline(intermediate, deleted = false)
                    GRANDCHILD to 1 -> ResolvedPipeline(grandchild, deleted = false)
                    else -> null
                }
            }

        val result = validatorWith(resolver, maxDepth = 2).validate(parent(), workspaceId)

        result.codes shouldContainExactly listOf(Validation.COMPOSITION_TOO_DEEP)
    }

    @Test
    fun `a reference chain at exactly the configured maximum passes`() {
        val grandchild = Fixtures.pipeline(name = GRANDCHILD)
        val intermediate =
            Fixtures.pipeline(
                name = CHILD,
                parameters = childParameters(),
                nodes = listOf(pipelineNode(ref = PipelineNodeRef(GRANDCHILD, 1))),
            )
        val resolver =
            PipelineResolver { _, name, version ->
                when (name to version) {
                    CHILD to 1, CHILD to CHILD_VERSION -> ResolvedPipeline(intermediate, deleted = false)
                    GRANDCHILD to 1 -> ResolvedPipeline(grandchild, deleted = false)
                    else -> null
                }
            }

        validatorWith(resolver, maxDepth = 3).validate(parent(), workspaceId).failures shouldContainExactly emptyList()
    }

    @Test
    fun `a pipeline without PIPELINE nodes never consults the resolver`() {
        val resolver =
            PipelineResolver { _, _, _ -> error("resolver consulted for a pipeline with no PIPELINE nodes") }

        validatorWith(resolver).validate(Fixtures.pipeline(), workspaceId).failures shouldContainExactly emptyList()
    }

    // ------------------------------------------------------------------ helpers

    /** The default child: one required DATE parameter, one optional STRING, one caller node. */
    private fun child(
        parameters: Map<String, Parameter> = childParameters(),
        caller: Boolean = true,
    ): Pipeline =
        Fixtures.pipeline(
            name = CHILD,
            parameters = parameters,
            nodes = listOf(Fixtures.node(output = if (caller) NodeOutput.Caller else NodeOutput.Tempdb("stg_child"))),
        )

    private fun childParameters(): Map<String, Parameter> =
        mapOf(
            "start_date" to Parameter(LogicalType.DATE, required = true),
            "region" to Parameter(LogicalType.STRING, default = Fixtures.json("\"EU\"")),
        )

    /** A resolver that knows [body] at version 1 (the name-existence probe) and the pinned version. */
    private fun resolver(
        body: Pipeline,
        deleted: Boolean = false,
    ): PipelineResolver =
        PipelineResolver { _, name, version ->
            if (name == CHILD && (version == 1 || version == CHILD_VERSION)) {
                ResolvedPipeline(body, deleted)
            } else {
                null
            }
        }

    private fun validatorWith(
        pipelines: PipelineResolver,
        maxDepth: Int = 5,
    ): PipelineValidator = Fixtures.validator(pipelines = pipelines, maxCompositionDepth = maxDepth)

    private fun parent(
        ref: PipelineNodeRef? = PipelineNodeRef(CHILD, CHILD_VERSION),
        parameters: Map<String, JsonNode>? = mapOf("start_date" to Fixtures.json("\"2026-08-01\"")),
        output: NodeOutput? = null,
        source: String = "",
        template: TemplateRef = TemplateRef(),
        parentParameters: Map<String, Parameter> = emptyMap(),
    ): Pipeline =
        Fixtures.pipeline(
            name = PARENT,
            parameters = parentParameters,
            nodes = listOf(pipelineNode(ref, parameters, output, source, template)),
        )

    private fun pipelineNode(
        ref: PipelineNodeRef?,
        parameters: Map<String, JsonNode>? = null,
        output: NodeOutput? = null,
        source: String = "",
        template: TemplateRef = TemplateRef(),
    ): Node =
        Node(
            id = "run_child",
            description = "Runs the child pipeline.",
            type = NodeType.PIPELINE,
            source = source,
            template = template,
            output = output,
            dependsOn = emptyList(),
            pipeline = ref,
            parameters = parameters,
        )

    private companion object {
        const val PARENT = "parent_pipeline"
        const val CHILD = "monthly_revenue_component"
        const val GRANDCHILD = "daily_revenue_component"
        const val CHILD_VERSION = 4
    }
}
