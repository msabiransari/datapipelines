package co.datapipelines.application.endpoints

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.AuthoringGuard
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import java.util.UUID

/**
 * #1111 — the namespace-writes half of a mutation grant, as a table ([EndpointAuthorizer.allowsNamespaceWrites]), and
 * its composition with the exact grant and the request-time posture ([EndpointGrants]).
 *
 * The property most worth protecting is the SHADOW rule: the deciding node is the nearest one carrying ANY key's GET
 * row, so an ordinary nearer binding — another key's or the marked key's own — turns the upper opt-in off for that
 * subtree, and a marked row of another key at the same node is never borrowed. An implementation that looked for "any
 * marked ancestor of this key" passes every happy-path case and fails exactly those.
 */
class EndpointNamespaceWritesTest {
    private val authorizer = EndpointAuthorizer()

    @Test
    fun `the deciding node is the nearest node carrying any GET binding, and only the presenting key's mark counts`() {
        val cases =
            listOf(
                Triple("a marked node admits a route published beneath it later", listOf(marked("/school/v1", KEY)), true),
                Triple("the marked node may be the root of the key's workspace", listOf(marked("/", KEY)), true),
                Triple("an unmarked binding of the key is not a namespace grant", listOf(plain("/school/v1", KEY)), false),
                Triple("no binding on any ancestor admits nothing", emptyList(), false),
                Triple(
                    "another key's ORDINARY nearer binding shadows the upper opt-in",
                    listOf(marked("/school/v1", KEY), plain("/school/v1/orders", OTHER)),
                    false,
                ),
                Triple(
                    "the key's OWN ordinary nearer binding shadows its upper opt-in",
                    listOf(marked("/school/v1", KEY), plain("/school/v1/orders", KEY)),
                    false,
                ),
                Triple(
                    "a deeper explicit opt-in admits where the upper one is shadowed",
                    listOf(plain("/school/v1", OTHER), marked("/school/v1/orders", KEY)),
                    true,
                ),
                Triple(
                    "another key's marked row at the same node is never borrowed",
                    listOf(plain("/school/v1", KEY), marked("/school/v1", OTHER)),
                    false,
                ),
                Triple(
                    "an ordinary key at the same node does not shadow the marked one",
                    listOf(marked("/school/v1", KEY), plain("/school/v1", OTHER)),
                    true,
                ),
                Triple(
                    "a sibling namespace with a similar string prefix is not an ancestor",
                    listOf(marked("/school/v1-beta", KEY)),
                    false,
                ),
                Triple(
                    "a foreign workspace's nearer row neither decides nor shadows",
                    listOf(marked("/school/v1", KEY), plain("/school/v1/orders", OTHER, workspace = FOREIGN)),
                    true,
                ),
                Triple(
                    "a foreign workspace's marked row grants nothing here",
                    listOf(marked("/school/v1", KEY, workspace = FOREIGN)),
                    false,
                ),
                Triple(
                    "an exact mutation grant on the path never decides the node",
                    listOf(marked("/school/v1", KEY), exact("/school/v1/orders", OTHER)),
                    true,
                ),
            )
        assertAll(
            cases.map { (name, rows, expected) ->
                {
                    withClue(name) {
                        authorizer.allowsNamespaceWrites("/school/v1/orders", KEY, WORKSPACE, rows) shouldBe expected
                    }
                }
            },
        )
    }

    @Test
    fun `a parameterized child is decided by its concrete path`() {
        val rows = listOf(marked("/school/v1", KEY), plain("/school/v1/pupils/ann", OTHER))
        authorizer.allowsNamespaceWrites("/school/v1/pupils/bob", KEY, WORKSPACE, rows) shouldBe true
        authorizer.allowsNamespaceWrites("/school/v1/pupils/ann", KEY, WORKSPACE, rows) shouldBe false
    }

    @Test
    fun `EndpointGrants admits the union - exact grants on any posture, the namespace grant only while authoring is on`() {
        val route = UUID.randomUUID()
        val other = UUID.randomUUID()
        val repository = repository(marked = listOf(marked("/school/v1", KEY)), exact = listOf(exact("/school/v1/orders", KEY, route)))
        val on = EndpointGrants(repository, authorizer, AuthoringGuard(true))
        val off = EndpointGrants(repository, authorizer, AuthoringGuard(false))
        assertAll(
            { withClue("exact, authoring on") { on.at(PATH, key(), WORKSPACE).mayMutate(route, EndpointMethod.POST) shouldBe true } },
            { withClue("exact, authoring off") { off.at(PATH, key(), WORKSPACE).mayMutate(route, EndpointMethod.POST) shouldBe true } },
            { withClue("exact names its method") { off.at(PATH, key(), WORKSPACE).mayMutate(route, EndpointMethod.PUT) shouldBe false } },
            { withClue("namespace, authoring on") { on.at(PATH, key(), WORKSPACE).mayMutate(other, EndpointMethod.PUT) shouldBe true } },
            { withClue("namespace, authoring off") { off.at(PATH, key(), WORKSPACE).mayMutate(other, EndpointMethod.PUT) shouldBe false } },
            {
                withClue("namespace needs the key's OWN workspace to be the route's") {
                    on.at(PATH, key(workspace = FOREIGN), WORKSPACE).mayMutate(other, EndpointMethod.PUT) shouldBe false
                }
            },
        )
    }

    @Test
    fun `one snapshot reads each binding query at most once however many methods it answers`() {
        val repository = repository(marked = listOf(marked("/school/v1", KEY)), exact = emptyList())
        val snapshot = EndpointGrants(repository, authorizer, AuthoringGuard(true)).at(PATH, key(), WORKSPACE)
        snapshot.read()
        EndpointMethod.entries.filter { it.isMutation }.forEach { snapshot.mayMutate(UUID.randomUUID(), it) }
        verify(exactly = 1) { repository.findByPrefixes(any(), WORKSPACE) }
        verify(exactly = 1) { repository.findMutationGrantsByKey(KEY, WORKSPACE) }
    }

    private fun repository(
        marked: List<EndpointKeyBinding>,
        exact: List<EndpointKeyBinding>,
    ): EndpointKeyBindingRepository =
        mockk {
            every { findByPrefixes(any(), WORKSPACE) } returns marked
            every { findMutationGrantsByKey(KEY, WORKSPACE) } returns exact
        }

    private fun key(workspace: UUID = WORKSPACE) =
        AuthenticatedPrincipal(
            userId = USER,
            email = "e2e@datapipelines.test",
            displayName = "E2E",
            authMethod = AuthMethod.API_KEY,
            keyId = KEY,
            workspaceName = "w",
            workspace = WorkspaceContext(workspace, "w"),
            keyKind = ApiKeyKind.ENDPOINT,
        )

    private fun plain(
        prefix: String,
        keyId: String,
        workspace: UUID = WORKSPACE,
    ) = EndpointKeyBinding(prefix, keyId, workspace, USER, Instant.EPOCH)

    private fun marked(
        prefix: String,
        keyId: String,
        workspace: UUID = WORKSPACE,
    ) = plain(prefix, keyId, workspace).copy(allowNamespaceWrites = true)

    private fun exact(
        path: String,
        keyId: String,
        endpointId: UUID = UUID.randomUUID(),
    ) = EndpointKeyBinding(path, keyId, WORKSPACE, USER, Instant.EPOCH, EndpointMethod.POST, endpointId)

    private companion object {
        const val KEY = "dpk_DEV"
        const val OTHER = "dpk_OTHER"
        const val PATH = "/school/v1/orders"
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        val FOREIGN: UUID = UUID.fromString("defa0000-0000-0000-0000-0000000000ff")
        val USER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
