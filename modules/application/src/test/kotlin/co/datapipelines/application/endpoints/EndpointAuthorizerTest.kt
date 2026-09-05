package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * The §5.2 authorisation rule, as a table.
 *
 * The property that needs the most protection is the REPLACE semantics of ruling R-EP2: the first
 * ancestor carrying any binding decides, and nodes above it are not consulted. An additive
 * implementation passes every "the right key works" test and fails only the one case that
 * matters — a key bound high in the tree still working inside a subtree an operator deliberately
 * scoped to a narrower key. `deeper binding hides the ancestor's key` is that case.
 *
 * The second is the unbound rule: an unbound path must NOT fall through to "any endpoint key".
 * If it did, publishing a new endpoint would silently widen every existing endpoint key's reach
 * at the moment of publication.
 */
class EndpointAuthorizerTest {
    private val authorizer = EndpointAuthorizer()

    @Test
    fun `the bound cases of section 5-2`() {
        val cases =
            listOf(
                Case(
                    "a bound endpoint key on its own subtree is allowed",
                    path = "/lending/home",
                    principal = endpointKey("dpk_A"),
                    bindings = listOf(binding("/lending", "dpk_A")),
                    expected = null,
                ),
                Case(
                    "an endpoint key bound elsewhere is refused where ANOTHER key decides",
                    path = "/trade/home",
                    principal = endpointKey("dpk_A"),
                    bindings = listOf(binding("/lending", "dpk_A"), binding("/trade", "dpk_B")),
                    expected = PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                ),
            )
        assertAll(cases.map { it.assertion() })
    }

    @Test
    fun `the unbound cases of section 5-2`() {
        val cases =
            listOf(
                Case(
                    // The subtree it is bound to is irrelevant: /trade has no bound ancestor at
                    // all, so this is the UNBOUND rule refusing an endpoint key, not the
                    // deciding-node rule. Both are 403; the codes say which rule spoke.
                    "an endpoint key on a subtree nobody bound is refused as unbound",
                    path = "/trade/home",
                    principal = endpointKey("dpk_A"),
                    bindings = listOf(binding("/lending", "dpk_A")),
                    expected = PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED,
                ),
                Case(
                    "a user key with execute is allowed where nothing is bound",
                    path = "/lending/home",
                    principal = userKey(Scope.EXECUTE),
                    bindings = emptyList(),
                    expected = null,
                ),
                Case(
                    "a user key with only read is refused on an unbound path",
                    path = "/lending/home",
                    principal = userKey(Scope.READ),
                    bindings = emptyList(),
                    expected = PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                ),
                Case(
                    "a user key of ANOTHER workspace is refused",
                    path = "/lending/home",
                    principal = userKey(Scope.EXECUTE, workspace = OTHER_WORKSPACE),
                    bindings = emptyList(),
                    expected = PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                ),
            )
        assertAll(cases.map { it.assertion() })
    }

    @Test
    fun `a binding at the root binds everything beneath it`() {
        assertAll(
            { codeOf(decide("/anything", endpointKey("dpk_A"), binding("/", "dpk_A"))) shouldBe null },
            { codeOf(decide("/deep/er/still", endpointKey("dpk_A"), binding("/", "dpk_A"))) shouldBe null },
            // And it decides for keys that are NOT bound there, rather than falling through.
            { codeOf(decide("/anything", endpointKey("dpk_B"), binding("/", "dpk_A"))) shouldBe PipelineErrorCodes.Endpoint.KEY_NOT_BOUND },
        )
    }

    @Test
    fun `a deeper binding hides the ancestor's key`() {
        // R-EP2's "replace, not add". An additive implementation allows dpk_A here, and passes
        // every other test in this file.
        val bindings = listOf(binding("/lending", "dpk_A"), binding("/lending/private", "dpk_B"))

        assertAll(
            // Outside the deeper node, the ancestor's key still works.
            { codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_A"), WORKSPACE, bindings)) shouldBe null },
            // Inside it, the ancestor's key does NOT.
            {
                codeOf(authorizer.authorize("/lending/private/x", endpointKey("dpk_A"), WORKSPACE, bindings)) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND
            },
            // And the deeper key does.
            { codeOf(authorizer.authorize("/lending/private/x", endpointKey("dpk_B"), WORKSPACE, bindings)) shouldBe null },
            // The deeper key does NOT leak upward either.
            {
                codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_B"), WORKSPACE, bindings)) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND
            },
        )
    }

    @Test
    fun `a binding on the exact leaf decides, not only folders`() {
        codeOf(decide("/lending/home", endpointKey("dpk_A"), binding("/lending/home", "dpk_A"))) shouldBe null
    }

    @Test
    fun `several keys may share one node`() {
        val bindings = listOf(binding("/lending", "dpk_A"), binding("/lending", "dpk_B"))
        assertAll(
            { codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_A"), WORKSPACE, bindings)) shouldBe null },
            { codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_B"), WORKSPACE, bindings)) shouldBe null },
        )
    }

    @Test
    fun `a USER key is subject to the bindings too — a bound path is not a user-key bypass`() {
        // A bound node decides for every credential, not only endpoint ones. Otherwise binding a
        // path would tighten it for machines and leave it open to every operator key.
        assertAll(
            {
                codeOf(decide("/lending/home", userKey(Scope.ADMIN), binding("/lending", "dpk_A"))) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND
            },
            { codeOf(decide("/lending/home", userKey(Scope.EXECUTE, keyId = "dpk_A"), binding("/lending", "dpk_A"))) shouldBe null },
        )
    }

    @Test
    fun `bindings on unrelated nodes never decide`() {
        // The walk selects by prefix rather than trusting the query, so extra rows are harmless.
        codeOf(decide("/lending/home", endpointKey("dpk_A"), binding("/trade", "dpk_Z"))) shouldBe
            PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
    }

    private fun decide(
        path: String,
        principal: AuthenticatedPrincipal,
        vararg bindings: EndpointKeyBinding,
    ) = authorizer.authorize(path, principal, WORKSPACE, bindings.toList())

    private fun codeOf(decision: EndpointAuthorizer.Decision): String? =
        when (decision) {
            is EndpointAuthorizer.Decision.Allowed -> null
            is EndpointAuthorizer.Decision.Refused -> decision.code
        }

    private fun endpointKey(
        keyId: String,
        workspace: UUID = WORKSPACE,
    ) = principal(keyId, ApiKeyKind.ENDPOINT, emptySet(), workspace)

    private fun userKey(
        scope: Scope,
        workspace: UUID = WORKSPACE,
        keyId: String = "dpk_USER",
    ) = principal(keyId, ApiKeyKind.USER, setOf(scope), workspace)

    private fun principal(
        keyId: String,
        kind: ApiKeyKind,
        scopes: Set<Scope>,
        workspace: UUID,
    ) = AuthenticatedPrincipal(
        userId = USER,
        email = "e2e@datapipelines.test",
        displayName = "E2E",
        scopes = scopes,
        authMethod = AuthMethod.API_KEY,
        keyId = keyId,
        workspaceName = "w",
        workspace = WorkspaceContext(workspace, "w"),
        keyKind = kind,
    )

    private fun binding(
        prefix: String,
        keyId: String,
    ) = EndpointKeyBinding(prefix, keyId, WORKSPACE, USER, Instant.EPOCH)

    private inner class Case(
        val name: String,
        val path: String,
        val principal: AuthenticatedPrincipal,
        val bindings: List<EndpointKeyBinding>,
        val expected: String?,
    ) {
        /** The case as an assertion, so a table runs through `assertAll` and reports every row. */
        fun assertion(): () -> Unit =
            {
                withClue(name) {
                    codeOf(authorizer.authorize(path, principal, WORKSPACE, bindings)) shouldBe expected
                }
            }
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val OTHER_WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-0000000000ff")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
