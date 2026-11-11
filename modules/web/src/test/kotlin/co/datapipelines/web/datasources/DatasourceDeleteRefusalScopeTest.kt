package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.application.datasources.DatasourceUpdateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceReference
import co.datapipelines.datasources.DatasourceReferences
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.datasources.DeleteResult
import co.datapipelines.datasources.ParameterSetDatasourceReference
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.ui.DatasourceBrowseModel
import co.datapipelines.web.ui.DatasourcePartialController
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import java.util.UUID

/**
 * #873 §F.3 e — the delete refusal's details are scoped to the caller's workspaces, on both surfaces that show them:
 * the REST `409 datasource.in_use` and the UI's delete dialog (with its confirmed POST).
 *
 * The scenario is the one the scope exists for: a workspace admin of A deletes a datasource OWNED by A and GRANTED to B.
 * The lock still counts every workspace — so the refusal fires in every case — and the caller is told only about the
 * references held in A. A super admin is told about all of them. References outside the caller's scope contribute no
 * name, version, count or workspace: two different outside populations give byte-identical bodies.
 */
class DatasourceDeleteRefusalScopeTest {
    private val wsA = UUID(0xA, 1)
    private val wsB = UUID(0xB, 1)
    private val wsC = UUID(0xC, 1)

    private val registry = mockk<DatasourceRegistry>()

    // A REAL rule set with workspace-admin datasource management on — the D8 gates admit a workspace admin of A to
    // delete A's own datasource, which is the path whose refusal this class reads.
    private val rules = DatasourceWorkspaceRules(mockk(relaxed = true), WorkspacesProperties(memberDatasourcesEnabled = true))

    private val rest =
        DatasourcesController(
            registry,
            rules,
            DatasourceCreateService(registry, rules::resolveCreateBinding, mockk(relaxed = true)),
            DatasourceUpdateService(registry, rules),
            co.datapipelines.web.EVERYTHING_LENS,
        )

    /** The scan the dialog asks; swapped per case, forwarded through both arms. */
    private var scan: Pair<List<DatasourceReference>, List<ParameterSetDatasourceReference>> =
        emptyList<DatasourceReference>() to emptyList()

    private val ui =
        DatasourcePartialController(
            DatasourceBrowseModel(registry),
            registry,
            rules,
            DatasourceUpdateService(registry, rules),
            mockk(relaxed = true),
            object : DatasourceReferences {
                override fun referencesTo(datasourceName: String) = scan.first

                override fun parameterSetReferencesTo(datasourceName: String) = scan.second
            },
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // ------------------------------------------------------------------ REST

    @Test
    fun `references only in B - refused, and the details name nothing`() {
        actAsAdminOfA()

        val refusal = restRefusal(pipelines = listOf(pipeline("globex/report", wsB)), sets = listOf(set("globex/filters", wsB)))

        refusal.code shouldBe PipelineErrorCodes.Datasource.IN_USE
        refusal.message shouldBe "Datasource 'shared' is in use and cannot be deleted."
        refusal.details shouldBe
            mapOf(
                "datasource_name" to "shared",
                "referencing_pipelines" to emptyList<String>(),
                "referencing_parameter_sets" to emptyList<String>(),
                "references" to emptyList<Map<String, Any?>>(),
            )
    }

    @Test
    fun `references in A and B - refused, and only A's are named`() {
        actAsAdminOfA()

        val refusal =
            restRefusal(
                pipelines = listOf(pipeline("acme/report", wsA), pipeline("globex/report", wsB)),
                sets = listOf(set("globex/filters", wsB), set("acme/filters", wsA)),
            )

        refusal.code shouldBe PipelineErrorCodes.Datasource.IN_USE
        refusal.message shouldBe ONE_AND_ONE_MESSAGE
        refusal.details shouldBe ONLY_A_DETAILS
    }

    @Test
    fun `references only in A - the same body as A and B together`() {
        actAsAdminOfA()

        val onlyA = restRefusal(pipelines = listOf(pipeline("acme/report", wsA)), sets = listOf(set("acme/filters", wsA)))
        val both =
            restRefusal(
                pipelines = listOf(pipeline("acme/report", wsA), pipeline("globex/report", wsB)),
                sets = listOf(set("acme/filters", wsA), set("globex/filters", wsB)),
            )

        onlyA.code shouldBe PipelineErrorCodes.Datasource.IN_USE
        onlyA.message shouldBe ONE_AND_ONE_MESSAGE
        onlyA.details shouldBe ONLY_A_DETAILS
        body(onlyA) shouldBe body(both)
    }

    @Test
    fun `two different outside populations give identical bodies`() {
        actAsAdminOfA()

        val few = restRefusal(pipelines = listOf(pipeline("globex/report", wsB)), sets = emptyList())
        val many =
            restRefusal(
                pipelines = listOf(pipeline("globex/report", wsB), pipeline("globex/daily", wsB), pipeline("initech/x", wsC)),
                sets = listOf(set("globex/filters", wsB), set("initech/filters", wsC)),
            )

        few.code shouldBe PipelineErrorCodes.Datasource.IN_USE
        body(few) shouldBe body(many)
    }

    @Test
    fun `a super admin is told about every workspace's references`() {
        actAs(superAdmin = true)

        val refusal =
            restRefusal(
                pipelines = listOf(pipeline("acme/report", wsA), pipeline("globex/report", wsB)),
                sets = listOf(set("acme/filters", wsA), set("globex/filters", wsB)),
            )

        refusal.message shouldBe
            "Datasource 'shared' is referenced by 2 pipeline(s) across 2 node(s) and 2 parameter set(s) across 2 selector(s)," +
            " including historical versions. Remove or repoint the referencing nodes and selectors before deleting it."
        refusal.details["referencing_pipelines"] shouldBe listOf("acme/report", "globex/report")
        refusal.details["referencing_parameter_sets"] shouldBe listOf("acme/filters", "globex/filters")
        (refusal.details["references"] as List<*>).size shouldBe 4
    }

    // ------------------------------------------------------------------ UI dialog + confirmed POST

    @Test
    fun `the dialog refuses a datasource used only in B and lists nothing`() {
        actAsAdminOfA()
        scan = listOf(pipeline("globex/report", wsB)) to listOf(set("globex/filters", wsB))

        val model = dialog()

        model["inUse"] shouldBe true
        model["usages"] shouldBe emptyList<DatasourceReference>()
        model["usedByPipelines"] shouldBe emptyList<String>()
        model["setUsages"] shouldBe emptyList<ParameterSetDatasourceReference>()
        model["usedBySets"] shouldBe emptyList<String>()
    }

    @Test
    fun `the dialog lists only A's rows when A and B both reference it, and equals the only-A dialog`() {
        actAsAdminOfA()
        scan =
            listOf(pipeline("acme/report", wsA), pipeline("globex/report", wsB)) to
            listOf(set("globex/filters", wsB), set("acme/filters", wsA))
        val both = dialogUsages()
        scan = listOf(pipeline("acme/report", wsA)) to listOf(set("acme/filters", wsA))
        val onlyA = dialogUsages()

        both shouldBe onlyA
        both["usedByPipelines"] shouldBe listOf("acme/report")
        both["usedBySets"] shouldBe listOf("acme/filters")
        both["inUse"] shouldBe true
    }

    @Test
    fun `two different outside populations give identical dialogs`() {
        actAsAdminOfA()
        scan = listOf(pipeline("globex/report", wsB)) to emptyList()
        val few = dialogUsages()
        scan = listOf(pipeline("globex/report", wsB), pipeline("initech/x", wsC)) to listOf(set("globex/filters", wsB))
        val many = dialogUsages()

        few shouldBe many
        few["inUse"] shouldBe true
    }

    @Test
    fun `a super admin's dialog lists every workspace's rows`() {
        actAs(superAdmin = true)
        scan = listOf(pipeline("acme/report", wsA), pipeline("globex/report", wsB)) to listOf(set("globex/filters", wsB))

        val model = dialog()

        model["usedByPipelines"] shouldBe listOf("acme/report", "globex/report")
        model["usedBySets"] shouldBe listOf("globex/filters")
    }

    @Test
    fun `the confirmed POST is refused with a message built from the caller's scope alone`() {
        actAsAdminOfA()
        every { registry.getVisible("shared", wsA) } returns owned()

        every { registry.delete("shared") } returns inUse(listOf(pipeline("globex/report", wsB)), listOf(set("globex/filters", wsB)))
        refusalMessage() shouldBe "'shared' is in use and cannot be deleted."

        every { registry.delete("shared") } returns
            inUse(listOf(pipeline("globex/report", wsB), pipeline("acme/report", wsA)), listOf(set("initech/filters", wsC)))
        refusalMessage() shouldBe
            "'shared' is still used by 1 pipeline(s) across 1 node(s) and 0 parameter set(s) across 0 selector(s)." +
            " Remove or repoint them first."
    }

    // ------------------------------------------------------------------ fixtures

    private fun restRefusal(
        pipelines: List<DatasourceReference>,
        sets: List<ParameterSetDatasourceReference>,
    ): ApiException {
        every { registry.getVisible("shared", wsA) } returns owned()
        every { registry.delete("shared") } returns inUse(pipelines, sets)
        return shouldThrow<ApiException> { rest.delete("shared") }
    }

    private fun dialog(): ExtendedModelMap {
        every { registry.getVisible("shared", wsA) } returns owned()
        val model = ExtendedModelMap()
        ui.deleteDialog(model, "shared") shouldBe "partials/datasource-delete"
        model["forbidden"] shouldBe null
        return model
    }

    /** The dialog's usage attributes only — the role stamps are not the scope's concern. */
    private fun dialogUsages(): Map<String, Any?> =
        dialog().filterKeys { it in setOf("inUse", "usages", "usedByPipelines", "setUsages", "usedBySets") }

    private fun refusalMessage(): String = (ui.delete(ExtendedModelMap(), "shared") as ModelAndView).model["message"] as String

    /** What the wire carries: code, message and details. */
    private fun body(e: ApiException) = Triple(e.code, e.message, e.details)

    private fun inUse(
        pipelines: List<DatasourceReference>,
        sets: List<ParameterSetDatasourceReference>,
    ) = DeleteResult(
        deleted = false,
        name = "shared",
        errorCode = PipelineErrorCodes.Datasource.IN_USE,
        references = pipelines,
        parameterSetReferences = sets,
    )

    private fun pipeline(
        name: String,
        workspace: UUID,
    ) = DatasourceReference(name, 1, "RELEASED", "extract", workspace)

    private fun set(
        name: String,
        workspace: UUID,
    ) = ParameterSetDatasourceReference(name, 2, "DISCARDED", "parameters[0].source.datasource", workspace)

    /** Owned by A (and, in the scenario, granted to B — the grant is what lets B's bodies name it). */
    private fun owned() =
        Datasource(
            name = "shared",
            displayName = "Shared",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "u",
            secret = null,
            ownerWorkspaceId = wsA,
            workspaceName = "acme",
        )

    private fun actAsAdminOfA() = actAs(superAdmin = false)

    private fun actAs(superAdmin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    UUID.randomUUID(),
                    "a@b.c",
                    "A",
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(wsA, "acme", WorkspaceRole.WORKSPACE_ADMIN),
                    superAdmin = superAdmin,
                ),
                null,
                emptyList(),
            )
    }

    private companion object {
        const val ONE_AND_ONE_MESSAGE =
            "Datasource 'shared' is referenced by 1 pipeline(s) across 1 node(s) and 1 parameter set(s) across 1 selector(s)," +
                " including historical versions. Remove or repoint the referencing nodes and selectors before deleting it."

        val ONLY_A_DETAILS =
            mapOf(
                "datasource_name" to "shared",
                "referencing_pipelines" to listOf("acme/report"),
                "referencing_parameter_sets" to listOf("acme/filters"),
                "references" to
                    listOf(
                        mapOf(
                            "kind" to "pipeline",
                            "pipeline" to "acme/report",
                            "node_id" to "extract",
                            "pipeline_version" to 1,
                            "version_status" to "RELEASED",
                        ),
                        mapOf(
                            "kind" to "parameter_set",
                            "parameter_set" to "acme/filters",
                            "parameter_set_version" to 2,
                            "version_status" to "DISCARDED",
                            "path" to "parameters[0].source.datasource",
                        ),
                    ),
            )
    }
}
