package co.datapipelines.web.datasources

import co.datapipelines.application.datasources.DatasourceCreateService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceMembershipRequiredException
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.auth.WorkspacesProperties
import co.datapipelines.datasources.Datasource
import co.datapipelines.datasources.DatasourceRegistry
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import java.util.stream.Stream

/**
 * **The D8 matrix, whole** (workspaces design §8/§6 last paragraph; the slice's required
 * proof): member vs admin × workspace-bound vs global × gate on/off × create/update/delete
 * × the `global`/`readonly` flag writes. Every cell's verdict is asserted — a row that is
 * missing here is a row shipped unverified, so the matrix below is deliberately exhaustive
 * over the axes the decision names.
 *
 * The binding-target axis ("bound to ANOTHER workspace") is the visibility 404 — covered by
 * the isolation E2E over the real repository, not here (a mocked registry would only prove
 * the mock).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceD8MatrixTest {
    private val registry = mockk<DatasourceRegistry>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val mapper = JsonMapper.builder().build()

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()

    private val bound =
        Datasource(
            name = "pg-prod",
            displayName = "Production Postgres",
            dialect = Dialect.POSTGRES,
            jdbcUrl = "jdbc:postgresql://db:5432/app",
            username = "readonly",
            password = null,
            workspaceId = workspaceId,
            workspaceName = "acme",
        )

    private val global = bound.copy(name = "shared", workspaceId = null, workspaceName = null)

    private fun controller(gate: Boolean): DatasourcesController {
        val rules = DatasourceWorkspaceRules(workspaceService, WorkspacesProperties(memberDatasourcesEnabled = gate))
        // 068: the create path is the shared service, built over the same rules instance the
        // assembled application wires — the D8 matrix must be exercised through what actually runs.
        return DatasourcesController(registry, rules, DatasourceCreateService(registry, rules::resolveCreateBinding))
    }

    private fun authenticate(admin: Boolean) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId,
                    "a@b.c",
                    "A",
                    if (admin) Scope.ADMIN.expand() else Scope.AUTHOR.expand(),
                    AuthMethod.OIDC,
                    workspace = WorkspaceContext(workspaceId, "acme"),
                ),
                null,
                emptyList(),
            )
    }

    private fun createBody(extra: String = ""): com.fasterxml.jackson.databind.JsonNode =
        mapper.readTree(
            """{"name":"pg-prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
                "username":"readonly","password":"pw"$extra}""",
        )

    private fun updateBody(extra: String = ""): com.fasterxml.jackson.databind.JsonNode =
        mapper.readTree("""{"dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app","username":"readonly"$extra}""")

    private fun stubTarget(
        name: String,
        datasource: Datasource?,
    ) {
        every { registry.getVisible(name, workspaceId) } returns datasource
    }

    // ------------------------------------------------------------ the matrix

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    fun `every D8 cell holds its verdict`(cell: Cell) {
        authenticate(cell.admin)
        if (cell.needsTarget) stubTarget(cell.targetName, cell.target)
        every { registry.save(any(), userId) } answers { firstArg() }
        every { registry.delete(any<String>()) } answers { co.datapipelines.datasources.DeleteResult(deleted = true, name = firstArg()) }
        every { registry.exists(any()) } returns false

        when (cell) {
            is Cell.Create -> controller(cell.gate).create(createBody(cell.extra))
            is Cell.Update -> controller(cell.gate).update(cell.targetName, updateBody(cell.extra))
            is Cell.Delete -> controller(cell.gate).delete(cell.targetName)
        }

        cell.expectedVerdict shouldBe Verdicts.ALLOWED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    fun `every refused D8 cell is workspace_forbidden 400`(cell: Cell) {
        authenticate(cell.admin)
        if (cell.needsTarget) stubTarget(cell.targetName, cell.target)
        every { registry.save(any(), userId) } answers { firstArg() }
        // A workspace the caller cannot access: the switch path's 403, which the rules
        // translate to the D8 400.
        every { workspaceService.resolveSwitch(any(), "rival") } throws WorkspaceMembershipRequiredException()

        val thrown =
            when (cell) {
                is Cell.Create -> shouldThrow<ApiException> { controller(cell.gate).create(createBody(cell.extra)) }
                is Cell.Update -> shouldThrow<ApiException> { controller(cell.gate).update(cell.targetName, updateBody(cell.extra)) }
                is Cell.Delete -> shouldThrow<ApiException> { controller(cell.gate).delete(cell.targetName) }
            }
        thrown.code shouldBe PipelineErrorCodes.Datasource.WORKSPACE_FORBIDDEN
    }

    // ------------------------------------------------------------ the table

    sealed interface Verdict

    enum class Verdicts : Verdict {
        ALLOWED,
        REFUSED_400,
    }

    sealed class Cell(
        val label: String,
        val admin: Boolean,
        val gate: Boolean,
        val targetName: String,
        val target: Datasource?,
        val extra: String,
        val expectedVerdict: Verdict,
    ) {
        val needsTarget get() = target !== NOT_NEEDED

        class Create(
            label: String,
            admin: Boolean,
            gate: Boolean,
            extra: String,
            verdict: Verdict,
        ) : Cell(label, admin, gate, "pg-prod", NOT_NEEDED, extra, verdict)

        class Update(
            label: String,
            admin: Boolean,
            gate: Boolean,
            target: Datasource,
            extra: String,
            verdict: Verdict,
        ) : Cell(label, admin, gate, target.name, target, extra, verdict)

        class Delete(
            label: String,
            admin: Boolean,
            gate: Boolean,
            target: Datasource,
            verdict: Verdict,
        ) : Cell(label, admin, gate, target.name, target, "", verdict)

        companion object {
            val NOT_NEEDED =
                Datasource(name = "\u0000not-needed", displayName = "", dialect = Dialect.POSTGRES, jdbcUrl = "", username = "")
        }
    }

    private fun c(
        label: String,
        admin: Boolean,
        gate: Boolean,
        extra: String,
        verdict: Verdict,
    ) = Cell.Create(label, admin, gate, extra, verdict)

    private fun u(
        label: String,
        admin: Boolean,
        gate: Boolean,
        target: Datasource,
        extra: String = "",
        verdict: Verdict,
    ) = Cell.Update(label, admin, gate, target, extra, verdict)

    private fun d(
        label: String,
        admin: Boolean,
        gate: Boolean,
        target: Datasource,
        verdict: Verdict,
    ) = Cell.Delete(label, admin, gate, target, verdict)

    /** The allowed half of the matrix — everything else must be refused (see [refusals]). */
    fun matrix(): Stream<Arguments> =
        Stream.of(
            *memberGateOnCells(),
            *adminCells(),
        )

    private fun memberGateOnCells(): Array<Arguments> =
        arrayOf(
            // gate ON, member, workspace-bound datasource
            Arguments.of(c("create bound, member, gate ON", admin = false, gate = true, extra = "", Verdicts.ALLOWED)),
            Arguments.of(
                c("create bound readonly, member, gate ON", admin = false, gate = true, extra = ",\"readonly\":true", Verdicts.ALLOWED),
            ),
            Arguments.of(u("update bound core, member, gate ON", admin = false, gate = true, target = bound, verdict = Verdicts.ALLOWED)),
            Arguments.of(
                u(
                    "update bound readonly flip, member, gate ON",
                    admin = false,
                    gate = true,
                    target = bound,
                    extra = ",\"readonly\":true",
                    verdict = Verdicts.ALLOWED,
                ),
            ),
            Arguments.of(d("delete bound, member, gate ON", admin = false, gate = true, target = bound, verdict = Verdicts.ALLOWED)),
        )

    private fun adminCells(): Array<Arguments> =
        arrayOf(
            // admin, any gate
            Arguments.of(c("create bound, admin", admin = true, gate = false, extra = "", Verdicts.ALLOWED)),
            Arguments.of(c("create global, admin", admin = true, gate = false, extra = ",\"global\":true", Verdicts.ALLOWED)),
            Arguments.of(
                c(
                    "create global readonly, admin",
                    admin = true,
                    gate = false,
                    extra = ",\"global\":true,\"readonly\":true",
                    verdict = Verdicts.ALLOWED,
                ),
            ),
            Arguments.of(u("update bound core, admin", admin = true, gate = false, target = bound, verdict = Verdicts.ALLOWED)),
            Arguments.of(u("update global core, admin", admin = true, gate = false, target = global, verdict = Verdicts.ALLOWED)),
            Arguments.of(
                u(
                    "update global readonly flip, admin",
                    admin = true,
                    gate = false,
                    target = global,
                    extra = ",\"readonly\":false",
                    verdict = Verdicts.ALLOWED,
                ),
            ),
            Arguments.of(
                u(
                    "flip bound to global, admin",
                    admin = true,
                    gate = false,
                    target = bound,
                    extra = ",\"global\":true",
                    verdict = Verdicts.ALLOWED,
                ),
            ),
            Arguments.of(
                u(
                    "flip global to bound, admin",
                    admin = true,
                    gate = false,
                    target = global,
                    extra = ",\"global\":false",
                    verdict = Verdicts.ALLOWED,
                ),
            ),
            Arguments.of(d("delete bound, admin", admin = true, gate = false, target = bound, verdict = Verdicts.ALLOWED)),
            Arguments.of(d("delete global, admin", admin = true, gate = false, target = global, verdict = Verdicts.ALLOWED)),
        )

    /** The refused half — every cell is `datasource.validation.workspace_forbidden` (400). */
    fun refusals(): Stream<Arguments> =
        Stream.of(
            // gate OFF: every member write, bound or not
            Arguments.of(c("create bound, member, gate OFF", admin = false, gate = false, extra = "", Verdicts.REFUSED_400)),
            Arguments.of(
                u("update bound core, member, gate OFF", admin = false, gate = false, target = bound, verdict = Verdicts.REFUSED_400),
            ),
            Arguments.of(d("delete bound, member, gate OFF", admin = false, gate = false, target = bound, verdict = Verdicts.REFUSED_400)),
            // member + global, any gate
            Arguments.of(c("create global, member", admin = false, gate = true, extra = ",\"global\":true", Verdicts.REFUSED_400)),
            Arguments.of(u("update global core, member", admin = false, gate = true, target = global, verdict = Verdicts.REFUSED_400)),
            Arguments.of(
                u(
                    "update global readonly flip, member",
                    admin = false,
                    gate = true,
                    target = global,
                    extra = ",\"readonly\":true",
                    Verdicts.REFUSED_400,
                ),
            ),
            Arguments.of(
                u(
                    "member sends global:true on bound",
                    admin = false,
                    gate = true,
                    target = bound,
                    extra = ",\"global\":true",
                    Verdicts.REFUSED_400,
                ),
            ),
            Arguments.of(
                u(
                    "member sends global:false on bound",
                    admin = false,
                    gate = true,
                    target = bound,
                    extra = ",\"global\":false",
                    Verdicts.REFUSED_400,
                ),
            ),
            Arguments.of(d("delete global, member", admin = false, gate = true, target = global, verdict = Verdicts.REFUSED_400)),
            // binding to an inaccessible workspace (any principal)
            Arguments.of(
                c("bind to rival workspace, member", admin = false, gate = true, extra = ",\"workspace\":\"rival\"", Verdicts.REFUSED_400),
            ),
            Arguments.of(
                c(
                    "global and workspace together, admin",
                    admin = true,
                    gate = true,
                    extra = ",\"global\":true,\"workspace\":\"rival\"",
                    Verdicts.REFUSED_400,
                ),
            ),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()
}
