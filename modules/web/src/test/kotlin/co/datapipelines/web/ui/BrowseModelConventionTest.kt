package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.reflect.KClass

/**
 * **The BrowseModel convention, drift-tested** (ui-screens.md §5, 097 §B).
 *
 * The rule: a list screen is ONE `<Entity>BrowseModel` plus a page controller plus a partial
 * controller. The page controller renders the shell AND the initial fragment through the
 * model; the partial controller renders every later fragment through the SAME model; neither
 * filters, pages or projects on its own.
 *
 * It is a rule because the alternative was tried. Datasources had the two controllers and no
 * model: each grew its own `filter()`, they diverged, and `GET /datasources?q=postgres` came
 * back empty while the same word typed into the same box returned rows (097 §A). Executions
 * had a page controller that projected nothing at all and fetched its rows on a load trigger.
 * Admin users had no projection layer in either direction — the rows were built as strings in
 * Kotlin (097 §C).
 *
 * The pairs are listed explicitly rather than derived, so the pairing itself is reviewable —
 * `AdminUsersController` and `ExecutionHistoryController` are page controllers that do not
 * carry the `Ui` infix, and no naming rule would find them without guessing. What IS derived
 * is COMPLETENESS: every `*PartialController` in the package must appear below either as half
 * of a pair or in [EXEMPT] with a reason, so a new one cannot quietly invent a third idiom.
 */
class BrowseModelConventionTest {
    /** One list screen: the page, the fragment, and the model they must share. */
    private data class Pair(
        val page: KClass<*>,
        val partial: KClass<*>,
        val model: KClass<*>,
    )

    private val pairs =
        listOf(
            Pair(PipelineUiController::class, PipelinePartialController::class, PipelineBrowseModel::class),
            Pair(TemplateUiController::class, TemplatePartialController::class, TemplateBrowseModel::class),
            Pair(DatasourceUiController::class, DatasourcePartialController::class, DatasourceBrowseModel::class),
            Pair(AdminUsersController::class, AdminUsersPartialController::class, AdminUsersBrowseModel::class),
            Pair(
                ExecutionHistoryController::class,
                ExecutionHistoryPartialController::class,
                ExecutionHistoryBrowseModel::class,
            ),
        )

    private fun injects(
        type: KClass<*>,
        model: KClass<*>,
    ): Boolean = type.constructors.any { ctor -> ctor.parameters.any { it.type.classifier == model } }

    @Test
    fun `both halves of every list screen inject the same BrowseModel`() {
        // Non-vacuity: the rule is worth nothing if the list quietly empties.
        pairs.size shouldBeGreaterThanOrEqual 3

        val violations =
            pairs.flatMap { pair ->
                listOf(pair.page, pair.partial)
                    .filterNot { injects(it, pair.model) }
                    .map { "${it.simpleName} does not inject ${pair.model.simpleName}" }
            }
        violations.shouldBeEmpty()
    }

    /**
     * The predicate has to be able to say NO, or the arm above passes by construction — the
     * "could this check have failed?" question, asked of the check itself rather than of the
     * code (a guard that cannot go red is not a guard).
     */
    @Test
    fun `the injection check can fail - it is not a tautology`() {
        injects(DatasourceUiController::class, DatasourceBrowseModel::class) shouldBe true
        injects(DatasourceUiController::class, PipelineBrowseModel::class) shouldBe false
    }

    @Test
    fun `every partial controller is either half of a pair or an exemption with a reason`() {
        val declared = pairs.map { it.partial.simpleName }.toSet() + EXEMPT.keys
        val onDisk =
            File("src/main/kotlin/co/datapipelines/web/ui")
                .listFiles { f -> f.name.endsWith("PartialController.kt") }
                .orEmpty()
                .map { it.name.removeSuffix(".kt") }

        // Non-vacuity again: the directory listing must have found the files at all.
        onDisk.size shouldBeGreaterThanOrEqual 5
        withClue("a new *PartialController must join a pair or be exempted with a reason") {
            (onDisk.toSet() - declared) shouldBe emptySet()
        }
        // And nothing may be exempted that no longer exists — a stale exemption is a rule
        // nobody is following any more.
        (declared - onDisk.toSet() - pairs.map { it.partial.simpleName }.toSet()) shouldBe emptySet()
    }

    private companion object {
        /**
         * The `/partials` controllers that are NOT list screens, each with the reason it is
         * not one. An entry here is a decision, not an omission.
         */
        val EXEMPT =
            mapOf(
                "DashboardPartialController" to
                    "the dashboard is the ONE deliberate skeleton-first screen (§5): four " +
                    "independent panels load in parallel, and there is no single list to project",
                "ExecutionDetailPartialController" to
                    "a DETAIL screen's panels (nodes, result cursor), not a list",
                "PipelineNodeSqlPartialController" to
                    "the pipeline editor's one htmx seam (§2.1) — an SPA island's fragment, no page twin",
                "ApiKeysPartialController" to
                    "its page twin is ApiConsoleController (§4.18), and the projection they share " +
                    "is ApiKeyRows — the same rule under the name 091 gave it",
            )
    }
}
