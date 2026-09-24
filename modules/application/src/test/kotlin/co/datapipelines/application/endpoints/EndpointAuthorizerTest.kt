package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
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
                    // #215 B3: until slice (b) a `user` key of the endpoint's own workspace holding
                    // `execute` was ADMITTED here. That branch went with the scopes: an unbound
                    // published path serves no one until a key is bound to it.
                    "an unbound path serves no one - a user key of the endpoint's own workspace is refused",
                    path = "/lending/home",
                    principal = userKey(),
                    bindings = emptyList(),
                    expected = PipelineErrorCodes.Endpoint.KEY_NOT_BOUND,
                ),
                Case(
                    "a user key of ANOTHER workspace is refused",
                    path = "/lending/home",
                    principal = userKey(workspace = OTHER_WORKSPACE),
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
                codeOf(decide("/lending/home", userKey(), binding("/lending", "dpk_A"))) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_NOT_BOUND
            },
            { codeOf(decide("/lending/home", userKey(keyId = "dpk_A"), binding("/lending", "dpk_A"))) shouldBe null },
        )
    }

    @Test
    fun `bindings on unrelated nodes never decide`() {
        // The walk selects by prefix rather than trusting the query, so extra rows are harmless.
        codeOf(decide("/lending/home", endpointKey("dpk_A"), binding("/trade", "dpk_Z"))) shouldBe
            PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
    }

    @Test
    fun `a binding of ANOTHER workspace is invisible - it neither decides nor shadows (#191)`() {
        // The exploit shape #191 closes: workspace B publishes /lending/**; A's key is bound at
        // /lending in A's workspace. Before the fix the nearer foreign node decided and the
        // presented-key comparison was workspace-blind, so A's key served B's endpoint. Now the
        // foreign row is not even consulted: the walk continues as if /lending carried nothing.
        val foreign =
            EndpointKeyBinding("/lending", "dpk_FOREIGNKEY", OTHER_WORKSPACE, USER, Instant.EPOCH)
        val own = binding("/", "dpk_A")

        assertAll(
            // The foreign binding does not let its own key in either...
            {
                codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_FOREIGNKEY"), WORKSPACE, listOf(foreign))) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
            },
            // ...does not keep a same-workspace key out of its own subtree (no shadowing)...
            {
                codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_A"), WORKSPACE, listOf(foreign, own))) shouldBe null
            },
            // ...and leaves the unbound rule exactly where it was for this workspace.
            {
                codeOf(authorizer.authorize("/lending/home", endpointKey("dpk_A"), WORKSPACE, listOf(foreign))) shouldBe
                    PipelineErrorCodes.Endpoint.KEY_KIND_REFUSED
            },
            // ...and, since #215 B3, an unbound path (for THIS workspace) serves no key at all.
            {
                codeOf(
                    authorizer.authorize("/lending/home", userKey(), WORKSPACE, listOf(foreign)),
                ) shouldBe PipelineErrorCodes.Endpoint.KEY_NOT_BOUND
            },
        )
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
    ) = principal(keyId, ApiKeyKind.ENDPOINT, workspace)

    private fun userKey(
        workspace: UUID = WORKSPACE,
        keyId: String = "dpk_USER",
    ) = principal(keyId, ApiKeyKind.USER, workspace)

    private fun principal(
        keyId: String,
        kind: ApiKeyKind,
        workspace: UUID,
    ) = AuthenticatedPrincipal(
        userId = USER,
        email = "e2e@datapipelines.test",
        displayName = "E2E",
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
