package co.datapipelines.web.ui

import co.datapipelines.application.lens.LensedView
import co.datapipelines.application.lens.PromoterLens
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserRepository
import co.datapipelines.auth.WorkspaceService
import co.datapipelines.pipeline.ReadLens
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.ControllerAdviceBean
import java.util.UUID

/**
 * #652 — the runtime half of the shell partition (the source half lives in
 * [ShellPageGuardTest]): the four shell advices are selected by
 * `assignableTypes = [ShellPage::class]`, so
 *
 * 1. every advice's [ControllerAdviceBean] applies to a `ShellPage` controller and to NO
 *    `@RestController` (the exact regression #652 fixed: `annotations = [Controller::class]`
 *    matched REST through its meta-annotation);
 * 2. through the advice pipeline, a JSON handler pays none of the shell reads
 *    (`UserRepository.findById`, `WorkspaceService.listOwn`) and a shell page pays them —
 *    pinned through standalone MockMvc, the same mechanism
 *    `RequestMappedHandlerAdapter` runs in production for `@ModelAttribute` advices.
 *
 * Falsified at birth (F1): reverting ONE advice's selector to
 * `annotations = [Controller::class]` turns the applicability arm (and the JSON-cost arm,
 * once re-registered) red.
 */
class ShellAdviceSelectionTest {
    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.OIDC,
                workspace = null,
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearPrincipal() {
        SecurityContextHolder.clearContext()
    }

    /** Arm (a): the selector, as Spring itself resolves it — the authoritative applicability answer. */
    @Test
    fun `the four advices apply to ShellPage controllers and to no REST controller`() {
        val advices = registeredAdvices()
        val violations = mutableListOf<String>()
        if (advices.size != 4) {
            violations += "expected the four shell advices, Spring reported ${advices.size}: " +
                advices.joinToString { it.beanType?.simpleName ?: "?" }
        }
        for (advice in advices) {
            if (advice.isApplicableToBeanType(RestStubController::class.java)) {
                violations += "${advice.beanType?.simpleName} applies to a @RestController — its selector left the ShellPage partition"
            }
            if (!advice.isApplicableToBeanType(PageStubController::class.java)) {
                violations += "${advice.beanType?.simpleName} does not apply to a ShellPage controller"
            }
        }
        violations.shouldBeEmpty()
    }

    /** Arm (b): through the pipeline, JSON pays zero shell reads; a shell page pays exactly one. */
    @Test
    fun `a JSON handler pays no shell read, a shell page pays exactly one`() {
        val userRepository = mockk<UserRepository>()
        val workspaceService = mockk<WorkspaceService>()
        val userReads = mutableListOf<UUID>()
        val workspaceLists = mutableListOf<AuthenticatedPrincipal>()
        every { userRepository.findById(capture(userReads)) } returns mockk(relaxed = true)
        every { workspaceService.listOwn(capture(workspaceLists)) } returns emptyList()

        val advices = shellAdvices(userRepository, workspaceService)
        mockMvcFor(RestStubController(), advices)
            .perform(get(REST_STUB_PATH))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        withClue(
            "the shell advices (${advices.joinToString { it::class.simpleName!! }}) ran for a JSON handler: " +
                "a REST request paid ${userReads.size} users read(s) and ${workspaceLists.size} workspace list(s)",
        ) {
            userReads.shouldBeEmpty()
            workspaceLists.shouldBeEmpty()
        }

        mockMvcFor(PageStubController(), advices)
            .perform(get(PAGE_STUB_PATH))
            .andExpect(status().isOk)
        userReads.shouldHaveSize(1)
        workspaceLists.shouldHaveSize(1)
    }

    private fun shellAdvices(
        userRepository: UserRepository,
        workspaceService: WorkspaceService,
    ): Array<Any> =
        arrayOf(
            AppShellAdvice(
                userRepository,
                NavCounts(emptyMap()),
                PromoterLens {
                    LensedView(
                        pipelines = ReadLens.Everything,
                        templates = ReadLens.Everything,
                        parameterSets = ReadLens.Everything,
                        visualizations = ReadLens.Everything,
                        dashboards = ReadLens.Everything,
                    )
                },
            ),
            UiWorkspaceAdvice(workspaceService, mockk(relaxed = true)),
            ReleaseFlashAdvice(mockk(relaxed = true)),
            SiteOriginAdvice(),
        )

    /** The four advices as Spring sees them, over a real bean factory. */
    private fun registeredAdvices(): List<ControllerAdviceBean> {
        val userRepository = mockk<UserRepository>()
        val workspaceService = mockk<WorkspaceService>()
        every { userRepository.findById(any()) } returns mockk(relaxed = true)
        every { workspaceService.listOwn(any()) } returns emptyList()
        val context = AnnotationConfigApplicationContext()
        shellAdvices(userRepository, workspaceService).forEachIndexed { index, advice ->
            context.beanFactory.registerSingleton("shellAdvice$index", advice)
        }
        context.refresh()
        return ControllerAdviceBean.findAnnotatedBeans(context)
    }

    private fun mockMvcFor(
        controller: Any,
        advices: Array<Any>,
    ): MockMvc =
        MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(*advices)
            .build()

    @RestController
    class RestStubController {
        @GetMapping(REST_STUB_PATH)
        fun restJson(): Map<String, String> = mapOf("ok" to "true")
    }

    @Controller
    class PageStubController : ShellPage {
        @GetMapping(PAGE_STUB_PATH)
        @ResponseBody
        @Suppress("FunctionOnlyReturningConstant") // the body is irrelevant — the request's ADVICE reads are the assertion
        fun page(): String = "page"
    }

    companion object {
        private const val REST_STUB_PATH = "/shell-guard/rest"
        private const val PAGE_STUB_PATH = "/shell-guard/page"
    }
}
