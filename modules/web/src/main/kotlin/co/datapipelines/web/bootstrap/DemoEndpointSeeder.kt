package co.datapipelines.web.bootstrap

import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKey
import co.datapipelines.auth.ApiKeyKind
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.ApiKeyService
import co.datapipelines.auth.AuthMethod
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.DemoWorkspaceSeeder
import co.datapipelines.auth.SecretHasher
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.auth.WorkspaceRole
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.web.config.EndpointsProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import java.util.UUID

/**
 * The demo workspace's public API (#224): after the example content has landed, publishes one
 * endpoint per seeded demo pipeline, mints the ONE configured `api_caller` key, and binds it to
 * every published demo path — so a reader of the site can call the demo API without an account.
 *
 * ## When it runs, and why every boot
 * A `SmartInitializingSingleton`, `@DependsOn` the demo workspace startup: on a FRESH database
 * the examples were imported a moment earlier (content seeding runs when `DemoWorkspaceSeeder`
 * creates `demo`), and the pipelines to publish already exist. Unlike the content seeding —
 * once per deployment, at creation — THIS step runs on every boot by design: the rotation is
 * "change the configured plaintext, restart", and a changed pipeline set must add and retire
 * paths. Everything below is idempotent, so a boot that changes nothing is a boot that logs
 * one line.
 *
 * ## The identity of "mine"
 * The step manages exactly three kinds of row, and only ever its own:
 * - published endpoints whose path it DERIVES from a seeded pipeline's name (below) — an
 *   endpoint at a wanted path left for an operator's different pipeline is logged, never
 *   replaced;
 * - endpoints it previously published, recognised by the demo category plus `created_by` being
 *   the SYSTEM actor (the same actor the examples are attributed to) — a super admin's
 *   hand-published `/demo/...` row is never retired by a restart;
 * - the live `endpoint`-kind keys NAMED [MANAGED_NAME] in the demo workspace. The name is the
 *   special, recognisable one the Keys page shows ("demo-public-key (API key)"); if an operator
 *   minted a second key of that name, the oldest is the managed one and the rest are left alone
 *   with a WARN.
 *
 * ## The path mapping
 * A pipeline name's segments become the endpoint path's segments under the demo category:
 * `nyc/mobility/taxi_vs_rideshare` → `/demo/nyc/mobility/taxi-vs-rideshare` — the first name
 * segment is the R-EP5 version segment, underscores fold to hyphens in the path (the path
 * grammar's lowercase alphabet has both, and URLs read better hyphenated), and `demo` is a
 * category no rule reserves. One mapping, stated once here; the site renders the same paths
 * from the same rule.
 *
 * ## The lake family (B5)
 * A pipeline whose examples file's `requires_datasources` gate names a visible LAKE-dialect
 * datasource pays S3 egress on every call: its endpoint is published and bound only when the
 * per-key request budget is ACTIVE (`max-requests > 0`). The budget ships on, so the lake
 * family publishes by default; an operator who turns the budget off gets the lake endpoints
 * retired on the next boot, not unbounded public S3 reads.
 *
 * ## The principal
 * The SYSTEM service account (auth.md §4.5) — the same actor the example content and every
 * automated write are attributed to, so `created_by` on a seeded endpoint names a row a reader
 * can find. The principal's `superAdmin` stamp is a statement about the ACT (the system
 * provisioning what the product ships, exactly as [LakeBootstrapSeeder] stamps it), not about
 * the row: issuance's `requireIssuancePermission` resolves the system actor's authority through
 * it, and every audit row carries the system actor's own user id.
 *
 * The constructor is one wiring surface — every parameter is a distinct collaborator the
 * callback reconciles against, the same shape EndpointPublishService's composition root has.
 */
@Suppress("LongParameterList")
class DemoEndpointSeeder(
    private val properties: BootstrapProperties,
    private val endpointsProperties: EndpointsProperties,
    private val examples: ExampleContentSeeder,
    private val demoWorkspaceSeeder: DemoWorkspaceSeeder,
    private val pipelineRepository: PipelineRepository,
    private val publishService: EndpointPublishService,
    private val keyService: EndpointKeyService,
    private val bindingRepository: EndpointKeyBindingRepository,
    private val endpointRepository: PublishedEndpointRepository,
    private val keyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
    private val secretHasher: SecretHasher,
    private val users: UserService,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(DemoEndpointSeeder::class.java)

    override fun afterSingletonsInstantiated() {
        // The preconditions are THE SYSTEM ACTOR and THE WORKSPACE WITH ITS CONTENT, and they
        // are ENSURED here, not hoped for: SmartInitializingSingleton callbacks fire in
        // bean-registration order, so this seeder's callback can run before SystemActorSeeder's
        // and `DemoWorkspaceStartup`'s on a fresh database. Both ensures are idempotent across
        // restarts BY DESIGN — provisioning is create-if-absent, and `ensureDemoWorkspace()`
        // is a no-op row read once the row exists. When the other startups ran first, both
        // calls are no-ops; when this runs first, they do the work this seeder's own
        // precondition needs. The @DependsOn on the bean stays as the declared intent; these
        // calls are the guarantee.
        val system = users.provisionSystemActor()
        val demo = demoWorkspaceSeeder.ensureDemoWorkspace()
        if (demo == null || !demo.isActive) {
            log.info(
                "event=demo.api_key_skipped workspace={} message=\"no active '{}' workspace; nothing published\"",
                demo?.name ?: "absent",
                DemoWorkspaceSeeder.DEMO_WORKSPACE,
            )
            return
        }
        val actor = bootstrapPrincipal(system, demo.id, demo.name)
        val plaintext = properties.demoApiKey

        if (plaintext.isNullOrBlank()) {
            // The kill switch: no configured key means no public demo API. Rows a previous boot
            // published are RETRACTED — an operator blanking the setting must not keep a live
            // public key and bound endpoints a restart forgot to remove.
            val retired = retireStaleEndpoints(system, demo.id, actor.userId, wantedPaths = emptySet())
            val revoked = revokeManagedKeys(demo.id, actor.userId)
            log.info("event=demo.api_key_unset workspace={} retired={} revoked={}", demo.name, retired, revoked)
            return
        }

        val wanted = wantedEndpoints(demo.id)
        val wantedPaths = wanted.map { it.path }.toSet()

        if (wantedPaths.isEmpty()) {
            // No demo endpoints to serve (a hardened deployment, or no family seeded anything):
            // the managed key is then RETRACTED, not minted bound to nothing. The invariant the
            // restart discipline keeps is "the public key exists IFF demo endpoints exist".
            val retired = retireStaleEndpoints(system, demo.id, actor.userId, wantedPaths = emptySet())
            val revoked = revokeManagedKeys(demo.id, actor.userId)
            log.info(
                "event=demo.api_key_no_endpoints workspace={} retired={} revoked={}",
                demo.name,
                retired,
                revoked,
            )
            return
        }

        val published = publishWanted(system, demo.id, wanted)
        val retired = retireStaleEndpoints(system, demo.id, actor.userId, wantedPaths)

        val keyId = reconcileKey(demo.id, actor, plaintext, wantedPaths)
        log.info(
            "event=demo.api_key_seeded workspace={} paths={} published={} retired={} key_id={}",
            demo.name,
            wantedPaths.size,
            published,
            retired,
            keyId ?: "none",
        )
    }

    /**
     * The managed key, reconciled to [wantedPaths]. A live key of the managed name whose
     * configured id AND hash both match is the idempotent case: only its bindings move. Any
     * OTHER live managed key is revoked (the rotation), and the configured plaintext is minted
     * bound to [wantedPaths]. A revoked row already holding the configured id stays revoked —
     * an operator who revoked the public key on purpose is not overridden by a restart, and the
     * mint could not reuse the id anyway (the primary key holds it).
     *
     * @return the live managed key's id, or null when none was minted or found.
     */
    private fun reconcileKey(
        demoId: UUID,
        actor: AuthenticatedPrincipal,
        plaintext: String,
        wantedPaths: Set<String>,
    ): String? {
        val configuredId = plaintext.substringBefore('.')
        val live = liveManagedKeys(demoId)
        val matching = live.firstOrNull { it.id == configuredId && secretHasher.verify(it.keyHash, plaintext) }
        if (matching != null) {
            reconcileBindings(actor, matching.id, wantedPaths)
            warnAboutUnmanaged(live, matching)
            return matching.id
        }

        // Rotation (or first mint): the old managed keys go, whatever they are bound to — their
        // bindings are the seeder's own rows, and a revoked key authorises nothing.
        live.forEach { key ->
            val revoked = apiKeyService.revokeWorkspaceEndpointKey(key.id, demoId, actor.userId)
            log.info(
                "event=demo.api_key_revoked key_id={} removed={} message=\"replaced by the configured demo key\"",
                key.id,
                revoked,
            )
        }

        val sittingAtConfiguredId = keyRepository.findById(configuredId)
        if (sittingAtConfiguredId != null) {
            log.warn(
                "event=demo.api_key_revoked_permanent key_id={} message=\"the configured demo key's id is taken by " +
                    "a revoked key; it stays revoked and nothing is re-minted — set a new value to rotate\"",
            )
            return null
        }

        val issued =
            keyService.issue(
                principal = actor,
                name = MANAGED_NAME,
                role = null,
                kind = ApiKeyKind.ENDPOINT,
                bindingPaths = wantedPaths.toList(),
                expiresAt = null,
                plaintext = plaintext,
            )
        return issued.record.id
    }

    /**
     * The wanted set: every seeded pipeline the gate admits that actually exists in the
     * workspace (a file whose gate skipped at creation left no pipeline to publish), minus the
     * lake-backed ones when no budget stands behind them (B5).
     */
    private fun wantedEndpoints(demoId: UUID): List<Wanted> {
        val lakeGated = !endpointsProperties.keyRequestBudgetActive
        return examples
            .seedablePipelines(demoId)
            .filterNot { lakeGated && it.lakeBacked }
            .mapNotNull { seedable ->
                val pipeline = pipelineRepository.findByName(demoId, seedable.name)
                if (pipeline == null) {
                    log.warn(
                        "event=demo.pipeline_absent pipeline={} message=\"the examples file names it but " +
                            "no such pipeline exists in the workspace; no endpoint published\"",
                        seedable.name,
                    )
                    null
                } else {
                    Wanted(name = seedable.name, pipelineId = pipeline.id, path = DemoEndpointPaths.pathFor(seedable.name))
                }
            }
    }

    /**
     * Publishes the wanted set, leaving alone whatever is already right. Returns how many rows
     * this boot published. An existing endpoint at a wanted path held by a DIFFERENT pipeline
     * (a super admin's publish, or another workspace's row) is left alone with a WARN — never
     * replaced silently, and never loudly enough to fail a boot over a URL an operator chose.
     */
    private fun publishWanted(
        system: co.datapipelines.auth.User,
        demoId: UUID,
        wanted: List<Wanted>,
    ): Int {
        var published = 0
        wanted.forEach { target ->
            val existing = endpointRepository.findByPath(target.path)
            when {
                existing == null -> {
                    publishService.publish(
                        bootstrapPrincipal(system, demoId, DemoWorkspaceSeeder.DEMO_WORKSPACE),
                        target.path,
                        target.name,
                        timeoutSeconds = null,
                        description = DESCRIPTION,
                    )
                    published++
                }

                existing.workspaceId == demoId && existing.pipelineId == target.pipelineId -> {
                    // Already ours, already right: the idempotent boot's most common branch.
                }

                else -> {
                    log.warn(
                        "event=demo.path_taken path={} pipeline={} message=\"an existing endpoint holds this " +
                            "path; the demo endpoint for '{}' is not published\"",
                        target.path,
                        target.name,
                        target.name,
                    )
                }
            }
        }
        return published
    }

    /** The managed key's bindings forced to exactly [wantedPaths]: missing ones bound, stale ones unbound. */
    private fun reconcileBindings(
        actor: AuthenticatedPrincipal,
        keyId: String,
        wantedPaths: Set<String>,
    ) {
        val existing = bindingRepository.findByKey(keyId).map { it.pathPrefix }.toSet()
        (wantedPaths - existing).forEach { prefix -> keyService.bind(actor, keyId, prefix) }
        (existing - wantedPaths).forEach { prefix -> keyService.unbind(actor, keyId, prefix) }
    }

    /** Live `endpoint`-kind keys of the managed name, oldest first — the managed row is the first. */
    private fun liveManagedKeys(demoId: UUID) =
        keyRepository
            .findByWorkspaceAndName(demoId, MANAGED_NAME)
            .filter { it.kind == ApiKeyKind.ENDPOINT }
            .sortedBy { it.createdAt }

    /** Revokes every live managed key in the workspace; returns how many went. */
    private fun revokeManagedKeys(
        demoId: UUID,
        actorId: UUID,
    ): Int {
        val live = liveManagedKeys(demoId)
        live.forEach { key ->
            val revoked = apiKeyService.revokeWorkspaceEndpointKey(key.id, demoId, actorId)
            log.info(
                "event=demo.api_key_revoked key_id={} removed={} message=\"the demo API is off (no key configured)\"",
                key.id,
                revoked,
            )
        }
        return live.size
    }

    /** A second live key of the managed name is an operator's row; name it, never touch it. */
    private fun warnAboutUnmanaged(
        live: List<ApiKey>,
        managed: ApiKey,
    ) {
        live.drop(1).forEach { extra ->
            log.warn(
                "event=demo.api_key_unmanaged key_id={} created_at={} message=\"a second live endpoint key is named " +
                    "'{}'; the seeder manages {} only\"",
                extra.id,
                extra.createdAt,
                MANAGED_NAME,
                managed.id,
            )
        }
    }

    /**
     * Unpublishes the step's own prior rows that the wanted set no longer names: endpoints of
     * the demo workspace at a `/demo/...` path whose `created_by` is [systemActorId] and whose
     * path is not wanted. Returns how many rows went.
     */
    private fun retireStaleEndpoints(
        system: co.datapipelines.auth.User,
        demoId: UUID,
        systemActorId: UUID,
        wantedPaths: Set<String>,
    ): Int {
        val stale =
            endpointRepository
                .findByWorkspace(demoId)
                .filter { it.pathPattern.startsWith(DEMO_CATEGORY_PREFIX) }
                .filter { it.createdBy == systemActorId }
                .filter { it.pathPattern !in wantedPaths }
        stale.forEach { endpoint ->
            val removed = publishService.unpublish(actorForRetirement(system, demoId), endpoint.pathPattern)
            log.info(
                "event=demo.endpoint_retired path={} removed={} pipeline_id={}",
                endpoint.pathPattern,
                removed,
                endpoint.pipelineId,
            )
        }
        return stale.size
    }

    /**
     * The system actor as the acting principal for [demoId]: the row's own id in the audit
     * trail, super admin as the statement of the act (see the class KDoc), and the demo
     * workspace as the resolved context the services' `requireWorkspace()` reads.
     */
    private fun bootstrapPrincipal(
        system: co.datapipelines.auth.User,
        demoId: UUID,
        demoName: String,
    ): AuthenticatedPrincipal =
        AuthenticatedPrincipal(
            userId = system.id,
            email = system.email,
            displayName = system.displayName,
            authMethod = AuthMethod.OIDC,
            superAdmin = true,
            workspaceName = demoName,
            workspace = WorkspaceContext(demoId, demoName, WorkspaceRole.VIEWER, superAdmin = true, implicit = true),
        )

    /** The retirement twin of [bootstrapPrincipal] — the same statement, rebuilt per call. */
    private fun actorForRetirement(
        system: co.datapipelines.auth.User,
        demoId: UUID,
    ): AuthenticatedPrincipal = bootstrapPrincipal(system, demoId, DemoWorkspaceSeeder.DEMO_WORKSPACE)

    /** One pipeline the boot wants served: its name, its workspace id, its derived path. */
    private data class Wanted(
        val name: String,
        val pipelineId: UUID,
        val path: String,
    )

    companion object {
        /** B2 — the special, recognisable name; the Keys page shows `demo-public-key (API key)`. */
        const val MANAGED_NAME = "demo-public-key"

        /** The stored path prefix of every endpoint this step manages. */
        const val DEMO_CATEGORY_PREFIX = "/demo/"

        /** What a seeded endpoint's description says — the same sentence on every family. */
        const val DESCRIPTION = "Seeded demo endpoint: serves this pipeline's released version, read-only."
    }
}
