package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.ValidationFailure
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.visualization.DocumentFixtures.obj
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

/**
 * [DashboardValidator]: the spec's worked document passes unchanged; each validation code the validator owns
 * is reached by exactly one case (held to the catalog by the non-vacuity test); every rule's reasons pinned —
 * the namespace, the dependencies through the three ports, the objects, scopes, overrides and the layout.
 */
class DashboardValidatorTest {
    @Test
    fun `the spec's worked document is valid unchanged - DRAFT set and visualization pins are accepted at save`() {
        valid(DocumentFixtures.dashboard())
        valid(DocumentFixtures.dashboard()) { fakes ->
            fakes.sets[ValidatorFakes.SET_REF] = fakes.sets.getValue(ValidatorFakes.SET_REF).copy(status = PipelineVersionStatus.DRAFT)
            fakes.visualizations[ValidatorFakes.VISUALIZATION_REF] =
                fakes.visualizations.getValue(ValidatorFakes.VISUALIZATION_REF).copy(status = PipelineVersionStatus.DRAFT)
        }
    }

    @Test
    fun `every validation code the validator owns is reached by exactly one case, and each case raises only its code`() {
        CASES.forEach { case ->
            withClue(case.code) {
                val failures = failures(DocumentFixtures.dashboard().also(case.mutate), case.setup)
                failures.map { it.code }.toSet() shouldBe setOf(case.code)
                failures.map { it.path to it.details["reason"] } shouldBe listOf(case.path to case.reason)
            }
        }
    }

    @Test
    fun `non-vacuity - the cases cover exactly the validation codes this validator raises`() {
        CASES.map { it.code }.toSet() shouldBe VALIDATOR_CODES
        CASES.size shouldBe VALIDATOR_CODES.size
    }

    @Test
    fun `the namespace - object grammar, a clash across kinds or with a set parameter, sources unique among themselves`() {
        codes(mutate = { it.obj("visualizations[0]").put("name", "RevenueChart") }).first() shouldBe
            (DashboardErrorCodes.DUPLICATE_NAME to "visualizations[0].name")
        codes(mutate = { (it.get("sources") as ArrayNode).add(it.obj("sources[0]").deepCopy()) }) shouldBe
            listOf(DashboardErrorCodes.DUPLICATE_NAME to "sources[1].name")
        codes(mutate = { it.obj("action_controls[0]").put("name", "overview_group") }).first() shouldBe
            (DashboardErrorCodes.DUPLICATE_NAME to "action_controls[0].name")
    }

    @Test
    fun `the dependencies - a missing pipeline or set, a set parameter the set lacks, a required parameter an override satisfies`() {
        codes({ it.pipelines.clear() }) shouldBe listOf(DashboardErrorCodes.DEPENDENCY_NOT_FOUND to "sources[0].pipeline")
        codes({ it.sets.clear() }) shouldContainExactlyInAnyOrder
            listOf(
                DashboardErrorCodes.DEPENDENCY_NOT_FOUND to "parameter_set",
                DashboardErrorCodes.PARAMETER_UNBOUND to "sources[0].parameters.year.parameter",
                DashboardErrorCodes.UNKNOWN_OBJECT to "groups[0].members[0]",
                DashboardErrorCodes.UNKNOWN_OBJECT to "parameter_scopes.year",
                DashboardErrorCodes.UNKNOWN_OBJECT to "parameter_state.parameters.currency",
                DashboardErrorCodes.UNKNOWN_OBJECT to "layout.parameter_placements.year",
            )
        codes({ it.sets[ValidatorFakes.SET_REF] = it.sets.getValue(ValidatorFakes.SET_REF).copy(status = PipelineVersionStatus.DISCARDED) })
            .first() shouldBe (DashboardErrorCodes.DEPENDENCY_NOT_FOUND to "parameter_set")
        codes(mutate = { it.obj("sources[0].parameters.year").put("parameter", "fiscal_year") }) shouldBe
            listOf(DashboardErrorCodes.PARAMETER_UNBOUND to "sources[0].parameters.year.parameter")
        codes(mutate = { it.obj("sources[0].parameters").putObject("region").put("value", "EU") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "sources[0].parameters.region")
        val overridden = DocumentFixtures.dashboard()
        overridden.obj("sources[0].parameters").remove("year")
        overridden.obj("outgoing_overrides.revenue_source").putObject("year").put("value", 2026)
        valid(overridden)
    }

    @Test
    fun `the occurrences - an input the visualization lacks, an unknown source, a timeout past the cap`() {
        codes(mutate = { it.obj("visualizations[0].inputs").putObject("target").put("source", "revenue_source") }) shouldBe
            listOf(DashboardErrorCodes.INPUT_UNBOUND to "visualizations[0].inputs.target")
        codes(mutate = { it.obj("visualizations[0].inputs.revenue").put("source", "sales_source") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "visualizations[0].inputs.revenue.source")
        reasons(mutate = { it.obj("visualizations[0]").put("timeout_seconds", 0) }) shouldBe
            listOf("visualizations[0].timeout_seconds" to "out_of_range")
        codes({
            it.pipelines[ValidatorFakes.PIPELINE_REF] =
                it.pipelines.getValue(ValidatorFakes.PIPELINE_REF).copy(outputColumns = emptyList())
        })
            .shouldBe(listOf(DashboardErrorCodes.INPUT_CONTRACT_MISMATCH to "visualizations[0].inputs.revenue"))
    }

    @Test
    fun `a source whose release does not declare its caller columns is not judged at save - never a guess`() {
        // L1b: a SQL caller node names no columns (only a transform's contract does), so the port answers null and the
        // input contract is the runtime's to check (L2). EMPTY stays a refusal: that release returns no rows at all.
        valid(DocumentFixtures.dashboard()) {
            it.pipelines[ValidatorFakes.PIPELINE_REF] = it.pipelines.getValue(ValidatorFakes.PIPELINE_REF).copy(outputColumns = null)
        }
    }

    @Test
    fun `the actions and controls - scope all with targets, an unknown target, a control naming no action or parameter`() {
        reasons(mutate = { it.obj("actions[0]").put("scope", "all") }) shouldBe listOf("actions[0].targets" to "targets_with_all")
        codes(mutate = { (it.obj("actions[0]").get("targets") as ArrayNode).add("pie_chart") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "actions[0].targets[1]")
        codes(mutate = { it.obj("action_controls[0]").put("parameter", "region") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "action_controls[0].parameter")
        valid(DocumentFixtures.dashboard().also { it.obj("action_controls[0]").put("parameter", "currency") })
    }

    @Test
    fun `a scope counts consumers through dependents - a group feeding a child parameter consumes its parent`() {
        // year is a literal to the source now; only currency — year's DEPENDENT — reaches it from the set.
        val tree = DocumentFixtures.dashboard()
        tree.obj("sources[0].parameters.year").remove("parameter")
        tree.obj("sources[0].parameters.year").put("value", 2026)
        tree.obj("sources[0].parameters.currency").remove("value")
        tree.obj("sources[0].parameters.currency").put("parameter", "currency")
        tree.obj("parameter_scopes").putArray("year")
        valid(tree)
        val yearIsParent: (ValidatorFakes.Fakes) -> Unit = {
            it.sets[ValidatorFakes.SET_REF] =
                it.sets
                    .getValue(ValidatorFakes.SET_REF)
                    .copy(parameters = listOf(SetParameterFact("year", setOf("currency")), SetParameterFact("currency", emptySet())))
        }
        failures(tree, yearIsParent).map { it.code to it.path } shouldBe
            listOf(DashboardErrorCodes.SCOPE_OMITS_CONSUMER to "parameter_scopes.year")
    }

    @Test
    fun `the scopes, state and overrides name only what exists`() {
        codes(mutate = {
            it
                .obj("parameter_scopes")
                .putArray("year")
                .add("overview_group")
                .add("sidebar")
        }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "parameter_scopes.year[1]")
        codes(mutate = { it.obj("parameter_state.parameters").putObject("region") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "parameter_state.parameters.region")
        codes(mutate = { it.obj("outgoing_overrides").putObject("sales_source") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "outgoing_overrides.sales_source")
        codes(mutate = { it.obj("outgoing_overrides.revenue_source").putObject("region").put("value", "EU") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "outgoing_overrides.revenue_source.region")
        codes(mutate = { (it.obj("groups[0]").get("members") as ArrayNode).add("ghost") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "groups[0].members[3]")
    }

    @Test
    fun `the layout - every occurrence gridded once, a control placed once, a group at most once, no second parent, no cycle`() {
        reasons(mutate = { it.obj("layout").putArray("grid") }) shouldBe listOf("visualizations[0]" to "unplaced")
        reasons(mutate = {
            grid(it)
                .addObject()
                .put("name", "refresh_button")
                .put("x", 6)
                .put("y", 0)
                .put("w", 2)
                .put("h", 1)
        }) shouldBe
            listOf("action_controls[0]" to "placed_twice")
        reasons(mutate = { (it.obj("groups[0]").get("members") as ArrayNode).remove(2) }) shouldBe
            listOf("action_controls[0]" to "unplaced")
        reasons(mutate = {
            grid(it)
                .addObject()
                .put("name", "overview_group")
                .put("x", 6)
                .put("y", 0)
                .put("w", 6)
                .put("h", 4)
        }) shouldBe
            emptyList()
        reasons(mutate = {
            (
                it.get(
                    "groups",
                ) as ArrayNode
            ).addObject().put("name", "side_group").put("type", "group").putArray("members").add("refresh_button")
        }) shouldBe listOf("groups[1].members[0]" to "multiple_parents")
        reasons(mutate = { (it.obj("groups[0]").get("members") as ArrayNode).add("overview_group") }) shouldBe
            listOf("groups[0].members" to "cycle")
        reasons(mutate = { it.obj("layout").put("columns", 10) }) shouldBe listOf("layout.columns" to "columns")
        reasons(mutate = { it.obj("layout").put("breakpoint_px", 0) }) shouldBe listOf("layout.breakpoint_px" to "breakpoint")
        reasons(mutate = { grid(it).add(it.obj("layout.grid[0]").deepCopy()) }) shouldBe listOf("layout.grid[1].name" to "duplicate")
        reasons(mutate = {
            grid(it)
                .addObject()
                .put("name", "year")
                .put("x", 0)
                .put("y", 5)
                .put("w", 2)
                .put("h", 1)
        }) shouldBe
            listOf("layout.grid[1].name" to "not_placeable")
        reasons(mutate = { (it.obj("groups[0]").get("members") as ArrayNode).add("refresh_overview") }) shouldBe
            listOf("groups[0].members[3]" to "not_placeable")
        codes(mutate = { it.obj("layout.parameter_placements.year").put("group", "sidebar") }) shouldBe
            listOf(DashboardErrorCodes.UNKNOWN_OBJECT to "layout.parameter_placements.year.group")
    }

    @Test
    fun `a 30,000-deep group chain validates in linear time - one cycle pass, one scope pass, every consumer still found`() {
        val tree = DocumentFixtures.dashboard()
        val groups = tree.get("groups") as ArrayNode
        // g0 → g1 → … → g29999 → overview_group: the scoped consumer (revenue_chart) sits at the bottom of the chain,
        // so every chain group consumes `year` and the declared scope, which names overview_group only, omits them all.
        (0 until CHAIN_DEPTH).forEach { i ->
            groups
                .addObject()
                .put("name", "g$i")
                .put("type", "group")
                .putArray("members")
                .add(if (i == CHAIN_DEPTH - 1) "overview_group" else "g${i + 1}")
        }
        val document = ValidatorFakes.dashboardDocument(tree)
        val validator = ValidatorFakes.Fakes().dashboardValidator()
        val result =
            assertTimeoutPreemptively(
                Duration.ofSeconds(CHAIN_SECONDS),
                ThrowingSupplier { validator.validate(ValidatorFakes.WORKSPACE, document) },
            )
        val failures = result.shouldBeInstanceOf<ArtifactValidation.Invalid>().result.failures
        failures.first().code shouldBe DashboardErrorCodes.SCOPE_OMITS_CONSUMER
        failures.first().details["group"] shouldBe "g0"
        failures.size shouldBe ArtifactFailures.MAX_FAILURES + 1
        failures.last().details["reason"] shouldBe "too_many_failures"
    }

    private fun grid(tree: ObjectNode): ArrayNode = tree.obj("layout").get("grid") as ArrayNode

    private fun valid(
        tree: ObjectNode,
        setup: (ValidatorFakes.Fakes) -> Unit = {},
    ) {
        ValidatorFakes
            .Fakes()
            .also(setup)
            .dashboardValidator()
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.dashboardDocument(tree))
            .shouldBeInstanceOf<ArtifactValidation.Valid<DashboardDocument>>()
    }

    private fun failures(
        tree: ObjectNode,
        setup: (ValidatorFakes.Fakes) -> Unit = {},
    ): List<ValidationFailure> =
        ValidatorFakes
            .Fakes()
            .also(setup)
            .dashboardValidator()
            .validate(ValidatorFakes.WORKSPACE, ValidatorFakes.dashboardDocument(tree))
            .let { it as? ArtifactValidation.Invalid }
            ?.result
            ?.failures
            .orEmpty()

    private fun codes(
        setup: (ValidatorFakes.Fakes) -> Unit = {},
        mutate: (ObjectNode) -> Unit = {},
    ): List<Pair<String, String>> = failures(DocumentFixtures.dashboard().also(mutate), setup).map { it.code to it.path }

    private fun reasons(
        setup: (ValidatorFakes.Fakes) -> Unit = {},
        mutate: (ObjectNode) -> Unit = {},
    ): List<Pair<String, Any?>> = failures(DocumentFixtures.dashboard().also(mutate), setup).map { it.path to it.details["reason"] }

    private data class Case(
        val code: String,
        val path: String,
        val reason: String?,
        val setup: (ValidatorFakes.Fakes) -> Unit = {},
        val mutate: (ObjectNode) -> Unit = {},
    )

    private companion object {
        /** Deep enough that a walk per group (quadratic) takes minutes; the linear passes take well under a second. */
        const val CHAIN_DEPTH = 30_000
        const val CHAIN_SECONDS = 10L

        /** The validation codes the VALIDATOR raises — `new_root_requires_confirmation` is L1b's, `name_taken` the repository's. */
        val VALIDATOR_CODES: Set<String> =
            DashboardErrorCodes.ALL.filter { it.startsWith("dashboard.validation.") }.toSet() -
                setOf(DashboardErrorCodes.NEW_ROOT_REQUIRES_CONFIRMATION, DashboardErrorCodes.NAME_TAKEN)

        private fun pipeline(
            fakes: ValidatorFakes.Fakes,
            change: (PipelineReleaseFact) -> PipelineReleaseFact,
        ) {
            fakes.pipelines[ValidatorFakes.PIPELINE_REF] = change(fakes.pipelines.getValue(ValidatorFakes.PIPELINE_REF))
        }

        val CASES: List<Case> =
            listOf(
                Case(DashboardErrorCodes.NAME_INVALID, "name", "folder_required", mutate = { it.put("name", "flat") }),
                Case(DashboardErrorCodes.BODY_INVALID, "timeouts.refresh_seconds", "out_of_range", mutate = {
                    it.obj("timeouts").put("refresh_seconds", DashboardValidator.DEFAULT_MAX_REFRESH_SECONDS + 1)
                }),
                Case(DashboardErrorCodes.DUPLICATE_NAME, "groups[1].name", null, mutate = {
                    (it.get("groups") as ArrayNode).addObject().put("name", "year").put("type", "group")
                }),
                Case(DashboardErrorCodes.UNKNOWN_OBJECT, "action_controls[0].action", null, mutate = {
                    it.obj("action_controls[0]").put("action", "refresh_all")
                }),
                Case(DashboardErrorCodes.TARGET_NOT_VISUALIZATION, "actions[0].targets[0]", null, mutate = {
                    it.obj("actions[0]").putArray("targets").add("overview_group")
                }),
                Case(
                    DashboardErrorCodes.EMPTY_TARGETS,
                    "actions[0].targets",
                    "empty",
                    mutate = { it.obj("actions[0]").putArray("targets") },
                ),
                Case(DashboardErrorCodes.PARENT_ACTION_BINDING, "action_controls[0].parameter", null, setup = {
                    it.sets[ValidatorFakes.SET_REF] =
                        it.sets
                            .getValue(ValidatorFakes.SET_REF)
                            .copy(
                                parameters = listOf(SetParameterFact("year", setOf("currency")), SetParameterFact("currency", emptySet())),
                            )
                }, mutate = { it.obj("action_controls[0]").put("parameter", "year") }),
                Case(
                    DashboardErrorCodes.SCOPE_OMITS_CONSUMER,
                    "parameter_scopes.year",
                    null,
                    mutate = { it.obj("parameter_scopes").putArray("year") },
                ),
                Case(DashboardErrorCodes.SOURCE_NOT_RELEASED, "sources[0].pipeline", null, setup = {
                    pipeline(it) { fact -> fact.copy(status = PipelineVersionStatus.DRAFT) }
                }),
                Case(DashboardErrorCodes.SOURCE_NOT_READ_ONLY, "sources[0].pipeline", null, setup = {
                    pipeline(it) { fact -> fact.copy(readOnly = false) }
                }),
                Case(DashboardErrorCodes.PARAMETER_UNBOUND, "sources[0].parameters", "required", mutate = {
                    it.obj("sources[0].parameters").remove("year")
                }),
                Case(DashboardErrorCodes.INPUT_UNBOUND, "visualizations[0].inputs", "unmapped", mutate = {
                    it.obj("visualizations[0].inputs").remove("revenue")
                }),
                Case(DashboardErrorCodes.INPUT_CONTRACT_MISMATCH, "visualizations[0].inputs.revenue", null, setup = {
                    pipeline(it) { fact ->
                        fact.copy(
                            outputColumns =
                                listOf(
                                    OutputColumn("month", LogicalType.DATE, false),
                                    OutputColumn("amount", LogicalType.BIGDECIMAL, false),
                                ),
                        )
                    }
                }),
                Case(DashboardErrorCodes.LAYOUT_INVALID, "layout.grid[0]", "geometry", mutate = { it.obj("layout.grid[0]").put("w", 13) }),
                Case(
                    DashboardErrorCodes.DEPENDENCY_NOT_FOUND,
                    "visualizations[0].visualization",
                    null,
                    setup = { it.visualizations.clear() },
                ),
            )
    }
}
