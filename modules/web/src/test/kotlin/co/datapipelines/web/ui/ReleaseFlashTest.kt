package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.TemplateRef
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * #407 — the release flash's one-shot hold/consume contract: the names are SERVER-derived
 * (the release POST's own list), keyed to the actor and the pipeline, expiring, and consumed
 * on first read — so a crafted `?ok=released_with_templates` renders the layout's generic
 * sentence and nothing else.
 */
class ReleaseFlashTest {
    private val flash = ReleaseFlash()
    private val pipelineId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val otherPipeline = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val actorId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
    }

    private fun actAs(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                AuthenticatedPrincipal(
                    userId = userId,
                    email = "probe@test",
                    displayName = "Probe",
                    authMethod = AuthMethod.OIDC,
                    workspace = WorkspaceContext(UUID.randomUUID(), "probe"),
                ),
                null,
                emptyList(),
            )
    }

    /** The GET of the pipeline page: `/pipelines/{id}?ok=released_with_templates`. */
    private fun workspaceGet(
        pathId: UUID,
        session: MockHttpSession,
    ): MockHttpServletRequest =
        MockHttpServletRequest("GET", "/pipelines/$pathId").apply {
            setParameter("ok", "released_with_templates")
            setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, mapOf("id" to pathId.toString()))
            session.let { setSession(it) }
        }

    @Test
    fun `the next matching GET renders the names - once`() {
        actAs(actorId)
        val session = MockHttpSession()
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/a.sql", 2), TemplateRef("test/b.sql", 3)))

        val message = flash.consumeMessage(workspaceGet(pipelineId, session), session)

        message shouldBe "Released. Also released: test/a.sql@2, test/b.sql@3."
        // Consumed: the SECOND look — a replayed or crafted URL — gets nothing.
        flash.consumeMessage(workspaceGet(pipelineId, session), session) shouldBe null
    }

    @Test
    fun `a crafted ok on another pipeline, another actor, or with nothing held renders no names`() {
        // Nothing held at all.
        val bare = MockHttpSession()
        actAs(actorId)
        flash.consumeMessage(workspaceGet(pipelineId, bare), bare) shouldBe null

        // Held for one pipeline, asked of another (the path var decides).
        val session = MockHttpSession()
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/a.sql", 2)))
        flash.consumeMessage(workspaceGet(otherPipeline, session), session) shouldBe null

        // Held for one actor, read by another — and the read still consumes.
        val otherActor = UUID.fromString("00000000-0000-0000-0000-000000000002")
        actAs(otherActor)
        flash.consumeMessage(workspaceGet(pipelineId, session), session) shouldBe null
    }

    @Test
    fun `a held entry past its minute renders no names`() {
        actAs(actorId)
        val session = MockHttpSession()
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/a.sql", 2)))
        val held = session.getAttribute(ReleaseFlash.SESSION_KEY) as ReleaseFlash.Held
        session.setAttribute(
            ReleaseFlash.SESSION_KEY,
            held.copy(atEpochMs = held.atEpochMs - ReleaseFlash.TTL_MS - 1),
        )

        flash.consumeMessage(workspaceGet(pipelineId, session), session) shouldBe null
    }

    @Test
    fun `a release that cascaded nothing holds nothing`() {
        val session = MockHttpSession()
        flash.hold(session, pipelineId, actorId, emptyList())

        session.getAttribute(ReleaseFlash.SESSION_KEY) shouldBe null
    }

    @Test
    fun `a second release replaces the held names`() {
        actAs(actorId)
        val session = MockHttpSession()
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/a.sql", 2)))
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/b.sql", 5)))

        val message = flash.consumeMessage(workspaceGet(pipelineId, session), session)

        message shouldBe "Released. Also released: test/b.sql@5."
    }

    @Test
    fun `the advice answers the ok code and nothing else - and consumes only on it`() {
        val session = MockHttpSession()
        val advice = ReleaseFlashAdvice(flash)
        flash.hold(session, pipelineId, actorId, listOf(TemplateRef("test/a.sql", 2)))
        actAs(actorId)

        // No code: null, and the entry survives for the real redirect.
        val noCode = MockHttpServletRequest("GET", "/pipelines/$pipelineId").apply { setSession(session) }
        advice.releasedFlashMessage(noCode) shouldBe null
        (session.getAttribute(ReleaseFlash.SESSION_KEY) as ReleaseFlash.Held).templates shouldBe listOf("test/a.sql@2")

        // A DIFFERENT code: null, entry still intact.
        val otherCode =
            MockHttpServletRequest("GET", "/pipelines/$pipelineId").apply {
                setParameter("ok", "released")
                setSession(session)
            }
        advice.releasedFlashMessage(otherCode) shouldBe null

        // The code, the pipeline, the actor: the names, consumed.
        val message = advice.releasedFlashMessage(workspaceGet(pipelineId, session))
        message shouldBe "Released. Also released: test/a.sql@2."
        session.getAttribute(ReleaseFlash.SESSION_KEY) shouldBe null
    }
}
