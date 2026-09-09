package co.datapipelines.web.api

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.Scope
import co.datapipelines.auth.ScopeMatrix
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
 * The mutating-floor guard (025 defect round, A1; re-keyed to handlers in the 025b
 * fix round): every POST/PUT/PATCH/DELETE handler must carry an operation whose §7.6
 * floor sits ABOVE `read` — unless the HANDLER is on the explicit allowlist below, each
 * entry justified in place.
 *
 * Why this test exists when [RequiredScopeCoverageTest] already passes: coverage asserts
 * an annotation EXISTS, not what it permits. An API key authenticates on EVERY path
 * (`ApiKeyFilter` has no path test) and is CSRF-exempt (`ApiKeyCredentialMatcher`), so a
 * mutating handler floored at `read` is reachable by a read-scoped key with no CSRF
 * token — the annotation is the only gate (the 022 verified addendum's 37-route scan).
 * `ScopeInterceptor`'s default-deny covers only the un-annotated case, and only under
 * `/api`, `/partials` and `/mcp`; a mis-floored mutation is invisible to every existing
 * check (the "coverage ≠ existence" trap, MISTAKES.md).
 *
 * The allowlist is keyed on individual HANDLERS (`"ControllerClass#method"`), not on
 * operations. The operation-keyed version is how five `WorkspacesController` mutations
 * slipped through (025 review, blocking): one `MANAGE_WORKSPACE` entry silently exempted
 * every handler declaring that operation — including REST handlers an API key CAN reach —
 * behind a justification written for the session-only UI twins. Two handlers sharing one
 * operation must never be exempted by one entry. The second test below is the
 * non-vacuity half: every allowlist key must resolve to a discovered read-floored
 * mutating handler, so a stale, misspelled, or since-raised entry fails the build
 * instead of exempting nothing (a guard that exempts nothing is exactly how the
 * operation-keyed version rotted).
 *
 * Born red: `PATCH /partials/profile/theme` mutated behind `READ_RESOURCES` until it got
 * its own `PROFILE_PREFERENCE` row (same commit as this test). Falsified again in 025b:
 * re-lowering `WORKSPACE_CREATE`/`MANAGE_WORKSPACE` to `read` names the five
 * `WorkspacesController` handlers.
 */
class MutatingHandlerScopeFloorTest {
    @Test
    fun `every mutating handler sits above the read floor or is allowlisted`() {
        val offenders = mutableListOf<String>()
        discoveredMutatingHandlers().forEach { handler ->
            val operation = handler.operation
            if (operation == null) {
                if (handler.where !in UNAUTHENTICATED_BY_DESIGN) {
                    offenders +=
                        "${handler.where}: mutating handler declares NO @RequiredScope " +
                        "(if it is unauthenticated by design, add it to UNAUTHENTICATED_BY_DESIGN with its permitAll justification)"
                }
            } else if (operation.minScope == Scope.READ && handler.where !in READ_FLOORED_MUTATION_HANDLERS) {
                offenders +=
                    "${handler.where}: mutating handler is floored at read via ${operation.name}"
            }
        }
        offenders shouldBe emptyList()
    }

    /**
     * Non-vacuity: every allowlist entry must be a REAL, currently read-floored mutating
     * handler. An entry whose handler was raised above `read`, renamed, or deleted
     * exempts nothing — and silently rotting entries are how the operation-keyed
     * allowlist came to certify a lie, so they fail here.
     */
    @Test
    fun `every allowlist entry is a discovered read-floored mutating handler`() {
        val readFloored =
            discoveredMutatingHandlers()
                .filter { it.operation?.minScope == Scope.READ }
                .map { it.where }
                .toSet()
        (READ_FLOORED_MUTATION_HANDLERS.keys - readFloored) shouldBe emptySet()
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
                .filter { it.operation == null }
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

    private data class DiscoveredHandler(
        val where: String,
        val operation: ScopeMatrix.RestOperation?,
        /** The handler's full path — class-level prefix plus the mapping's own — or null. */
        val path: String?,
    )

    private fun discoveredMutatingHandlers(): List<DiscoveredHandler> =
        allControllers().flatMap { controller ->
            val classOperation = controller.findAnnotation<RequiredScope>()?.value
            controller.functions
                .filter { mutatingMethodOf(it.javaMethod) != null }
                .map { fn ->
                    DiscoveredHandler(
                        where = "${controller.simpleName}#${fn.name}",
                        operation = fn.findAnnotation<RequiredScope>()?.value ?: classOperation,
                        path = pathOf(controller, fn.javaMethod),
                    )
                }
        }

    /**
     * The deliberate read-floored mutation handlers, `"ControllerClass#method"` → the
     * reason `read` is honest FOR THAT HANDLER. Adding an entry requires the same three
     * things the existing entries have: a §7.6 row, a KDoc on the
     * [ScopeMatrix.RestOperation] constant arguing why `read` is honest, and an
     * in-handler guard that is the REAL control for whoever the floor lets through —
     * plus this per-handler justification, because the operation's KDoc cannot know
     * which credential each of its handlers is reachable by.
     */
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

        val READ_FLOORED_MUTATION_HANDLERS: Map<String, String> =
            mapOf(
                "AuthController#createKey" to
                    "own-resource: issuance resolves the caller's own userId, and the §7.4 subset check in " +
                    "ApiKeyService.issue (a read key mints only read keys) is the real privilege guard — " +
                    "'any authenticated' IS the documented floor for managing one's own keys (§7.6)",
                "AuthController#revokeKey" to
                    "own-resource: revocation is scoped to the caller's own keys in SQL " +
                    "(ApiKeyService.revoke(keyId, caller.userId)); no payload-chosen target beyond the caller's own key",
                "ApiKeysPartialController#create" to
                    "the /partials twin of AuthController#createKey — same own-resource issuance, same §7.4 subset guard",
                "ApiKeysPartialController#revoke" to
                    "the /partials twin of AuthController#revokeKey — same own-resource, SQL-scoped revocation",
                "UserSettingsController#updateTheme" to
                    "own-resource: the write targets the caller's own user row (the handler resolves the caller's " +
                    "userId); there is no payload-chosen target",
                // Added at the 026 merge, not by either lane: 026 branched BEFORE this guard
                // existed (025 introduced it), so its new mutating handler met the guard for the
                // first time here — and the guard did its job by refusing it. That is the
                // parallel-lane failure this test is for.
                "UserSettingsController#changeOwnPassword" to
                    "session-only by construction, then own-resource. The read floor was NOT sufficient on its own: " +
                    "the handler now refuses any non-OIDC principal (auth.session.required), because a leaked " +
                    "read-scoped key could otherwise guess the owner's password here — unmetered, since changeOwn " +
                    "had no lockout — and on a hit rotate it into an interactive takeover. With the key class refused " +
                    "the floor is moot for the credential that could abuse it, and the CURRENT password is still " +
                    "verified (now lockout-counted) so a hijacked session cannot rotate it either",
                "WorkspacesUiController#switch" to
                    "session-only by construction (requireSessionPrincipal): an API key is refused before the session " +
                    "mint and a session carries CSRF, so the read floor is moot for the credential that could abuse it",
            )

        const val BASE_PACKAGE = "co.datapipelines.web"

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
                "WorkspacesUiController#join" to
                    "SHELL: joining changes the caller's own membership set — the switcher again",
                "WorkspacesUiController#addMember" to
                    "NO-JS: workspace administration is the recovery surface an operator reaches when the " +
                    "app is misbehaving, and it is a plain form POST end to end",
                "WorkspacesUiController#removeMember" to
                    "NO-JS: the sibling of addMember, same form, same banner",
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
