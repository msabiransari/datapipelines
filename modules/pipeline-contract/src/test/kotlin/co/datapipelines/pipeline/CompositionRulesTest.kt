package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterConstraints
import com.fasterxml.jackson.databind.JsonNode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
        val result = validatorWith(resolver(child())).validate(parent(template = TemplateRef("test/t.sql", 1)), workspaceId)

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

    // ------------------------------------------------ #264: a literal is judged by the child's WHOLE declaration

    @Test
    fun `a same-type reference into a narrower child DECIMAL is pipeline_parameter_invalid - narrowing`() {
        // The #264 defect: a parent DECIMAL(12,4) feeding a child DECIMAL(12,2) saved, and failed
        // at run whenever the parent value had more than two places. The parent parameter's
        // descriptor must widen losslessly into the child's (parameter-engine record §6.4).
        val child = child(parameters = mapOf("amount" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 2)))
        val supplied = mapOf("amount" to Fixtures.json("\"\${anchor}\""))
        val parentParameters = mapOf("anchor" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 4))

        val result =
            validatorWith(resolver(child))
                .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_INVALID).single()
        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_INVALID)
        failure.details["parameter"] shouldBe "amount"
        failure.details["reference"] shouldBe "anchor"
        failure.details["reason"] shouldBe "narrowing"
        failure.message shouldContain "anchor"
    }

    @Test
    fun `a same-type reference between identical descriptors passes`() {
        val child = child(parameters = mapOf("amount" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 4)))
        val supplied = mapOf("amount" to Fixtures.json("\"\${anchor}\""))
        val parentParameters = mapOf("anchor" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 4))

        validatorWith(resolver(child))
            .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `a lossless same-type widening passes - the child holds every digit the parent can carry`() {
        // The one direction §6.4 admits at equal type: more integer digits AND at least the
        // parent's fractional digits.
        val child = child(parameters = mapOf("amount" to Parameter(LogicalType.DECIMAL, required = true, precision = 14, scale = 4)))
        val supplied = mapOf("amount" to Fixtures.json("\"\${anchor}\""))
        val parentParameters = mapOf("anchor" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 2))

        validatorWith(resolver(child))
            .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `a cross-type reference is still the type mismatch - widening across types is not adopted`() {
        // INTEGER -> BIGINTEGER saves nothing today and must stay exactly that refusal: the run's
        // wire encoder cannot carry a cross-type value (SubPipelineExecutionRunner re-encodes by
        // the CHILD's type), so the save-time rule must not open that door.
        val child = child(parameters = mapOf("count" to Parameter(LogicalType.BIGINTEGER, required = true)))
        val supplied = mapOf("count" to Fixtures.json("\"\${anchor}\""))
        val parentParameters = mapOf("anchor" to Parameter(LogicalType.INTEGER, required = true))

        val result =
            validatorWith(resolver(child))
                .validate(parent(parameters = supplied, parentParameters = parentParameters), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
    }

    @Test
    fun `a literal over the child's declared max is pipeline_parameter_invalid`() {
        // The #264 defect: `-1` for a child declaring `max: 100` saved cleanly and failed only
        // when the composition ran (the child's binder refused it). Save now judges the literal
        // by the same declaration the run will.
        val child =
            child(
                parameters =
                    mapOf(
                        "amount" to
                            Parameter(
                                LogicalType.INTEGER,
                                required = true,
                                constraints = ParameterConstraints(max = Fixtures.json("100")),
                            ),
                    ),
            )
        val supplied = mapOf("amount" to Fixtures.json("500"))

        val result = validatorWith(resolver(child)).validate(parent(parameters = supplied), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_INVALID).single()
        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_INVALID)
        failure.path shouldBe "nodes[0].parameters.amount"
        failure.details["node"] shouldBe "run_child"
        failure.details["parameter"] shouldBe "amount"
        failure.details["reason"] shouldBe "max"
        // The refusal names the rule, never the value.
        failure.message shouldNotContain "500"
    }

    @Test
    fun `a literal failing the child's pattern is pipeline_parameter_invalid`() {
        val child =
            child(
                parameters =
                    mapOf(
                        "code" to
                            Parameter(
                                LogicalType.STRING,
                                required = true,
                                constraints = ParameterConstraints(pattern = "[a-z]+"),
                            ),
                    ),
            )
        val supplied = mapOf("code" to Fixtures.json("\"123\""))

        val result = validatorWith(resolver(child)).validate(parent(parameters = supplied), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_INVALID).single()
        failure.details["parameter"] shouldBe "code"
        failure.details["reason"] shouldBe "pattern"
    }

    @Test
    fun `a literal with more places than the child's DECIMAL scale is pipeline_parameter_invalid`() {
        val child =
            child(parameters = mapOf("amount" to Parameter(LogicalType.DECIMAL, required = true, precision = 12, scale = 2)))
        val supplied = mapOf("amount" to Fixtures.json("1.234"))

        val result = validatorWith(resolver(child)).validate(parent(parameters = supplied), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_INVALID).single()
        failure.details["parameter"] shouldBe "amount"
        failure.details["reason"] shouldBe "scale"
    }

    @Test
    fun `a list literal into a SINGLE parameter is pipeline_parameter_type_mismatch`() {
        val supplied = mapOf("start_date" to Fixtures.json("[2026, 8, 1]"))

        val result = validatorWith(resolver(child())).validate(parent(parameters = supplied), workspaceId)

        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
    }

    @Test
    fun `a literal satisfying the child's whole declaration saves`() {
        val child =
            child(
                parameters =
                    mapOf(
                        "amount" to
                            Parameter(
                                LogicalType.INTEGER,
                                required = true,
                                constraints = ParameterConstraints(min = Fixtures.json("0"), max = Fixtures.json("100")),
                            ),
                    ),
            )
        val supplied = mapOf("amount" to Fixtures.json("50"))

        validatorWith(resolver(child)).validate(parent(parameters = supplied), workspaceId).failures shouldContainExactly
            emptyList()
    }

    @Test
    fun `an unsound child declaration falls back to the type-only check`() {
        // The child's own constraints are one save refuses (min > max): reported at the CHILD's
        // save, so the composition judges only the type — never a declaration it cannot trust
        // (the validator refuses an unsound declaration outright, the checkDefault mould).
        val child =
            child(
                parameters =
                    mapOf(
                        "amount" to
                            Parameter(
                                LogicalType.INTEGER,
                                required = true,
                                constraints = ParameterConstraints(min = Fixtures.json("100"), max = Fixtures.json("1")),
                            ),
                    ),
            )

        // A type-broken literal is the type mismatch and nothing else.
        val typeBroken =
            validatorWith(resolver(child)).validate(parent(parameters = mapOf("amount" to Fixtures.json("\"abc\""))), workspaceId)
        typeBroken.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)

        // A type-sound literal saves — the unsound constraints are not this save's verdict.
        val typeSound =
            validatorWith(resolver(child)).validate(parent(parameters = mapOf("amount" to Fixtures.json("5"))), workspaceId)
        typeSound.failures shouldContainExactly emptyList()
    }

    // ------------------------------------------------ 078 A5-composition: the three parent tiers

    @Test
    fun `a reference to a parent calculator output of the identical type passes`() {
        val child = child(parameters = mapOf("quarter" to Parameter(LogicalType.INTEGER, required = true)))
        val supplied = mapOf("quarter" to Fixtures.json("\"\${run_fiscal_quarter}\""))

        validatorWith(resolver(child))
            .validate(parent(parameters = supplied, parentNodes = listOf(Fixtures.calculatorNode())), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `a type mismatch against a parent calculator output names that tier`() {
        val child = child(parameters = mapOf("label" to Parameter(LogicalType.STRING, required = true)))
        val supplied = mapOf("label" to Fixtures.json("\"\${run_fiscal_quarter}\""))

        val result =
            validatorWith(resolver(child))
                .validate(parent(parameters = supplied, parentNodes = listOf(Fixtures.calculatorNode())), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH).single()
        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
        failure.message shouldContain "parent calculator output 'run_fiscal_quarter'"
        failure.message shouldContain "child parameter 'label'"
    }

    @Test
    fun `a reference to an org key resolves as STRING`() {
        // org_currency_name is STRING (§0.2): it maps onto the child's STRING parameter…
        val supplied = mapOf("start_date" to Fixtures.json("\"2026-08-01\""), "region" to Fixtures.json("\"\${org_currency_name}\""))

        validatorWith(resolver(child()))
            .validate(parent(parameters = supplied), workspaceId)
            .failures shouldContainExactly emptyList()

        // …but not onto a DATE one, and the message names the tier.
        val mismatched =
            validatorWith(resolver(child()))
                .validate(parent(parameters = mapOf("start_date" to Fixtures.json("\"\${org_currency_name}\""))), workspaceId)

        mismatched.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
        mismatched.withCode(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH).single().message shouldContain
            "org key 'org_currency_name'"
    }

    @Test
    fun `a reference to a platform key resolves with its canonical type`() {
        // current_date is DATE (ContextKeys.PLATFORM_TYPES): it maps onto the child's DATE parameter…
        val supplied = mapOf("start_date" to Fixtures.json("\"\${current_date}\""))

        validatorWith(resolver(child()))
            .validate(parent(parameters = supplied), workspaceId)
            .failures shouldContainExactly emptyList()

        // …while execution_id is STRING, and the message names the tier.
        val mismatched =
            validatorWith(resolver(child()))
                .validate(parent(parameters = mapOf("start_date" to Fixtures.json("\"\${execution_id}\""))), workspaceId)

        mismatched.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
        mismatched.withCode(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH).single().message shouldContain
            "platform key 'execution_id'"
    }

    @Test
    fun `an ANY-output parent calculator key maps onto any typed target without a type check`() {
        // coalesce outputs ANY — typed only by the run — so the save-time check skips (A6's convention).
        val supplied = mapOf("start_date" to Fixtures.json("\"\${anything}\""))
        val anyNode =
            Fixtures.calculatorNode(
                id = "cq",
                kind = "coalesce",
                inputs = mapOf("values" to Fixtures.literals("2026-08-01", "2026-09-01")),
                contextKey = "anything",
            )

        validatorWith(resolver(child()))
            .validate(parent(parameters = supplied, parentNodes = listOf(anyNode)), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    // ------------------------------------------------ 078 A5-composition: the child calculator-key tier

    @Test
    fun `a supplied key that is only a child calculator context_key is not pipeline_parameter_unknown`() {
        val supplied = mapOf("child_quarter" to Fixtures.json("4"))

        validatorWith(resolver(childWithCalculator()))
            .validate(parent(parameters = supplied), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `a reference mapped onto a child calculator key of the identical type passes`() {
        val supplied = mapOf("child_quarter" to Fixtures.json("\"\${run_fiscal_quarter}\""))

        validatorWith(resolver(childWithCalculator()))
            .validate(parent(parameters = supplied, parentNodes = listOf(Fixtures.calculatorNode())), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    @Test
    fun `a type mismatch against a child calculator key names both tiers`() {
        val supplied = mapOf("child_quarter" to Fixtures.json("\"\${org_currency_name}\""))

        val result =
            validatorWith(resolver(childWithCalculator()))
                .validate(parent(parameters = supplied), workspaceId)

        val failure = result.withCode(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH).single()
        result.codes shouldContainExactly listOf(Validation.PIPELINE_PARAMETER_TYPE_MISMATCH)
        failure.message shouldContain "org key 'org_currency_name'"
        failure.message shouldContain "child calculator output 'child_quarter'"
    }

    @Test
    fun `a literal mapped onto an ANY-output child calculator key takes any scalar`() {
        val anyChild =
            Fixtures.pipeline(
                name = CHILD,
                nodes =
                    listOf(
                        Fixtures.calculatorNode(
                            id = "cq",
                            kind = "coalesce",
                            inputs = mapOf("values" to Fixtures.literals("a", "b")),
                            contextKey = "child_any",
                        ),
                        Fixtures.node(output = NodeOutput.Caller),
                    ),
            )
        val supplied = mapOf("child_any" to Fixtures.json("4"))

        validatorWith(resolver(anyChild))
            .validate(parent(parameters = supplied), workspaceId)
            .failures shouldContainExactly emptyList()
    }

    /** A child whose only input is the calculator `context_key` `child_quarter` (INTEGER output). */
    private fun childWithCalculator(): Pipeline =
        Fixtures.pipeline(
            name = CHILD,
            nodes =
                listOf(
                    Fixtures.calculatorNode(id = "cq", contextKey = "child_quarter"),
                    Fixtures.node(output = NodeOutput.Caller),
                ),
        )

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
                    CHILD to 1, CHILD to CHILD_VERSION -> ResolvedPipeline(intermediate, entityDiscarded = false)
                    GRANDCHILD to 1 -> ResolvedPipeline(grandchild, entityDiscarded = false)
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
                    CHILD to 1, CHILD to CHILD_VERSION -> ResolvedPipeline(intermediate, entityDiscarded = false)
                    GRANDCHILD to 1 -> ResolvedPipeline(grandchild, entityDiscarded = false)
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
    @Test
    fun `a child reference may name a path, and one that breaks the grammar is name_invalid`() {
        // 067: a child reference names a pipeline, so it takes the §3.2 PATH grammar. Both
        // halves matter — the folder round is useless if a composed pipeline cannot reference a
        // child under a root, and a malformed name must not masquerade as a missing pipeline.
        val pathChild = Fixtures.pipeline(name = PATH_CHILD, nodes = listOf(Fixtures.node(output = NodeOutput.Caller)))
        val pathResolver =
            PipelineResolver { _, name, version ->
                if (name == PATH_CHILD && version == 1) ResolvedPipeline(pathChild, false) else null
            }

        validatorWith(pathResolver)
            .validate(parent(ref = PipelineNodeRef(PATH_CHILD, 1), parameters = null), workspaceId)
            .failures shouldContainExactly emptyList()

        val malformed = validatorWith(resolver(child())).validate(parent(ref = PipelineNodeRef("nyc/../secrets", 1)), workspaceId)

        malformed.withCode(Validation.NAME_INVALID).single().path shouldBe "nodes[0].pipeline.name"
        malformed.codes shouldContainExactly listOf(Validation.NAME_INVALID)
    }

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
        parentNodes: List<Node> = emptyList(),
    ): Pipeline =
        Fixtures.pipeline(
            name = PARENT,
            parameters = parentParameters,
            nodes = parentNodes + pipelineNode(ref, parameters, output, source, template),
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
        const val PARENT = "test/parent_pipeline"
        const val CHILD = "test/monthly_revenue_component"
        const val GRANDCHILD = "test/daily_revenue_component"
        const val CHILD_VERSION = 4

        /** 067: a child under a folder root — the shape the demo's `mobility_briefing` now uses. */
        const val PATH_CHILD = "nyc/mobility/borough_od_matrix"
    }
}
