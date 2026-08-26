package co.datapipelines.web.ui

import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Scope
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineRecord
import co.datapipelines.pipeline.PipelineRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.ui.ExtendedModelMap
import java.util.UUID

class PipelineUiControllerTest {
    private val repository = mockk<PipelineRepository>()
    private val themeResolver = mockk<ThemeResolver>()
    private val controller = PipelineUiController(repository, themeResolver)

    private val userId = UUID.randomUUID()
    private val workspaceId = UUID.randomUUID()
    private val pageSize = 25

    private fun pipeline(name: String = "my-pipeline") =
        PipelineRecord(
            id = UUID.randomUUID(),
            name = name,
            displayName = "My Pipeline",
            description = "A test pipeline",
            ownerId = userId,
            currentVersion = 1,
            isDeleted = false,
            createdAt = java.time.Instant.parse("2026-08-01T00:00:00Z"),
            updatedAt = java.time.Instant.parse("2026-08-10T00:00:00Z"),
        )

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                userId,
                "a@b.c",
                "A",
                setOf(Scope.AUTHOR),
                AuthMethod.OIDC,
                workspace = WorkspaceContext(workspaceId, "acme"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @Test
    fun `list page returns pipelines view with theme and pipelines`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.findAll(any(), null, pageSize + 1, 0) } returns listOf(pipeline(), pipeline("other"))

        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = controller.list(model, mockk(), null, null)

        viewName shouldBe "pipelines/list"
        model["activeTheme"] shouldBe "saas"
        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 2
        model["total"] shouldBe 2
        model["hasMore"] shouldBe false
        model["offset"] shouldBe 0
    }

    @Test
    fun `list page filters by search query`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.findAll(any()) } returns
            listOf(
                pipeline("alpha"),
                pipeline("beta"),
                pipeline("gamma"),
            )

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), "beta", null)

        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 1
        result[0].name shouldBe "beta"
        model["total"] shouldBe 1
    }

    @Test
    fun `list page search filter matches displayName and description`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        val now = java.time.Instant.now()
        val record1 =
            PipelineRecord(
                UUID.randomUUID(),
                "p1",
                "Alpha Bravo",
                "desc one",
                userId,
                1,
                false,
                now,
                now,
            )
        val record2 =
            PipelineRecord(
                UUID.randomUUID(),
                "p2",
                "Charlie Delta",
                "contains BRAVO in desc",
                userId,
                1,
                false,
                now,
                now,
            )
        val record3 =
            PipelineRecord(
                UUID.randomUUID(),
                "p3",
                "Echo",
                "nothing",
                userId,
                1,
                false,
                now,
                now,
            )
        every { repository.findAll(any()) } returns listOf(record1, record2, record3)

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), "bravo", null)

        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 2
    }

    @Test
    fun `partial returns fragment view with correct model`() {
        authenticate()
        every { repository.findAll(any(), null, pageSize + 1, 0) } returns listOf(pipeline(), pipeline(), pipeline())

        val partialController = PipelinePartialController(repository)
        val model: ExtendedModelMap = ExtendedModelMap()
        val viewName = partialController.list(model, null, null)

        viewName shouldBe "partials/pipelines"
        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 3
    }

    @Test
    fun `partial paginates correctly`() {
        authenticate()
        every { repository.findAll(any(), null, pageSize + 1, 25) } returns (1..5).map { pipeline("p${it + 25}") }

        val partialController = PipelinePartialController(repository)
        val model: ExtendedModelMap = ExtendedModelMap()
        partialController.list(model, null, 25)

        @Suppress("UNCHECKED_CAST")
        val result = model["pipelines"] as List<PipelineRecord>
        result shouldHaveSize 5
        model["offset"] shouldBe 25
        model["hasMore"] shouldBe false
    }

    @Test
    fun `scopes are populated from authenticated principal`() {
        authenticate()

        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.findAll(any(), null, pageSize + 1, 0) } returns emptyList()

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        @Suppress("UNCHECKED_CAST")
        val scopes = model["scopes"] as Set<String>
        scopes shouldBe setOf("AUTHOR")
    }

    @Test
    fun `empty list renders with hasMore false`() {
        authenticate()
        every { themeResolver.resolve(any()) } returns "saas"
        every { repository.findAll(any(), null, pageSize + 1, 0) } returns emptyList()

        val model: ExtendedModelMap = ExtendedModelMap()
        controller.list(model, mockk(), null, null)

        model["total"] shouldBe 0
        model["hasMore"] shouldBe false
        @Suppress("UNCHECKED_CAST")
        (model["pipelines"] as List<*>) shouldHaveSize 0
    }
}
