package co.datapipelines.web.api

import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.KeyRole
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.RolePermissions
import co.datapipelines.auth.ScopeInterceptor
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

/**
 * The mutating-handler guard (025 defect round, A1; re-keyed to handlers in 025b; to the
 * permission catalog in #215 slice (a); to KEY KINDS in slice (b)).
 *
 * Why it exists: an API key is CSRF-exempt (`ApiKeyCredentialMatcher`), so a mutating handler a
 * key can reach is gated by nothing but the key's authorization. Until #215 slice (b) that was the
 * key's SCOPE, and this test held every mutating handler above the `read` floor. Scopes are gone:
 * a key's reach is now its KIND's confined surface (`ScopeInterceptor.reachableBy` — the MCP key
 * `/mcp` only, B2; an `endpoint` key the serve paths and its own execution reads; a `server` key
 * the promotion routes) and then its KEY ROLE. So the guard asks the question that replaced the
 * floor: **which mutating handlers can each key kind reach at all, and does its role decide them
 * the way the entry says?** Every (kind, handler) pair is listed in [KEY_SURFACE_MUTATIONS] with
 * its reason; an unlisted one — a new mutating route that falls inside a kind's surface — fails
 * the build, and a listed one that is no longer reachable fails the non-vacuity arm.
 *
 * Coverage asserts an annotation EXISTS ([RequiredScopeCoverageTest]); this asserts who can get
 * to the handler that carries it (the "coverage ≠ existence" trap, MISTAKES.md).
 *
 * Born red (025): `PATCH /partials/profile/theme` mutated behind `READ_RESOURCES`. Falsified in
 * slice (b) by widening `reachableBy(USER, …)` to the whole app — every REST and partial mutation
 * is then named as reachable by the MCP key.
 */
class MutatingHandlerScopeFloorTest {
    @Test
    fun `every mutating handler declares a permission or is allowlisted as unauthenticated`() {
        val offenders =
            discoveredMutatingHandlers()
                .filter { it.permission == null && it.where !in UNAUTHENTICATED_BY_DESIGN }
                .map {
                    "${it.where}: mutating handler declares NO @RequiredScope " +
                        "(if it is unauthenticated by design, add it to UNAUTHENTICATED_BY_DESIGN with its permitAll justification)"
                }
        offenders shouldBe emptyList()
    }

    /**
     * Every mutating handler a key kind can reach, BOTH directions: an unlisted pair is a new
     * mutation inside a CSRF-exempt credential's surface; a listed pair that no longer resolves
     * exempts nothing and rots. For each, the kind's KEY ROLE must decide it the way the entry says
     * — a surface the role does not hold is a second line (refused by `auth.role_required`), not
     * a grant.
     */
    @Test
    fun `every mutating handler a key kind can reach is on that kind's declared surface`() {
        val reached =
            discoveredMutatingHandlers()
                .filter { it.path != null }
                .flatMap { handler ->
                    ApiKeyKind.entries
                        .filter { kind -> ScopeInterceptor.reachableBy(kind, concrete(handler.path!!)) }
                        .map { kind -> "${kind.wire} ${handler.where}" to handler }
                }.toMap()

        reached.keys shouldBe KEY_SURFACE_MUTATIONS.keys
        reached.forEach { (pair, handler) ->
            val kind = ApiKeyKind.fromWire(pair.substringBefore(' '))
            val role = KeyRole.forKind(kind)
            val held = role != null && handler.permission != null && handler.permission in RolePermissions.of(role)
            withClue(pair) { held shouldBe KEY_SURFACE_MUTATIONS.getValue(pair).heldByKeyRole }
        }
    }

    /** B2 in one line: the MCP key reaches no mutating MVC handler — its whole surface is the `/mcp` servlet. */
    @Test
    fun `the MCP key reaches no mutating handler`() {
        discoveredMutatingHandlers()
            .filter { it.path != null && ScopeInterceptor.reachableBy(ApiKeyKind.USER, concrete(it.path)) }
            .map { it.where } shouldBe emptyList()
    }

    /**
     * The same non-vacuity rule for the unauthenticated allowlist: an entry that has since
     * gained a `@RequiredScope`, been renamed, or been deleted must fail rather than sit
     * there certifying nothing. This list is the more dangerous of the two — an entry here
     * says "no credential is required at all" — so it must stay short, and every addition
     * has to name the `permitAll()` line in `SecurityConfig` that makes it true.
     */
    @Test
    fun `every unauthenticated-by-design entry is a discovered unannotated mutating handler`() {
        val unannotated =
            discoveredMutatingHandlers()
                .filter { it.permission == null }
                .map { it.where }
                .toSet()
        (UNAUTHENTICATED_BY_DESIGN.keys - unannotated) shouldBe emptySet()
    }

    /**
     * 097 §E — the FOURTH URL space. `ui-screens.md §2.1` declares three (UI pages,
     * `/partials`, `/api/v1`) and, until 097, described nothing about mutations that live on
     * a PAGE route: seven POSTs with a redirect and an `?ok=`/`?error=` banner, which is a
     * second error idiom beside §5.1's toasts. The ruling was "document, don't move" — a
     * page-route mutation is allowed when its success changes the SHELL (the workspace
     * switch re-mints the session cookie; promotion changes what the whole screen shows) or
     * when it must work without JS — so this arm is what keeps the list closed.
     *
     * Everything else mutates at `/partials` (a fragment and a toast) or `/api/v1` (an
     * envelope). A new page-route mutation fails here until someone writes down why it is
     * one of the two allowed kinds.
     */
    @Test
    fun `every mutating handler outside partials and api is an allowlisted page-route mutation`() {
        val offenders =
            discoveredMutatingHandlers()
                .filter { it.path != null && !it.path.startsWith("/partials") && !it.path.startsWith("/api") }
                .filterNot { it.where in PAGE_ROUTE_MUTATIONS }
                .map { "${it.where} (${it.path}): a mutating handler on a PAGE route — see ui-screens.md §2.1" }
        offenders shouldBe emptyList()
    }

    /** Non-vacuity, and rot control: every entry must still be a page-route mutation. */
    @Test
    fun `every page-route mutation entry is a discovered handler outside partials and api`() {
        val found =
            discoveredMutatingHandlers()
                .filter { it.path != null && !it.path.startsWith("/partials") && !it.path.startsWith("/api") }
                .map { it.where }
                .toSet()
        (PAGE_ROUTE_MUTATIONS.keys - found) shouldBe emptySet()
        // The rule is worth nothing if the path resolution quietly returns null for everything.
        found shouldHaveAtLeastSize 5
    }

    /** The scan sees the module's controllers — an empty scan would prove nothing. */
    @Test
    fun `the scan finds the module's controllers`() {
        allControllers() shouldHaveAtLeastSize 15
        allControllers().map { it.qualifiedName.orEmpty() } shouldContain
            "co.datapipelines.web.ui.WorkspacesUiController"
    }

    /** A mapping path as a concrete request path: every `{variable}` and `**` becomes one literal segment. */
    private fun concrete(path: String): String = path.replace(Regex("\\{[^}]+}"), "x").replace("**", "x")

    private data class DiscoveredHandler(
        val where: String,
        val permission: Permission?,
        /** The handler's full path — class-level prefix plus the mapping's own — or null. */
        val path: String?,
    )

    private fun discoveredMutatingHandlers(): List<DiscoveredHandler> =
        allControllers().flatMap { controller ->
            val classPermission = controller.findAnnotation<RequiredScope>()?.value
            controller.functions
                .filter { mutatingMethodOf(it.javaMethod) != null }
                .map { fn ->
                    DiscoveredHandler(
                        where = "${controller.simpleName}#${fn.name}",
                        permission = fn.findAnnotation<RequiredScope>()?.value ?: classPermission,
                        path = pathOf(controller, fn.javaMethod),
                    )
                }
        }

    private companion object {
        /**
         * Mutating handlers that carry NO scope because they are reachable BEFORE any
         * credential exists. Each entry must name the `SecurityConfig` `permitAll()` line
         * that makes it true — an unannotated mutating handler outside `/api`, `/partials`
         * and `/mcp` is otherwise exactly the shape that let a read key reach
         * `POST /workspace/switch`, so "no scope" needs a louder justification than a low
         * floor, not a quieter one.
         *
         * Added at the 026 merge: 026 branched before this guard existed, so its login
         * endpoint met the guard for the first time at merge — and the guard refused it,
         * which is the parallel-lane gap this test is here to catch.
         */
        val UNAUTHENTICATED_BY_DESIGN: Map<String, String> =
            mapOf(
                "LocalLoginController#login" to
                    "the local sign-in POST — it authenticates the caller, so it cannot require a credential. " +
                    "Explicitly permitted in SecurityConfig's permitAll() list alongside \"/login\"; brute force is " +
                    "bounded by LoginRateLimitFilter and the per-account lockout (auth.md §5A), not by a scope",
            )

        /** What a key-surface entry asserts: whether the kind's key role HOLDS the handler's permission, and why the pair exists. */
        data class SurfaceEntry(
            val heldByKeyRole: Boolean,
            val reason: String,
        )

        /** `"<kind> <Controller#method>"` → the one mutating handler that kind's surface reaches, and why (§7.7, #215). */
        val KEY_SURFACE_MUTATIONS: Map<String, SurfaceEntry> =
            mapOf(
                "server PromotionController#push" to
                    SurfaceEntry(
                        heldByKeyRole = true,
                        reason =
                            "the promotion receiver's push (versioning §10.4): the server key's whole purpose, " +
                                "held by promotion_receiver",
                    ),
                "endpoint ExecutionsController#cancel" to
                    SurfaceEntry(
                        heldByKeyRole = false,
                        reason =
                            "the execution-read path pattern an endpoint key reaches (to collect its own run) also maps " +
                                "DELETE — reached by path, refused by role: api_caller does not hold execution.cancel",
                    ),
            )

        /**
         * 177 §D.1 — `co.datapipelines`, not `co.datapipelines.web`: every controller on this
         * module's classpath, whichever module declares it (auth, application, mcp-server sit
         * below web and are reached; `modules/app` sits above and is covered by
         * `RoleWalkE2eTest`, which walks the running application's own handler mapping).
         */
        const val BASE_PACKAGE = "co.datapipelines"

        /**
         * The mutating HTTP verb a handler carries, or null for read verbs.
         *
         * The generic `@RequestMapping(method = [...])` form is checked too, and that is
         * not defensive coding: without it a handler written that way is invisible HERE
         * while the sibling coverage guard sees it, so it would ship mutating-and-
         * under-floored with every guard green and no allowlist entry to review. A guard
         * blind to a shape is the "coverage ≠ existence" trap this test exists to close.
         */
        fun mutatingMethodOf(method: java.lang.reflect.Method?): String? {
            if (method == null) return null
            val direct =
                listOf(
                    PostMapping::class.java to "POST",
                    PutMapping::class.java to "PUT",
                    PatchMapping::class.java to "PATCH",
                    DeleteMapping::class.java to "DELETE",
                ).firstOrNull { method.getAnnotation(it.first) != null }?.second
            if (direct != null) return direct
            return method
                .getAnnotation(RequestMapping::class.java)
                ?.method
                ?.map { it.name }
                ?.firstOrNull { it in MUTATING_VERBS }
        }

        private val MUTATING_VERBS = setOf("POST", "PUT", "PATCH", "DELETE")

        /** Class-level `@RequestMapping` prefix + the handler's own mapping path. */
        fun pathOf(
            controller: KClass<*>,
            method: java.lang.reflect.Method?,
        ): String? {
            if (method == null) return null
            val prefix =
                controller
                    .findAnnotation<RequestMapping>()
                    ?.value
                    ?.firstOrNull()
                    .orEmpty()
            val own =
                method.getAnnotation(PostMapping::class.java)?.value?.firstOrNull()
                    ?: method.getAnnotation(PutMapping::class.java)?.value?.firstOrNull()
                    ?: method.getAnnotation(PatchMapping::class.java)?.value?.firstOrNull()
                    ?: method.getAnnotation(DeleteMapping::class.java)?.value?.firstOrNull()
                    ?: method.getAnnotation(RequestMapping::class.java)?.value?.firstOrNull()
                    ?: ""
            val full = prefix + own
            return full.ifEmpty { null }
        }

        /**
         * The page-route mutations (ui-screens.md §2.1, "Page-route mutation (PRG)"), each
         * with the reason it is not a `/partials` mutation. The two admissible reasons are the
         * §2.1 row's: the success changes the SHELL, or the action must survive without JS.
         */
        val PAGE_ROUTE_MUTATIONS: Map<String, String> =
            mapOf(
                "WorkspacesUiController#switch" to
                    "SHELL: it re-mints the session cookie and re-renders every workspace-scoped surface. " +
                    "A fragment swap cannot change the identity the rest of the page was rendered under, " +
                    "so this one MUST stay a full-page POST",
                "WorkspacesUiController#create" to
                    "SHELL: a new workspace joins the switcher in the header, which is outside any fragment " +
                    "this screen swaps; the redirect re-renders it",
                "WorkspacesUiController#addMember" to
                    "NO-JS: workspace administration is the recovery surface an operator reaches when the " +
                    "app is misbehaving, and it is a plain form POST end to end. (Changing an existing member's " +
                    "role is NOT here since 177/D22: it is the `/partials/workspaces/…/role` htmx partial, the one " +
                    "verb an admin repeats down a table, where a full-page reload per row lost the scroll.)",
                "WorkspacesUiController#removeMember" to
                    "NO-JS: the sibling of addMember, same form, same banner",
                "WorkspacesUiController#revokeMemberKey" to
                    "NO-JS: the fifth member verb (#200) — revoking a member's login-minted key from " +
                    "the same administration row, a plain form POST beside removeMember with the same " +
                    "banner the row's verbs share",
                "WorkspacesUiController#revokeInvitation" to
                    "NO-JS: the fourth member verb (113) — revoking a pending invitation, same form and " +
                    "same toast stack as the member rows it sits beneath",
                "WorkspacesUiController#renameDisplay" to
                    "NO-JS: the workspace's display name, edited on the same administration surface and by " +
                    "the same plain form POST as its members",
                "WorkspacesUiController#deactivate" to
                    "SHELL: a deactivated workspace leaves the switcher, and the ACTIVE one leaving changes " +
                    "what the rail and every workspace-scoped surface can show (D-R10)",
                "WorkspacesUiController#reactivate" to
                    "SHELL: the other half of deactivate — the workspace rejoins the switcher",
                "WorkspacesUiController#delete" to
                    "SHELL: deleting the ACTIVE workspace changes what the switcher and the rail can show",
                "PromotionUiController#promote" to
                    "SHELL: a promotion changes the deployment the whole screen describes, and the operator " +
                    "is told the outcome by the ?ok=/?error= banner the redirect carries",
                "LocalLoginController#login" to
                    "NO-JS, and pre-credential: the sign-in POST mints the session it is authenticated by. " +
                    "It is also on UNAUTHENTICATED_BY_DESIGN above, which is where its permitAll is justified",
            )
    }

    private fun allControllers(): List<KClass<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply {
                addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
                addIncludeFilter(AnnotationTypeFilter(Controller::class.java))
            }.findCandidateComponents(BASE_PACKAGE)
            .map(BeanDefinition::getBeanClassName)
            .filterNotNull()
            .map { Class.forName(it) }
            // Production handlers only: test probes live under /test/ compile output.
            .filter {
                it.protectionDomain.codeSource.location.path
                    .contains("/main/")
            }.map { it.kotlin }
            .sortedBy { it.qualifiedName }
}
