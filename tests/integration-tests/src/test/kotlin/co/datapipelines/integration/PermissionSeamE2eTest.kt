package co.datapipelines.integration

import co.datapipelines.DatapipelinesApplication
import co.datapipelines.integration.E2eSession.asSession
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.util.UUID

/**
 * **The isolated-permission witness** (security-assurance record §7.1, ratified B4; lane 217a, A.3).
 *
 * Every role holds many permissions, so a walk over roles cannot tell a route that declares the
 * RIGHT permission from one that declares another permission the same roles happen to hold: a
 * delete route declaring `pipeline.create` passes every role walk, because every role holding one
 * holds both. The only oracle that separates them is a principal that holds ONE permission and
 * nothing else — which no role is.
 *
 * This suite builds that principal through the seam, never by mocking a decision: the context's
 * [co.datapipelines.auth.PermissionResolver] is replaced by a synthetic grant (a test-sources proxy
 * over the production interface, registered as the context's `@Primary` resolver) that answers,
 * for each witness workspace, exactly one permission — and for every other workspace delegates to
 * the production resolver. The security chain, the `ScopeInterceptor`, the MCP dispatcher, every
 * service and every filter run exactly as shipped. Then, for each witnessed permission, its
 * principal — a member of its own workspace — calls every governed REST/UI route with a session,
 * and must be admitted exactly where auth.md §7.6 PLACES that permission's surfaces and refused ON
 * THE ROLE AXIS everywhere else. The expectation is the doc's placement, never the annotation being
 * tested (record §5: the implementation's decision is not the oracle).
 *
 * The context is dirtied after the class: closing it closes the installation, which puts the
 * production resolver back for every other context in this JVM.
 *
 * Out of this witness, and why: public routes (no principal), the promotion receiver family (a
 * route family fenced upstream by `PromotionServerKeyFilter`), the two key-role columns (the
 * matrix reads them directly — see `PermissionResolver`'s KDoc), and — since keys v2 — the MCP
 * tool leg: an `mcp` key's chosen member role is judged by `ScopeMatrix.allowed` straight from
 * [co.datapipelines.auth.RolePermissions] (the `keyRole` short-circuit), so no seedable key is a
 * permission-isolated principal and the seam cannot isolate a tool. The tools' wire truth is the
 * role walk's MCP leg (`RoleWalkE2eTest`); what it cannot separate — permissions co-granted by
 * every role that holds any of them (169 of the 406 tool-permission pairs at conversion, measured
 * 2026-09-25) — has no guard until the follow-up issue settles one. Admission is the subject, exactly
 * as in the role walk: a reached handler may answer 404 or 400 on the deliberately absent ids.
 */
@SpringBootTest(
    classes = [DatapipelinesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // The walk sends ~180 route requests per witness in a few seconds; the §12 per-user limiter (100/s by
    // default) would answer some of them with a 429, which is neither a refusal nor a reach.
    properties = ["datapipelines.rate-limit.requests-per-second=100000", "datapipelines.rate-limit.requests-per-minute=1000000"],
)
@Import(PermissionSeamE2eTest.SeamConfiguration::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PermissionSeamE2eTest : EntryAssuranceE2eBase() {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var context: ApplicationContext

    private val doc = RoleMatrixDocE2e.read()
    private val rows = RoleMatrixDocE2e.permissionRows(doc)

    @Test
    fun `a principal holding one permission reaches exactly the routes that declare it - and is refused on every other one`() {
        ensureSeeded()
        val routes = governedRoutes()
        val matrix = mutableListOf<String>()
        val mismatches = mutableListOf<String>()

        WITNESSED.forEach { permission ->
            val principal = principals.getValue(permission)
            val routeAnswers = routes.associateWith { route -> callRoute(route, principal) }
            routeAnswers.filterValues { it.first in UNSCORABLE_STATUSES }.forEach { (route, answer) ->
                mismatches += "$permission UNSCORABLE ${route.method} ${route.pattern}: ${answer.first} ${answer.second.take(EXCERPT)}"
            }
            val reachedRoutes = routeAnswers.filterValues { !it.roleRefusal() }.keys.toList()
            // The oracle is the REVIEWED placement — the routes §7.6 names on this permission's row —
            // never the annotation under test: a route re-declared with another permission would carry
            // its expectation along with it (measured: delete re-declared as create stayed green that way).
            val placed = rows[permission]?.routes.orEmpty().toSet()
            val expectedRoutes = routes.filter { "${it.method} ${EntryDiscovery.canonical(it.pattern)}" in placed }

            (reachedRoutes.toSet() - expectedRoutes.toSet()).forEach {
                mismatches +=
                    "$permission REACHED ${it.method} ${it.pattern} (declares ${it.permission})"
            }
            (expectedRoutes.toSet() - reachedRoutes.toSet()).forEach {
                mismatches +=
                    "$permission REFUSED ${it.method} ${it.pattern} (§7.6 places it on this row; the code declares ${it.permission})"
            }
            matrix += "$permission routes=${expectedRoutes.size}/${reachedRoutes.size} " +
                "refused=${routes.size - reachedRoutes.size}"
        }

        println(matrix.joinToString("\n") { "event=seam.witness $it" })
        mismatches.joinToString("\n") shouldBe ""
        // Non-vacuity: every witnessed permission has at least one route of its own in §7.6.
        WITNESSED.forEach { permission ->
            rows[permission]?.routes.orEmpty().size shouldBeGreaterThanOrEqual 1
        }
        WITNESSED.size shouldBeGreaterThanOrEqual WITNESS_FLOOR
        routes.size shouldBeGreaterThanOrEqual ROUTE_FLOOR
    }

    @Test
    fun `the context's resolver is the synthetic grant - and every workspace outside the witness is judged by the production table`() {
        val installed =
            Class
                .forName(RESOLUTION)
                .getField("INSTANCE")
                .get(null)
                .let { it.javaClass.getMethod("getResolver").invoke(it) }
        Proxy.isProxyClass(installed.javaClass) shouldBe true
        // A workspace no witness owns: the synthetic grant delegates, so a viewer reads pipelines and deletes none.
        val holds = Class.forName(RESOLVER).getMethod("holds", UUID::class.java, ROLE, Boolean::class.javaPrimitiveType, PERMISSION)
        val viewer = ROLE.enumConstants.first { (it as Enum<*>).name == "VIEWER" }
        holds.invoke(installed, UUID.randomUUID(), viewer, false, permission("pipeline.read")) shouldBe true
        holds.invoke(installed, UUID.randomUUID(), viewer, false, permission("pipeline.delete")) shouldBe false
    }

    // ------------------------------------------------------------------ the synthetic grant

    /**
     * Registers the synthetic grant as the context's `@Primary` resolver; the production bean stays,
     * un-chosen. Imported explicitly: with `@SpringBootTest(classes = …)` a nested `@TestConfiguration`
     * is not picked up on its own (measured — the first run judged every witness by the production
     * table). A class, not an object (detekt's utility-class rule suppressed for it): Spring
     * instantiates a configuration class.
     */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor")
    class SeamConfiguration {
        companion object {
            @JvmStatic
            @Bean
            fun syntheticGrantRegistrar(): BeanDefinitionRegistryPostProcessor =
                object : BeanDefinitionRegistryPostProcessor {
                    @Suppress("UNCHECKED_CAST")
                    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
                        val definition = RootBeanDefinition(Class.forName(RESOLVER) as Class<Any>) { SyntheticGrantResolver.proxy() }
                        definition.isPrimary = true
                        registry.registerBeanDefinition("syntheticGrantResolver", definition)
                    }

                    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
                }
        }
    }

    /**
     * `holds(workspaceId, role, superAdmin, permission)` for the witness workspaces: true exactly for
     * the one permission that workspace was granted, whatever the role. Any other workspace (or none)
     * is asked of the production resolver unchanged — so nothing else in this context is judged
     * differently from production.
     */
    object SyntheticGrantResolver : InvocationHandler {
        private val production: Any by lazy { Class.forName(PRODUCTION).getField("INSTANCE").get(null) }

        fun proxy(): Any = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Class.forName(RESOLVER)), this)

        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            when (method.name) {
                "holds" -> {
                    val granted = GRANTS[args!![0] as UUID?]
                    if (granted == null) {
                        method.invoke(production, *args)
                    } else {
                        wireOf(args[3]!!) == granted
                    }
                }

                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "toString" -> {
                    "SyntheticGrantResolver(${GRANTS.size} witness workspaces)"
                }

                else -> {
                    error("unexpected call ${method.name} on the synthetic grant")
                }
            }
    }

    // ------------------------------------------------------------------ the walk

    private data class Route(
        val method: String,
        val pattern: String,
        val path: String,
        val permission: String,
    )

    private fun governedRoutes(): List<Route> =
        EntryDiscovery
            .handlerMethods(context)
            .filter { !it.public && !it.pattern.startsWith(PROMOTION_RECEIVER_PREFIX) }
            .map {
                Route(
                    it.method,
                    it.pattern,
                    EntryDiscovery.concretePath(it.pattern),
                    requireNotNull(it.permission) { "${it.handler}" },
                )
            }.distinct()

    /**
     * `(status, body)` of [route] as [principal]. Refused means ON THE ROLE AXIS (403
     * `auth.role_required`); a 401 or a 429 is neither refused nor reached and fails the walk as
     * unscorable; anything else (200, 302, 400, 404) is "reached", as in the role walk.
     */
    private fun callRoute(
        route: Route,
        principal: Witness,
    ): Pair<Int, String> {
        val request =
            given()
                .port(port)
                .asSession(principal.session)
                .redirects()
                .follow(false)
                .accept("*/*")
                .contentType(ContentType.JSON)
                .body("{}")
                .`when`()
        val response =
            when (route.method) {
                "POST" -> request.post(route.path)
                "PUT" -> request.put(route.path)
                "PATCH" -> request.patch(route.path)
                "DELETE" -> request.delete(route.path)
                else -> request.get(route.path)
            }.then().extract()
        return response.statusCode() to response.asString()
    }

    private fun Pair<Int, String>.roleRefusal(): Boolean =
        first == HTTP_FORBIDDEN && CODE.find(second)?.groupValues?.get(1) == ROLE_REQUIRED

    // ------------------------------------------------------------------ the witnesses

    /** One witnessed permission's principal: a viewer member of its own workspace, with a session. */
    private data class Witness(
        val session: String,
    )

    private val principals: Map<String, Witness> by lazy {
        WITNESSED.associateWith { permission ->
            val index = WITNESSED.indexOf(permission)
            Witness(
                session = E2eSession.jwt(jwtSecret, userId(index), email(index), workspace = workspaceName(index)),
            )
        }
    }

    private var seeded = false

    private fun ensureSeeded() {
        if (seeded) return
        seeded = true
        val pg = SharedE2e.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
            WITNESSED.forEachIndexed { index, permission ->
                connection.createStatement().use { s ->
                    s.execute(
                        "INSERT INTO workspaces (id, name, display_name) VALUES ('${workspaceId(
                            index,
                        )}', '${workspaceName(index)}', '$permission')",
                    )
                    s.execute(
                        "INSERT INTO users (id, email, display_name, provider, provider_subject, is_active, is_admin) VALUES " +
                            "('${userId(index)}', '${email(index)}', 'Seam $index', 'test', 'seam-$index', TRUE, FALSE)",
                    )
                    // The DB role is irrelevant inside a witness workspace — the synthetic grant ignores it — so the least one.
                    s.execute(
                        "INSERT INTO workspace_members (workspace_id, user_id, role) VALUES ('${workspaceId(
                            index,
                        )}', '${userId(index)}', 'viewer')",
                    )
                }
            }
        }
    }

    private fun permission(wire: String): Any = PERMISSION.enumConstants.first { wireOf(it) == wire }

    companion object {
        private const val RESOLVER = "co.datapipelines.auth.PermissionResolver"
        private const val PRODUCTION = "co.datapipelines.auth.RolePermissionsResolver"
        private const val RESOLUTION = "co.datapipelines.auth.PermissionResolution"
        private val ROLE: Class<*> = Class.forName("co.datapipelines.auth.WorkspaceRole")
        private val PERMISSION: Class<*> = Class.forName("co.datapipelines.auth.Permission")

        /**
         * The witnessed permissions — chosen across families, and in co-granted pairs a role walk cannot
         * separate (create/delete/update, read/execute, release/promote, read/cancel), plus instance-,
         * key-, member- and profile-scoped rows. Fourteen; the record's floor is ten. `api_key.create`
         * was witnessed until keys v2 moved the create routes onto `mcp_key.create`'s row and left
         * `api_key.create` a service-CHECK claim with "No route of its own" (auth.md §7.6) — a
         * permission with no surface proves nothing, so the slot moved with the routes.
         */
        val WITNESSED =
            listOf(
                "pipeline.read",
                "pipeline.create",
                "pipeline.delete",
                "pipeline.execute",
                "template.update",
                "template.release",
                "execution.read",
                "execution.cancel",
                "endpoint.publish",
                "promotion.promote",
                "mcp_key.create",
                "workspace.members.manage",
                "datasource.manage",
                "profile.password",
            )

        /** `workspace id -> the one permission granted there`, read by the synthetic grant. */
        private val GRANTS: Map<UUID, String> =
            WITNESSED.withIndex().associate { (index, wire) ->
                UUID.fromString(workspaceId(index)) to
                    wire
            }

        private fun workspaceId(index: Int) = "5e5e0000-0000-0000-0000-%012d".format(index + WORKSPACE_ID_BASE)

        private fun userId(index: Int) = "5e5e1000-0000-0000-0000-%012d".format(index + WORKSPACE_ID_BASE)

        private fun workspaceName(index: Int) = "seam-witness-$index"

        private fun email(index: Int) = "seam-$index@witness.test"

        private fun wireOf(permission: Any): String = permission.javaClass.getMethod("getWire").invoke(permission) as String

        private const val WORKSPACE_ID_BASE = 217_000
        private const val WITNESS_FLOOR = 10
        private const val ROUTE_FLOOR = 150
        private const val HTTP_FORBIDDEN = 403
        private val UNSCORABLE_STATUSES = setOf(401, 429)

        private const val EXCERPT = 240
        private const val ROLE_REQUIRED = "auth.role_required"
        private const val PROMOTION_RECEIVER_PREFIX = "/api/v1/promotion/"
        private val CODE = Regex("\"code\"\\s*:\\s*\"([a-z_.]+)\"")
    }
}
