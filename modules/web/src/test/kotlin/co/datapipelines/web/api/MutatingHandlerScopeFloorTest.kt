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
import org.springframework.web.bind.annotation.RestController
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.jvm.javaMethod

/**
 * The mutating-floor guard (025 defect round, A1): every POST/PUT/PATCH/DELETE handler
 * must carry an operation whose §7.6 floor sits ABOVE `read` — unless the operation is on
 * the explicit allowlist below, each entry justified in place.
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
 * The allowlist is operations, not paths: the enum is the §7.6 unit, and every handler
 * declaring an allowlisted operation is covered by that operation's justification.
 *
 * Born red: `PATCH /partials/profile/theme` mutated behind `READ_RESOURCES` until it got
 * its own `PROFILE_PREFERENCE` row (same commit as this test).
 */
class MutatingHandlerScopeFloorTest {
    @Test
    fun `every mutating handler sits above the read floor or is allowlisted`() {
        val offenders = mutableListOf<String>()
        allControllers().forEach { controller ->
            val classOperation = controller.findAnnotation<RequiredScope>()?.value
            controller.functions
                .filter { mutatingMethodOf(it.javaMethod) != null }
                .forEach { fn ->
                    val operation = fn.findAnnotation<RequiredScope>()?.value ?: classOperation
                    val where = "${controller.simpleName}#${fn.name}"
                    if (operation == null) {
                        offenders += "$where: mutating handler declares NO @RequiredScope"
                    } else if (operation.minScope == Scope.READ && operation !in READ_FLOORED_MUTATIONS) {
                        offenders +=
                            "$where: mutating handler is floored at read via ${operation.name}"
                    }
                }
        }
        offenders shouldBe emptyList()
    }

    /** The scan sees the module's controllers — an empty scan would prove nothing. */
    @Test
    fun `the scan finds the module's controllers`() {
        allControllers() shouldHaveAtLeastSize 15
        allControllers().map { it.qualifiedName.orEmpty() } shouldContain
            "co.datapipelines.web.ui.WorkspacesUiController"
    }

    /**
     * The deliberate read-floored mutations, each with its reason. Adding an entry here
     * requires the same three things the existing entries have: a §7.6 row, a KDoc on the
     * [ScopeMatrix.RestOperation] constant arguing why `read` is honest, and an in-handler
     * guard that is the REAL control for whoever the floor lets through.
     */
    private companion object {
        /**
         * Own-resource mutations: the handler resolves the caller's own userId; the §7.4
         * subset check in `ApiKeyService.issue` is the real privilege guard (a read key
         * mints only read keys), revocation is scoped to the caller's own keys in SQL,
         * and the theme write has no payload-chosen target.
         */
        val MANAGE_OWN_API_KEYS = ScopeMatrix.RestOperation.MANAGE_OWN_API_KEYS
        val PROFILE_PREFERENCE = ScopeMatrix.RestOperation.PROFILE_PREFERENCE

        /**
         * The one POST on WORKSPACES_READ is `/workspace/switch`, which is session-only
         * (`requireSessionPrincipal()`, 96240ed) — an API key cannot reach it at all and a
         * session carries CSRF; the floor is moot for the credential that could abuse it.
         */
        val WORKSPACES_READ = ScopeMatrix.RestOperation.WORKSPACES_READ

        /**
         * "Any authenticated may REACH it; role/mode decides": creation is refused per
         * provisioning mode in-handler (`closed` → non-admin 403), and every management
         * path is owner-or-admin in `WorkspaceService` (the no-oracle 403). A key acts as
         * its creator's userId, so an owner's key exercising owner rights is the
         * documented key-as-actor model.
         */
        val WORKSPACE_CREATE = ScopeMatrix.RestOperation.WORKSPACE_CREATE
        val MANAGE_WORKSPACE = ScopeMatrix.RestOperation.MANAGE_WORKSPACE

        val READ_FLOORED_MUTATIONS: Set<ScopeMatrix.RestOperation> =
            setOf(MANAGE_OWN_API_KEYS, PROFILE_PREFERENCE, WORKSPACES_READ, WORKSPACE_CREATE, MANAGE_WORKSPACE)

        const val BASE_PACKAGE = "co.datapipelines.web"

        /** The mutating HTTP verb annotation a handler carries, or null for read verbs. */
        fun mutatingMethodOf(method: java.lang.reflect.Method?): String? {
            if (method == null) return null
            return listOf(
                PostMapping::class.java to "POST",
                PutMapping::class.java to "PUT",
                PatchMapping::class.java to "PATCH",
                DeleteMapping::class.java to "DELETE",
            ).firstOrNull { method.getAnnotation(it.first) != null }?.second
        }
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
