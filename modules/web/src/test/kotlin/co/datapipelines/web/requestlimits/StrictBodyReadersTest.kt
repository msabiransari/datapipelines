package co.datapipelines.web.requestlimits

import co.datapipelines.application.dashboards.DashboardKeyService
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.api.ApiExceptionHandler
import co.datapipelines.web.authapi.AuthController
import co.datapipelines.web.dashboards.DashboardKeyBindingsController
import co.datapipelines.web.endpoints.EndpointsController
import co.datapipelines.web.pipelines.PromotionController
import co.datapipelines.web.pipelines.PromotionInventoryService
import co.datapipelines.web.pipelines.PromotionReceiveService
import co.datapipelines.web.pipelines.PromotionWire
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

/**
 * #333 reader 4 - the five DTO-bodied handlers (#382 added the dashboard key binding) refuse a JSON number or boolean where the DTO declares a
 * STRING (and a string or float where it declares an integer), refuse an unknown key on the two request
 * DTOs that are not a cross-version wire, and never echo the value.
 *
 * Boot's mapper binds a scalar into a String field as text (`"name": 12` is the key named "12"), and the
 * bind happens in Spring's message converter, before any handler line - so the only place a route can own
 * the read is to take the body as a tree and bind it through a strict mapper. These cases go through the
 * real [ApiExceptionHandler] on standalone MVC, the collaborators STRICT mocks with nothing stubbed: a case
 * that reached one would fail, which is the point - a refusal happens before any service is touched.
 *
 * Every refusal asserts the KEY SET of `details`, that the response names the field's path, and that a
 * 9-digit sentinel is ABSENT from the whole body - so a message built from Jackson's own text (which quotes
 * the value) cannot pass.
 */
class StrictBodyReadersTest {
    private val apiKeyService = mockk<ApiKeyService>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val userService = mockk<UserService>()
    private val endpointKeyService = mockk<EndpointKeyService>()
    private val publishing = mockk<EndpointPublishService>()
    private val bindings = mockk<EndpointKeyBindingRepository>()
    private val pipelines = mockk<PipelineRepository>()
    private val inventoryService = mockk<PromotionInventoryService>()
    private val receiveService = mockk<PromotionReceiveService>()
    private val dashboardKeys = mockk<DashboardKeyService>()

    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AuthController(apiKeyService, apiKeyRepository, userService, endpointKeyService),
                EndpointsController(publishing, endpointKeyService, bindings, apiKeyRepository, pipelines),
                PromotionController(inventoryService, receiveService),
                DashboardKeyBindingsController(dashboardKeys, apiKeyRepository),
            ).setControllerAdvice(ApiExceptionHandler())
            .build()

    @BeforeEach
    fun authenticate() {
        val principal =
            AuthenticatedPrincipal(
                UUID.randomUUID(),
                "a@b.c",
                "A",
                AuthMethod.API_KEY,
                "dpk_abc",
                workspace = WorkspaceContext(UUID.randomUUID(), "acme"),
            )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `a number or a boolean where a string is declared is refused with its path on all five routes, never bound as text`() {
        STRING_CASES.forEach { case ->
            SCALARS.forEach { scalar ->
                withClue("${case.route} ${case.path} = $scalar") {
                    val body = case.bodyWith(scalar)
                    val reply = refused(case.route, body)

                    reply.at("/error/code").asText() shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
                    reply.at("/error/details/reason").asText() shouldBe "wrong_type"
                    reply.at("/error/details/path").asText() shouldBe case.path
                    reply.at("/error/details/expected").asText() shouldBe "a string"
                    detailKeys(reply) shouldContainExactlyInAnyOrder listOf("reason", "path", "expected")
                    reply.toString() shouldNotContain SENTINEL.toString()
                }
            }
        }
    }

    @Test
    fun `a string or a float where an integer is declared is refused, never coerced`() {
        listOf("\"5\"", "5.5").forEach { scalar ->
            withClue("timeout_seconds = $scalar") {
                val reply = refused(ENDPOINTS, """{"path":"/a","pipeline":"p","timeout_seconds":$scalar}""")

                reply.at("/error/details/reason").asText() shouldBe "wrong_type"
                reply.at("/error/details/path").asText() shouldBe "timeout_seconds"
                reply.at("/error/details/expected").asText() shouldBe "an integer"
            }
        }
    }

    @Test
    fun `an unknown key on the two request DTOs is refused by name, the key clipped`() {
        UNKNOWN_KEY_CASES.forEach { (route, body) ->
            withClue("$route with a $GARBAGE_LENGTH-char unknown key") {
                val reply = refused(route, body)

                reply.at("/error/details/reason").asText() shouldBe "unknown_key"
                detailKeys(reply) shouldContainExactlyInAnyOrder listOf("reason", "path")
                (reply.at("/error/details/path").asText().length <= MAX_PATH_ECHO) shouldBe true
                reply.toString() shouldNotContain GARBAGE
            }
        }
    }

    @Test
    fun `a missing required field is the same refusal naming the field`() {
        MISSING_CASES.forEach { (route, caseOf) ->
            withClue("$route missing ${caseOf.second}") {
                val reply = refused(route, caseOf.first)

                reply.at("/error/details/reason").asText() shouldBe "missing"
                reply.at("/error/details/path").asText() shouldBe caseOf.second
                detailKeys(reply) shouldContainExactlyInAnyOrder listOf("reason", "path")
            }
        }
    }

    @Test
    fun `a body that is not an object is refused as wrong_type at the root`() {
        ROOT_ROUTES.forEach { route ->
            withClue(route) {
                val reply = refused(route, "[1,2]")

                reply.at("/error/details/reason").asText() shouldBe "wrong_type"
                reply.at("/error/details/expected").asText() shouldBe "an object"
                detailKeys(reply) shouldContainExactlyInAnyOrder listOf("reason", "path", "expected")
            }
        }
    }

    /**
     * #382's non-vacuity floor: the dashboard binding route carries all THREE String fields, a missing
     * required field, an unknown key and a root-shape case in the tables above, so a table emptied or
     * filtered cannot leave the guard green. The inventory is printed so a reader sees what ran.
     */
    @Test
    fun `the dashboard binding route is in every table with all three fields`() {
        val fields = STRING_CASES.filter { it.route == DASHBOARD_BINDINGS }.map { it.path }
        val inventory =
            "dashboard bindings guard inventory: string fields=$fields x scalars=$SCALARS, " +
                "missing=${MISSING_CASES.filter { it.first == DASHBOARD_BINDINGS }.map { it.second.second }}, " +
                "unknown=${UNKNOWN_KEY_CASES.count { it.first == DASHBOARD_BINDINGS }}, " +
                "root=${ROOT_ROUTES.count { it == DASHBOARD_BINDINGS }}"
        println(inventory)

        withClue(inventory) {
            fields shouldContainExactlyInAnyOrder listOf("name_prefix", "api_key_name", "api_key_id")
            MISSING_CASES.count { it.first == DASHBOARD_BINDINGS } shouldBe 1
            UNKNOWN_KEY_CASES.count { it.first == DASHBOARD_BINDINGS } shouldBe 1
            ROOT_ROUTES.count { it == DASHBOARD_BINDINGS } shouldBe 1
        }
    }

    /** The batch is a cross-version wire: an unknown key stays tolerated (`ignoreUnknown` on the class). */
    @Test
    fun `the promotion batch still tolerates an unknown key`() {
        every { receiveService.apply(any(), any()) } returns PromotionWire.Applied("w", "s", 0, 0)

        val result =
            mvc
                .perform(post(PUSH).contentType(MediaType.APPLICATION_JSON).content("""{$PUSH_HEAD,"from_the_future":1}"""))
                .andReturn()

        result.response.status shouldBe 200
        verify(exactly = 1) { receiveService.apply(any(), any()) }
    }

    /** The coercion half still applies to it, an unknown key beside the bad value changing nothing. */
    @Test
    fun `the promotion batch refuses a number where a string is declared even beside an unknown key`() {
        val reply = refused(PUSH, """{$PUSH_HEAD,"from_the_future":1,"endpoints":[{"path":"/a","pipeline":$SENTINEL}]}""")

        reply.at("/error/details/reason").asText() shouldBe "wrong_type"
        reply.at("/error/details/path").asText() shouldBe "endpoints[0].pipeline"
    }

    private fun refused(
        route: String,
        body: String,
    ): JsonNode {
        val result = mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn()

        withClue("status for $route <- $body") { result.response.status shouldBe 400 }
        confirmVerified(
            apiKeyService,
            apiKeyRepository,
            userService,
            endpointKeyService,
            publishing,
            bindings,
            pipelines,
            receiveService,
            dashboardKeys,
        )
        return JSON.readTree(result.response.contentAsString)
    }

    private fun detailKeys(reply: JsonNode): List<String> =
        reply
            .at("/error/details")
            .fieldNames()
            .asSequence()
            .toList()

    private data class StringCase(
        val route: String,
        val path: String,
        val template: String,
    ) {
        /** [template] with the field's JSON value replaced by [scalar] (`%s`). */
        fun bodyWith(scalar: String): String = template.replace("%s", scalar)
    }

    private companion object {
        const val SENTINEL = 987654321
        const val GARBAGE_LENGTH = 2_048
        const val MAX_PATH_ECHO = 161

        const val AUTH_KEYS = "/api/v1/auth/api-keys"
        const val ENDPOINTS = "/api/v1/endpoints"
        const val BINDINGS = "/api/v1/endpoints/bindings"
        const val PUSH = "/api/v1/promotion/push"
        const val DASHBOARD_BINDINGS = "/api/v1/dashboards/bindings"

        /** The two scalar shapes a String field must refuse: a number (the sentinel) and a boolean. */
        val SCALARS = listOf(SENTINEL.toString(), "true")

        val GARBAGE = "k".repeat(GARBAGE_LENGTH)

        val ROOT_ROUTES = listOf(AUTH_KEYS, ENDPOINTS, BINDINGS, PUSH, DASHBOARD_BINDINGS)

        val UNKNOWN_KEY_CASES =
            listOf(
                AUTH_KEYS to """{"name":"n","kind":"mcp","$GARBAGE":1}""",
                ENDPOINTS to """{"path":"/a","pipeline":"p","$GARBAGE":1}""",
                BINDINGS to """{"path_prefix":"/a","api_key_id":"dpk_1","$GARBAGE":1}""",
                DASHBOARD_BINDINGS to """{"name_prefix":"a","api_key_id":"dpk_1","$GARBAGE":1}""",
            )

        val MISSING_CASES =
            listOf(
                AUTH_KEYS to ("""{"kind":"mcp"}""" to "name"),
                ENDPOINTS to ("""{"pipeline":"p"}""" to "path"),
                BINDINGS to ("""{"api_key_id":"dpk_1"}""" to "path_prefix"),
                DASHBOARD_BINDINGS to ("""{"api_key_id":"dpk_1"}""" to "name_prefix"),
            )

        /** The three required keys of a well-typed batch, without the braces. */
        const val PUSH_HEAD = """"source_env":"s","key_fingerprint":"f","workspace":"w""""

        val JSON = ObjectMapper()

        val STRING_CASES =
            listOf(
                StringCase(AUTH_KEYS, "name", """{"name":%s,"kind":"mcp"}"""),
                StringCase(AUTH_KEYS, "role", """{"name":"n","kind":"mcp","role":%s}"""),
                StringCase(AUTH_KEYS, "kind", """{"name":"n","kind":%s}"""),
                StringCase(AUTH_KEYS, "bindings[0]", """{"name":"n","kind":"endpoint","bindings":[%s]}"""),
                StringCase(ENDPOINTS, "path", """{"path":%s,"pipeline":"p"}"""),
                StringCase(ENDPOINTS, "pipeline", """{"path":"/a","pipeline":%s}"""),
                StringCase(ENDPOINTS, "description", """{"path":"/a","pipeline":"p","description":%s}"""),
                StringCase(BINDINGS, "path_prefix", """{"path_prefix":%s}"""),
                StringCase(BINDINGS, "api_key_name", """{"path_prefix":"/a","api_key_name":%s}"""),
                StringCase(BINDINGS, "api_key_id", """{"path_prefix":"/a","api_key_id":%s}"""),
                StringCase(DASHBOARD_BINDINGS, "name_prefix", """{"name_prefix":%s,"api_key_id":"dpk_1"}"""),
                StringCase(DASHBOARD_BINDINGS, "api_key_name", """{"name_prefix":"a","api_key_name":%s}"""),
                StringCase(DASHBOARD_BINDINGS, "api_key_id", """{"name_prefix":"a","api_key_id":%s}"""),
                StringCase(PUSH, "source_env", """{"source_env":%s,"key_fingerprint":"f","workspace":"w"}"""),
                StringCase(PUSH, "key_fingerprint", """{"source_env":"s","key_fingerprint":%s,"workspace":"w"}"""),
                StringCase(PUSH, "workspace", """{"source_env":"s","key_fingerprint":"f","workspace":%s}"""),
                StringCase(
                    PUSH,
                    "endpoints[0].path",
                    """{$PUSH_HEAD,"endpoints":[{"path":%s,"pipeline":"p"}]}""",
                ),
                StringCase(
                    PUSH,
                    "endpoints[0].bindings[0]",
                    """{$PUSH_HEAD,"endpoints":[{"path":"/a","pipeline":"p","bindings":[%s]}]}""",
                ),
            )
    }
}
