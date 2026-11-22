package co.datapipelines.web.pipelines

import co.datapipelines.application.endpoints.EndpointBodyContract
import co.datapipelines.application.endpoints.EndpointKeyBindingRepository
import co.datapipelines.application.endpoints.EndpointKeyService
import co.datapipelines.application.endpoints.EndpointMethod
import co.datapipelines.application.endpoints.EndpointPinValidator
import co.datapipelines.application.endpoints.EndpointPromotionImport
import co.datapipelines.application.endpoints.EndpointPublishService
import co.datapipelines.application.endpoints.EndpointVersionBody
import co.datapipelines.application.endpoints.PublishedEndpoint
import co.datapipelines.application.endpoints.PublishedEndpointRepository
import co.datapipelines.auth.ApiKeyRepository
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.typesystem.DatapipelinesException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Published endpoints inside a promotion batch (074, rest-api §19.5) — the sender's half and the
 * receiver's half in one place.
 *
 * It exists as a collaborator rather than as more constructor parameters on
 * [PromotionService] and [PromotionReceiveService] because the rules here are ONE subject: what
 * an endpoint entry means on the wire, which keys it needs, and what happens when they are
 * absent. Spreading that across the two promotion classes put three more collaborators on each
 * and left the "keys are environment-local" rule stated in two places.
 *
 * ## Keys are environment-local, so bindings travel by NAME
 *
 * `api_keys` is environment-local (metadata-db §5A): a dev key does not exist in prod, and a
 * promotion must never mint one — minting a credential as a side effect of a deploy is not a
 * thing this system does. So a binding names the key the target is expected to already have, and
 * a target missing it refuses the WHOLE batch before anything is pushed.
 *
 * ## #786 — what an entry is now
 *
 * An API is versioned: the sender reads each API's CURRENT version body (pipeline name, timeout, description) and the
 * wire is unchanged (owner ruling 2026-11-08 (3); exact-pin promotion is follow-up #879). The receiver creates an
 * absent API through [EndpointPromotionImport] — the receiver-only import that pins the target pipeline's CURRENT and
 * needs no authoring capability — never through the native authoring creation, which a receiver refuses by
 * construction.
 */
class EndpointPromotion(
    private val publishing: EndpointPublishService,
    /** #786 F.2 — the receiver-only import; constructed for this one collaborator, never a bean. */
    private val importer: EndpointPromotionImport,
    private val keys: EndpointKeyService,
    private val endpoints: PublishedEndpointRepository,
    private val bindings: EndpointKeyBindingRepository,
    private val apiKeys: ApiKeyRepository,
    private val pipelines: PipelineRepository,
    /** #1040 — the pinned version's body contract, read for a travelling method route's `request_body` mode. */
    private val pins: EndpointPinValidator,
) {
    // ---------------------------------------------------------------------------------------
    // Sender
    // ---------------------------------------------------------------------------------------

    /**
     * The endpoints published over [promotedPipelineNames], as batch entries.
     *
     * Scoped to the batch's pipelines rather than the whole workspace on purpose: promoting one
     * pipeline must not drag along endpoints over pipelines the operator did not select, which
     * would push URLs whose pipelines the target does not have.
     *
     * The bindings are the SOURCE WORKSPACE's only, filtered in the query (#199, the D11 rule).
     * Only the exact `path_pattern` is globally unique (V11); path trees may overlap, so a
     * neighbouring workspace may legitimately bind at a node of its own tree that equals one
     * of these patterns. Reading every workspace's rows and matching by node carried that
     * neighbour's key NAME into this batch — and on the target, [apply] would bind the target's
     * key of that name to this path, or [refuseIfKeysMissing] would refuse the whole batch for
     * a name this workspace never chose.
     *
     * A LEGACY row (#274 — a stored path today's grammar refuses; the target's would too) is
     * never an entry: [PublishedEndpointRepository.findByWorkspace] reads valid rows only. The
     * omission is not silent (#286): the rows over the promoted pipelines are counted and logged
     * — a count, never a path — so an operator knows the endpoint did not travel. The fix is the
     * console's: unpublish the legacy row and republish at a legal path.
     *
     * ## #1040 — a method-bearing route travels EXACTLY pinned, or the whole transfer is refused before sending
     *
     * A POST, PUT or DELETE route travels only to a receiver whose inventory advertises it ([capabilities] —
     * published-mutation-apis §7: "receiver-advertised support for method, request-body mode and method-specific binding
     * fields"); a receiver that advertises nothing (every build before #1040) would import it as GET or refuse it, so one
     * such route over the promoted pipelines throws [PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED] for the
     * entire batch, naming the sender's own routes, before anything is pushed. A travelling method entry carries its
     * exact pin — the API's current version's pipeline version and that version's body hash — its body mode and the
     * names of the keys holding an exact grant of its method on it; never GET tree [PromotionWire.EndpointEntry.bindings].
     * A GET entry is byte-identical to every earlier sender's (its exact pin is #879's).
     */
    fun entriesFor(
        workspaceId: UUID,
        promotedPipelineNames: List<String>,
        capabilities: PromotionWire.EndpointCapabilities? = null,
    ): List<PromotionWire.EndpointEntry> {
        val promoted = promotedPipelineNames.filter { pipelines.findByName(workspaceId, it) != null }.toSet()
        val omitted = endpoints.findLegacy(workspaceId).count { it.pipelineName in promoted }
        if (omitted > 0) {
            log.warn(
                "event=endpoint.promotion_legacy_omitted count={} message=\"legacy endpoint rows over the promoted " +
                    "pipelines are not in the batch: their stored path fails today's grammar (R-EP5); unpublish and " +
                    "republish at a legal path to promote them (issues #274, #286)\"",
                omitted,
            )
        }
        val travelling =
            endpoints.findByWorkspace(workspaceId).mapNotNull { endpoint ->
                // #786: the API's CURRENT version is what travels; an API with none has nothing to promote.
                endpoint.current
                    ?.body
                    ?.takeIf { it.pipelineName in promoted }
                    ?.let { endpoint to it }
            }
        val methodRoutes = travelling.filter { (endpoint, _) -> endpoint.method.isMutation }
        // A receiver that advertises nothing is refused before any pin is read: nothing about the routes matters then.
        val modes: Map<UUID, String> =
            capabilities
                ?.let { methodRoutes.associate { (endpoint, body) -> endpoint.id to contractOf(workspaceId, body) } }
                .orEmpty()
        refuseIfReceiverIncapable(methodRoutes.map { it.first }, modes, capabilities)
        val allBindings = bindings.findByWorkspace(workspaceId)
        val keyNames = { ids: List<String> -> ids.mapNotNull { apiKeys.findById(it)?.name }.distinct().sorted() }
        return travelling.map { (endpoint, body) ->
            if (!endpoint.method.isMutation) {
                // GET node grants only (#1037): the wire's `bindings` are GET tree grants by construction — the
                // receiver binds each as one. A mutation grant at the same pattern belongs to its own route entry.
                PromotionWire.EndpointEntry(
                    path = endpoint.pathPattern,
                    pipeline = body.pipelineName,
                    timeoutSeconds = body.timeoutSeconds,
                    description = body.description,
                    bindings =
                        keyNames(
                            allBindings
                                .filter { it.method == EndpointMethod.GET && it.pathPrefix == endpoint.pathPattern }
                                .map { it.apiKeyId },
                        ),
                )
            } else {
                PromotionWire.EndpointEntry(
                    path = endpoint.pathPattern,
                    pipeline = body.pipelineName,
                    timeoutSeconds = body.timeoutSeconds,
                    description = body.description,
                    method = endpoint.method.name,
                    pipelineVersion = body.pipelineVersion,
                    pipelineBodyHash = pinnedHash(workspaceId, body),
                    requestBody = modes.getValue(endpoint.id),
                    grants = keyNames(allBindings.filter { it.endpointId == endpoint.id }.map { it.apiKeyId }),
                )
            }
        }
    }

    /** The pinned version's body mode, as the wire spells it (an unreadable pin refuses: it cannot travel exactly). */
    private fun contractOf(
        workspaceId: UUID,
        body: EndpointVersionBody,
    ): String = (pins.bodyContract(workspaceId, body.pipelineName, body.pipelineVersion) ?: throw pinGone(body)).mode.wire

    /** The sender's body hash of the exact pinned pipeline version. */
    private fun pinnedHash(
        workspaceId: UUID,
        body: EndpointVersionBody,
    ): String {
        val record = pipelines.findByName(workspaceId, body.pipelineName) ?: throw pinGone(body)
        return pipelines.findVersionDetail(workspaceId, record.id, body.pipelineVersion)?.bodyHash ?: throw pinGone(body)
    }

    private fun pinGone(body: EndpointVersionBody) =
        DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND,
            message = "'${body.pipelineName}' has no live version ${body.pipelineVersion} to promote exactly.",
            details = mapOf("pipeline" to body.pipelineName, "pipeline_version" to body.pipelineVersion),
        )

    /** The sender's preflight: every travelling method route must be one the receiver advertises it imports exactly. */
    private fun refuseIfReceiverIncapable(
        methodRoutes: List<PublishedEndpoint>,
        modes: Map<UUID, String>,
        capabilities: PromotionWire.EndpointCapabilities?,
    ) {
        val refused =
            methodRoutes.filterNot { endpoint ->
                val mode = modes[endpoint.id]
                capabilities != null && mode != null && capabilities.admits(endpoint.method.name, mode)
            }
        if (refused.isEmpty()) return
        val sorted = refused.sortedWith(compareBy({ it.pathPattern }, { it.method }))
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED,
            message =
                "This batch would promote API route(s) ${sorted.joinToString(", ") { "${it.method.name} ${it.pathPattern}" }}, " +
                    "which the target does not advertise it can import exactly (method, exact pipeline pin, request-body " +
                    "mode and method grants). Nothing was sent. Upgrade the target, or unpublish the route before promoting.",
            details =
                mapOf(
                    "unsupported_routes" to sorted.map { mapOf("method" to it.method.name, "path" to it.pathPattern) },
                    "target_advertises" to (capabilities != null),
                ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Receiver
    // ---------------------------------------------------------------------------------------

    /**
     * Refuses the whole batch when a method-bearing entry cannot be imported exactly (#1037, #1040) — before anything is
     * applied, reported once for the batch like [refuseIfKeysMissing]. A method this deployment cannot publish (PATCH, a
     * typo), a method entry without its exact pin, hash or a body mode this build reads, and a method entry that carries
     * GET tree `bindings` are each refused: importing any of them would reinterpret the route (as GET, on another pin,
     * or with a GET grant beside its method). An absent method and an explicit `GET` are the legacy entry and pass.
     */
    fun refuseIfMethodUnsupported(entries: List<PromotionWire.EndpointEntry>) {
        val unsupported =
            entries.filter { entry ->
                val method = entry.method?.let { EndpointMethod.of(it) }
                when {
                    entry.method == null -> false
                    method == null -> true
                    !method.isMutation -> false
                    else -> !isExactMethodEntry(entry)
                }
            }
        if (unsupported.isEmpty()) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PROMOTION_METHOD_UNSUPPORTED,
            message =
                "This batch carries ${unsupported.size} API route(s) that cannot be imported exactly: a method-bearing " +
                    "route needs one of POST, PUT or DELETE, its exact pipeline version and hash, a known request-body mode " +
                    "and no GET bindings. Nothing was imported.",
            details = mapOf("unsupported_routes" to unsupported.map { mapOf("path" to it.path) }),
        )
    }

    private fun isExactMethodEntry(entry: PromotionWire.EndpointEntry): Boolean =
        entry.pipelineVersion != null &&
            !entry.pipelineBodyHash.isNullOrBlank() &&
            EndpointBodyContract.Mode.ofWire(entry.requestBody) != null &&
            entry.bindings.isEmpty()

    /**
     * Refuses the whole batch when it binds a key name this deployment does not have — a GET entry's `bindings` and a
     * method entry's `grants` alike (#1040).
     *
     * Reported ONCE for the batch, naming every absent key, BEFORE anything is pushed — the same
     * shape and the same reason as `pipeline.promotion.missing_datasources` (§10.5): a mid-batch
     * failure would leave the target half-promoted, with URLs nobody can call.
     */
    fun refuseIfKeysMissing(
        entries: List<PromotionWire.EndpointEntry>,
        workspaceId: UUID,
        workspaceName: String,
    ) {
        val missing =
            entries
                .flatMap { it.bindings + it.grants.orEmpty() }
                .distinct()
                .filter { apiKeys.findByWorkspaceAndName(workspaceId, it).isEmpty() }
        if (missing.isEmpty()) return
        throw DatapipelinesException(
            code = PipelineErrorCodes.Endpoint.PROMOTION_KEY_MISSING,
            message =
                "This batch binds API key(s) ${missing.joinToString(", ")}, which do not exist in workspace " +
                    "'$workspaceName'. Keys are environment-local and a promotion never mints one — create " +
                    "them on this deployment first.",
            details = mapOf("missing_api_keys" to missing, "workspace" to workspaceName),
        )
    }

    /**
     * Imports one promoted endpoint and binds its keys.
     *
     * A GET entry (the legacy shape): an existing path is skipped rather than refused, so a re-push is idempotent like
     * every other entry in the batch; an absent one is created by the receiver-only import (#786 F.2) — v1 pins the
     * target pipeline's CURRENT, RELEASED iff that version is — and its `bindings` become GET tree grants.
     *
     * A method entry (#1040, already validated by [refuseIfMethodUnsupported]): the route at (path, method) is imported
     * on the sender's EXACT pin ([EndpointPromotionImport.importExact] — absent: created; same pin: unchanged; another
     * pin: a new RELEASED version made current), and each `grants` name becomes an exact grant of that method on that
     * one route. Both in the receiver's one transaction, so a later failure rolls the entity, its version and its grants
     * back with the batch.
     */
    fun apply(
        entry: PromotionWire.EndpointEntry,
        promoter: AuthenticatedPrincipal,
    ) {
        val workspaceId = promoter.requireWorkspace().id
        val method = entry.method?.let { EndpointMethod.of(it) } ?: EndpointMethod.GET
        if (method.isMutation) {
            importer.importExact(
                workspaceId = workspaceId,
                actor = promoter.userId,
                pathPattern = entry.path,
                method = method,
                pin =
                    EndpointPromotionImport.ExactPin(
                        pipelineName = entry.pipeline,
                        pipelineVersion = requireNotNull(entry.pipelineVersion) { "validated by refuseIfMethodUnsupported" },
                        pipelineBodyHash = requireNotNull(entry.pipelineBodyHash) { "validated by refuseIfMethodUnsupported" },
                        timeoutSeconds = entry.timeoutSeconds,
                        description = entry.description,
                    ),
            )
            bindByName(entry.grants.orEmpty(), workspaceId) { keyId -> keys.bind(promoter, keyId, entry.path, method) }
            return
        }
        if (publishing.get(promoter, entry.path) == null) {
            importer.importPromoted(
                workspaceId = workspaceId,
                actor = promoter.userId,
                pathPattern = entry.path,
                pipelineName = entry.pipeline,
                timeoutSeconds = entry.timeoutSeconds,
                description = entry.description,
            )
        }
        bindByName(entry.bindings, workspaceId) { keyId -> keys.bind(promoter, keyId, entry.path) }
    }

    /**
     * Binds every key named in [names] — proven to exist by [refuseIfKeysMissing]. An ambiguous name — several keys of
     * that name in one workspace, which the schema permits — binds ALL of them: the batch asked for "the key called X",
     * and picking one would be a guess about whose credential to widen.
     */
    private fun bindByName(
        names: List<String>,
        workspaceId: UUID,
        bind: (String) -> Unit,
    ) {
        names.forEach { keyName -> apiKeys.findByWorkspaceAndName(workspaceId, keyName).forEach { key -> bind(key.id) } }
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(EndpointPromotion::class.java)

        /**
         * #1040 — what this build's receiver half imports of a method-bearing route, advertised in its inventory
         * ([PromotionWire.Inventory.endpointCapabilities]): every mutation method, exactly pinned, every body mode,
         * with exact method grants.
         */
        val CAPABILITIES: PromotionWire.EndpointCapabilities =
            PromotionWire.EndpointCapabilities(
                methods = EndpointMethod.ALL.filter { it.isMutation }.map { it.name },
                exactPin = true,
                requestBodyModes = EndpointBodyContract.Mode.entries.map { it.wire },
                methodGrants = true,
            )
    }
}
