package co.datapipelines.web.dependencies

import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.Lifecycle
import co.datapipelines.web.api.ApiExceptionHandler
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * The eight handlers' behavior (#875 G4 §B): each route reads ITS family's row (an absent root answers that family's own
 * not-found code), the raw query reaches the parser, and a 200 is the §11.1 `data` object, never cached. Handler
 * behavior only — never evidence of the interceptor's order (`DependenciesRouteE2eTest`).
 */
class DependenciesControllerTest {
    private val families = FamilyKey.PRODUCTION.keys.associateWith { StubFamily(it) }
    private val transport = DependencyFamilyTransport.production(DependenciesRequestTest.LENSES)
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(DependenciesController(treeService(families.values.toList(), transport.families.map(transport::row))))
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun signIn() {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(member(), null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `each route answers an absent root with its own family's not-found code`() {
        FamilyKey.PRODUCTION.keys.forEach { family ->
            withClue(family.wire) {
                mvc
                    .perform(tree(family.wire, absentQuery(family)))
                    .andExpect(status().isNotFound)
                    .andExpect(jsonPath("$.error.code").value(transport.row(family).notFoundCode))
            }
        }
    }

    @Test
    fun `a resolved root is 200 with the section 11_1 data object and Cache-Control no-store`() {
        families.getValue(FamilyKey.PIPELINE).put(exact(FamilyKey.PIPELINE, "acme/daily/orders", 3), Lifecycle.DRAFT)

        mvc
            .perform(tree("pipeline", "name=acme%2Fdaily%2Forders&version=3"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.schema_version").value(1))
            .andExpect(jsonPath("$.data.roots[0]").value("n0"))
            .andExpect(jsonPath("$.data.nodes[0].family").value("pipeline"))
            .andExpect(jsonPath("$.data.nodes[0].name").value("acme/daily/orders"))
            .andExpect(jsonPath("$.data.nodes[0].version.number").value(3))
            .andExpect(jsonPath("$.data.nodes[0].lifecycle").value("DRAFT"))
            .andExpect(jsonPath("$.data.nodes[0].entity_id").isString)
            .andExpect(jsonPath("$.data.complete").value(true))
    }

    @Test
    fun `the handler reads the raw query - a family parameter is ignored, the route decides`() {
        families.getValue(FamilyKey.TEMPLATE).put(exact(FamilyKey.TEMPLATE, "acme/q.sql", 1))

        val body =
            mvc
                .perform(tree("template", "family=pipeline&name=acme/q.sql&version=1"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        body.contains("\"family\":\"template\"") shouldBe true
        families.getValue(FamilyKey.PIPELINE).calls.size shouldBe 0
    }

    private fun absentQuery(family: FamilyKey): String =
        when (transport.row(family).selector) {
            SelectorShape.NAME_AT_VERSION -> "name=${absentName(family)}&version=1"
            SelectorShape.NAME -> "name=absent_source"
            SelectorShape.ID -> "id=00000000-0000-0000-0000-0000000000ab"
        }

    private fun absentName(family: FamilyKey) =
        when (family) {
            FamilyKey.API_ENDPOINT -> "%2Fapi%2Fabsent%2Fv1%2Fx"
            FamilyKey.TEMPLATE -> "acme/absent.sql"
            else -> "acme/absent"
        }
}
