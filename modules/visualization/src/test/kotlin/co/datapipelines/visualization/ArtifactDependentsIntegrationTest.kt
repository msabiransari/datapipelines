package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.visualization.VisualizationTestDb.AUTHOR
import co.datapipelines.visualization.VisualizationTestDb.OTHER_WORKSPACE
import co.datapipelines.visualization.VisualizationTestDb.WORKSPACE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The three reverse arrows [ArtifactDependents] answers, against the real V42 schema (#320) — the
 * `ParameterSetTemplatePinsIntegrationTest` shape: DRAFT, RELEASED and DISCARDED rows, the exact-version match, the
 * three [PinScope]s, and another workspace's same-named artifact. The rows are seeded by SQL (a body that binds
 * nothing but the pin under test), because the question is the JSONB predicate and the scope, not the readers.
 *
 * Each arrow is run through the SAME cases by [arrows]: the statement is one shape ([ArtifactDependents.scan]) and the
 * three differ only in the body path, so a case written once over all three cannot drift between them.
 */
class ArtifactDependentsIntegrationTest {
    private val dependents = ArtifactDependents(VisualizationTestDb.jdbc)

    @BeforeEach
    fun reset() = VisualizationTestDb.reset()

    /** One arrow: the dependent's family, a body that pins `name@version`, and the scan that answers the question. */
    private class Arrow(
        val label: String,
        val kind: ArtifactKind,
        val bodyPinning: (name: String, version: Int) -> String,
        val ask: (ArtifactDependents, UUID, String, Int?, PinScope) -> List<ArtifactPin>,
    )

    private val arrows =
        listOf(
            Arrow(
                "visualization -> transform template",
                ArtifactKind.VISUALIZATION,
                { name, version -> """{"transform": {"template": {"name": "$name", "version": $version}, "inputs": {}}}""" },
                { d, workspace, name, version, scope -> d.visualizationsPinningTemplate(workspace, name, version, scope) },
            ),
            Arrow(
                "dashboard -> pipeline release",
                ArtifactKind.DASHBOARD,
                { name, version ->
                    """{"sources": [{"name": "other", "pipeline": {"name": "unrelated/pipe", "version": 1}},""" +
                        """{"name": "s", "pipeline": {"name": "$name", "version": $version}}]}"""
                },
                { d, workspace, name, version, scope -> d.dashboardsPinningPipeline(workspace, name, version, scope) },
            ),
            Arrow(
                "dashboard -> parameter set",
                ArtifactKind.DASHBOARD,
                { name, version -> """{"parameter_set": {"name": "$name", "version": $version}}""" },
                { d, workspace, name, version, scope -> d.dashboardsPinningParameterSet(workspace, name, version, scope) },
            ),
        )

    private data class Row(
        val version: Int,
        val status: String,
        val pins: String,
    )

    @Test
    fun `LIVE finds DRAFT and RELEASED versions at the exact pin - never DISCARDED, never another version`() {
        eachArrow { arrow ->
            seed(
                arrow,
                "dep/live",
                pointer = 2,
                Row(1, "DISCARDED", arrow.bodyPinning(TARGET, 3)),
                Row(2, "RELEASED", arrow.bodyPinning(TARGET, 3)),
                Row(3, "DRAFT", arrow.bodyPinning(TARGET, 3)),
            )
            seed(arrow, "dep/other_version", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 4)))

            val pins = arrow.ask(dependents, WORKSPACE, TARGET, 3, PinScope.LIVE)

            pins.map { Triple(it.name, it.version, it.status) } shouldBe
                listOf(
                    Triple("dep/live", 3, PipelineVersionStatus.DRAFT),
                    Triple("dep/live", 2, PipelineVersionStatus.RELEASED),
                )
            pins.map { it.pinnedVersion }.distinct() shouldBe listOf(3)
        }
    }

    @Test
    fun `a null version asks every pinned version - the scan for a template or a set as a whole`() {
        eachArrow { arrow ->
            seed(arrow, "dep/a", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)))
            seed(arrow, "dep/b", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 2)))

            arrow.ask(dependents, WORKSPACE, TARGET, null, PinScope.LIVE).map { it.name to it.pinnedVersion } shouldBe
                listOf("dep/a" to 1, "dep/b" to 2)
        }
    }

    @Test
    fun `ANY counts a DISCARDED version of a dependent that still lives - and nothing of one that does not`() {
        eachArrow { arrow ->
            // Its only pinning version is DISCARDED, but the dependent has a live version: a restore resurrects the pin (R12).
            seed(
                arrow,
                "dep/restorable",
                pointer = 2,
                Row(1, "DISCARDED", arrow.bodyPinning(TARGET, 1)),
                Row(2, "RELEASED", arrow.bodyPinning("something/else", 1)),
            )
            // Every version DISCARDED: the dependent is gone for every purpose, and so is its pin.
            seed(arrow, "dep/gone", pointer = null, Row(1, "DISCARDED", arrow.bodyPinning(TARGET, 1)))

            arrow.ask(dependents, WORKSPACE, TARGET, null, PinScope.ANY).map { it.name to it.status } shouldBe
                listOf("dep/restorable" to PipelineVersionStatus.DISCARDED)
            arrow.ask(dependents, WORKSPACE, TARGET, null, PinScope.LIVE).shouldBeEmpty()
        }
    }

    @Test
    fun `WORKING reads the draft when one exists and the pointer otherwise - a draft that dropped the pin is not counted`() {
        eachArrow { arrow ->
            // The pointer pins TARGET, the draft above it does not: the working version is the draft.
            seed(
                arrow,
                "dep/moved_on",
                pointer = 1,
                Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)),
                Row(2, "DRAFT", arrow.bodyPinning("something/else", 1)),
            )
            // The pointer pins it and no draft exists: the working version is the pointer.
            seed(arrow, "dep/steady", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)))
            // A draft that just ADOPTED the pin is counted even though the pointer does not have it.
            seed(
                arrow,
                "dep/adopting",
                pointer = 1,
                Row(1, "RELEASED", arrow.bodyPinning("something/else", 1)),
                Row(2, "DRAFT", arrow.bodyPinning(TARGET, 1)),
            )

            arrow.ask(dependents, WORKSPACE, TARGET, 1, PinScope.WORKING).map { it.name to it.version } shouldBe
                listOf("dep/adopting" to 2, "dep/steady" to 1)
        }
    }

    @Test
    fun `another workspace's same-named artifact never matches - the pinned name is per workspace`() {
        eachArrow { arrow ->
            seed(arrow, "dep/theirs", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)), workspace = OTHER_WORKSPACE)
            seed(arrow, "dep/mine", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)))

            PinScope.entries.forEach { scope ->
                arrow.ask(dependents, WORKSPACE, TARGET, null, scope).map { it.name } shouldBe listOf("dep/mine")
                arrow.ask(dependents, OTHER_WORKSPACE, TARGET, null, scope).map { it.name } shouldBe listOf("dep/theirs")
            }
        }
    }

    @Test
    fun `a dependent that pins the same target twice is one row - a dashboard with two sources on one release`() {
        val body =
            """{"sources": [{"name": "a", "pipeline": {"name": "$TARGET", "version": 1}},""" +
                """{"name": "b", "pipeline": {"name": "$TARGET", "version": 1}}]}"""
        seed(arrows[1], "dep/twice", pointer = 1, Row(1, "RELEASED", body))

        dependents.dashboardsPinningPipeline(WORKSPACE, TARGET, 1, PinScope.LIVE).map { it.name } shouldBe listOf("dep/twice")
    }

    @Test
    fun `the probe is a bound value - a name that is JSON or SQL matches nothing and breaks nothing`() {
        eachArrow { arrow ->
            seed(arrow, "dep/a", pointer = 1, Row(1, "RELEASED", arrow.bodyPinning(TARGET, 1)))

            listOf("""x"}, {"name": "$TARGET""", "'; DROP TABLE dashboards; --", "%", TARGET.uppercase()).forEach { hostile ->
                arrow.ask(dependents, WORKSPACE, hostile, null, PinScope.ANY).shouldBeEmpty()
            }
            arrow.ask(dependents, WORKSPACE, TARGET, null, PinScope.ANY).map { it.name } shouldBe listOf("dep/a")
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private fun eachArrow(case: (Arrow) -> Unit) =
        arrows.forEach { arrow ->
            reset()
            runCatching { case(arrow) }.onFailure { throw AssertionError("arrow '${arrow.label}': ${it.message}", it) }
        }

    /** One dependent artifact of [arrow]'s family with the given stored versions and index pointer. */
    private fun seed(
        arrow: Arrow,
        name: String,
        pointer: Int?,
        vararg rows: Row,
        workspace: UUID = WORKSPACE,
    ) {
        val kind = arrow.kind
        val id = UUID.randomUUID()
        val jdbc = VisualizationTestDb.jdbc.jdbcTemplate
        jdbc.update(
            "INSERT INTO ${kind.index} (id, workspace_id, name, display_name, current_version, created_by) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            workspace,
            name,
            name,
            pointer,
            AUTHOR,
        )
        rows.forEach { row ->
            val released = if (row.status == "DRAFT") "NULL, NULL" else "NOW(), '$AUTHOR'"
            val discarded = if (row.status == "DISCARDED") ", NOW(), '$AUTHOR'" else ", NULL, NULL"
            jdbc.update(
                "INSERT INTO ${kind.versions} (${kind.fk}, version, body_json, status, body_hash, released_at, released_by, " +
                    "discarded_at, discarded_by, created_by) VALUES (?, ?, ?::jsonb, ?, ?, $released$discarded, ?)",
                id,
                row.version,
                row.pins,
                row.status,
                "seeded-$name-${row.version}",
                AUTHOR,
            )
        }
    }

    private companion object {
        const val TARGET = "acme/target"
    }
}
