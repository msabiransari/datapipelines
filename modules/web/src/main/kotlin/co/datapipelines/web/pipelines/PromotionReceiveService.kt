package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate

/**
 * The RECEIVER applying one promotion batch (versioning §10.4, rest-api §18.2).
 *
 * ## One unit, or nothing
 * §10.4: "a batch is one unit — the receiver applies it in one transaction or not at all".
 * The alternative is the failure mode the template library import documents and accepts for
 * an interactive, re-runnable action: a mid-batch failure leaves earlier entries stored.
 * Promotion is not that. A batch is a dependency closure — templates a pipeline pins, children
 * a parent runs — so a partial apply leaves the receiver holding pipelines whose pins do not
 * resolve, which is worse than holding nothing.
 *
 * The transaction is an explicit `TransactionTemplate` over the **`transactionManager`** bean
 * (since 056 the explicitly declared `metadataTransactionManager` for the metadata `DataSource`;
 * named here because this is the first transaction boundary in the codebase and an unnamed
 * "the" manager is a claim nobody can check). Both import services write through the SAME
 * `NamedParameterJdbcTemplate` on that `DataSource`, so they enlist in this transaction
 * without knowing it exists.
 *
 * **Pointer for 056 (R6, service layer slice A):** this boundary belongs on `PipelineService`
 * once that exists. When it lands, this class should delegate rather than own a
 * `TransactionTemplate` of its own — the demarcation moves, the all-or-nothing semantics do
 * not.
 *
 * ## Order comes from the sender
 * The batch arrives already in §10.4 order and is applied in the order given. The receiver
 * does not re-derive the closure: the sender owns that rule, and a second implementation of
 * it here would be a second thing to keep correct.
 *
 * ## Guards
 * `target_is_authoring` is checked BEFORE the transaction opens: dev is where drafts live, and
 * a deployment that authors must not receive promoted content (D7). Everything after that is
 * §9.2's preserved-version import table, unchanged — conflict, idempotency, hash recompute —
 * because those semantics are right and promotion calls them rather than restating them.
 */
class PromotionReceiveService(
    private val inventory: PromotionInventoryService,
    private val pipelineImportService: PipelineImportService,
    private val templateImportService: TemplateImportService,
    private val auditLogger: AuditLogger,
    private val transactionTemplate: TransactionTemplate,
    private val authoringEnabled: Boolean,
    /** 074 — published endpoints ride the batch; the rules live in one collaborator. */
    private val endpointPromotion: EndpointPromotion,
    /** 140 — the receiver's release-check gate (§10.5): the one runner, shared with every surface. */
    private val checkRunner: co.datapipelines.application.checks.PipelineCheckRunner,
) {
    private val log = LoggerFactory.getLogger(PromotionReceiveService::class.java)

    /**
     * Applies [batch] as [peer] — the principal `PromotionServerKeyFilter` authenticated. Every
     * received row and the audit row are attributed to [peer]'s user (#215 record C4): a stored
     * server key's `service` identity, or the System actor for the deprecated config value, which
     * has no row of its own (B6).
     */
    fun apply(
        batch: PromotionWire.Batch,
        peer: AuthenticatedPrincipal,
    ): PromotionWire.Applied {
        refuseIfAuthoring()
        val workspace = inventory.contextFor(batch.workspace)
        val actor = peer.userId
        // The batch's target workspace is stamped on the principal handed to
        // [EndpointPromotion.apply] as an ARGUMENT — never installed in the security context:
        // the import path reads no ambient principal since 134. Its authority stays the peer's
        // key role, `promotion_receiver` (#215 record §3.2).
        val promoter = peer.copy(workspaceName = workspace.name, workspace = workspace)

        // The import services take the TARGET workspace explicitly, and since 134 so does
        // everything beneath them: the datasource port `PipelineValidator` resolves a node's
        // `source` through takes the workspace as an ARGUMENT, so a workspace-BOUND datasource
        // on the receiver resolves for the batch's workspace. Before 134 that port
        // read the ACTIVE workspace off the thread-local principal — which the promotion
        // credential does not pin — and this block stamped the batch's workspace onto the
        // principal for the duration of the import; found by the two-deployment E2E, which
        // registers its datasource workspace-bound and still guards this path.
        //
        // §10.5's discipline, applied to endpoint bindings: every key name the batch references
        // must already exist on this deployment, checked ONCE for the whole batch BEFORE anything
        // is pushed, so the target is left byte-unchanged rather than failing mid-batch. Keys are
        // environment-local — a promotion must never mint one.
        endpointPromotion.refuseIfKeysMissing(batch.endpoints, workspace.id, batch.workspace)

        // 140 §10.5 — the receiver's release-check gate, BEFORE the import transaction: a
        // promoted release's checks travel in its body, and the receiver re-runs them on its
        // OWN datasources at its release step — the same gate a human release faces, with no
        // override channel. It runs outside the transaction (the probes open
        // customer-datasource connections, which `ConnectionLease` refuses while a metadata
        // transaction is open on the thread — `datasource.lease_in_transaction`), and it is
        // deliberately NOT persisted: the pipelines do not exist on the receiver yet, so no
        // `pipeline_check_runs` row can key to them; the receiver's first persisted run is
        // the first one commissioned after the batch lands.
        gateChecks(batch, workspace.id, actor)

        transactionTemplate.executeWithoutResult {
            if (batch.templates.isNotEmpty()) {
                templateImportService.import(templatesPayload(batch), workspace.id, actor)
            }
            batch.pipelines.forEach { pipeline ->
                pipelineImportService.import(pipeline.toString(), workspace.id, actor)
            }
            // AFTER the pipelines: an endpoint over a pipeline this same batch is bringing
            // must find it already stored. Republishing an unchanged endpoint is a no-op
            // rather than a conflict, so a re-push is idempotent like every other entry.
            batch.endpoints.forEach { entry -> endpointPromotion.apply(entry, promoter) }
        }

        // C4: the promoted rows are stamped with the peer's identity (the System actor for the
        // config value), and WHERE they came from is recorded here — the source deployment's name and a fingerprint of the key that
        // authorised the push. Never the key.
        auditLogger.log(
            event = AUDIT_ACCEPTED,
            userId = actor,
            details =
                mapOf(
                    "source_env" to batch.sourceEnv,
                    "key_fingerprint" to batch.keyFingerprint,
                    "workspace" to batch.workspace,
                    "templates" to batch.templates.size,
                    "pipelines" to batch.pipelines.size,
                    "endpoints" to batch.endpoints.size,
                ),
        )
        log.info(
            "event=$AUDIT_ACCEPTED source_env={} workspace={} templates={} pipelines={} actor={}",
            batch.sourceEnv,
            batch.workspace,
            batch.templates.size,
            batch.pipelines.size,
            actor,
        )
        return PromotionWire.Applied(
            workspace = batch.workspace,
            sourceEnv = batch.sourceEnv,
            templates = batch.templates.size,
            pipelines = batch.pipelines.size,
            endpoints = batch.endpoints.size,
        )
    }

    /**
     * §10.1 D7 — promotion into an authoring-enabled deployment is refused.
     *
     * Raised by the RECEIVER on purpose. The sender checks the same thing from the inventory
     * and refuses earlier with the same code, but a sender is a client: a client bug, an
     * operator's `curl`, or a stale UI must not be able to push drafts' home full of promoted
     * content. Both ends guard, exactly as §10.3 requires of the version rules.
     */
    private fun refuseIfAuthoring() {
        if (!authoringEnabled) return
        throw ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_IS_AUTHORING,
            "This deployment has authoring enabled (datapipelines.deployment.authoring-enabled=true) and does not " +
                "accept promoted content — dev is where drafts live (versioning D7). Set authoring-enabled=false on " +
                "a promotion receiver.",
            mapOf("authoring_enabled" to true),
        )
    }

    /** The `{"templates": [...]}` envelope `TemplateImportService` reads. */
    private fun templatesPayload(batch: PromotionWire.Batch): String {
        val root: ObjectNode = MAPPER.createObjectNode()
        root.set<ObjectNode>("templates", MAPPER.createArrayNode().addAll(batch.templates))
        return MAPPER.writeValueAsString(root)
    }

    /**
     * The 140 gate: every batch pipeline whose body declares `checks[]` gets a fresh,
     * unpersisted run on THIS deployment's datasources (see [apply] for why outside the
     * transaction and why unpersisted). Any `fail` or `error` verdict refuses the whole
     * batch with `pipeline.check.failed` — one unit or nothing, exactly like every other
     * §10 pre-validation. There is no override channel: promotion is two deployments
     * trusting each other, and a number nobody verified is not a release.
     */
    private fun gateChecks(
        batch: PromotionWire.Batch,
        workspaceId: java.util.UUID,
        actorId: java.util.UUID,
    ) {
        val deserializer = co.datapipelines.pipeline.PipelineDeserializer()
        batch.pipelines.forEach { payload ->
            val pipeline = deserializer.readOrThrow(payload.toString())
            if (pipeline.checks.isEmpty()) return@forEach
            val version = payload.get("version")?.takeIf { it.isInt }?.asInt() ?: 0
            val outcomes =
                checkRunner.run(
                    workspaceId = workspaceId,
                    // Inert for an unpersisted run — no row is written to key on it.
                    pipelineId = java.util.UUID(0L, 0L),
                    version = version,
                    pipeline = pipeline,
                    parameters = emptyMap(),
                    via = co.datapipelines.pipeline.CheckRunVia.RELEASE,
                    actor = actorId,
                    persist = false,
                )
            val failing = outcomes.filter { it.verdict != co.datapipelines.pipeline.CheckRunVerdict.PASS }
            if (failing.isNotEmpty()) {
                throw ApiException(
                    PipelineErrorCodes.Check.FAILED,
                    "Promotion refused: pipeline '${pipeline.name}' carries release checks and " +
                        "${failing.size} of ${outcomes.size} did not pass on this deployment's datasources.",
                    mapOf(
                        "pipeline" to pipeline.name,
                        "checks" to
                            failing.map { outcome ->
                                mapOf(
                                    "check_id" to outcome.checkId,
                                    "name" to outcome.name,
                                    "observed" to outcome.observed,
                                    "verdict" to outcome.verdict.wire,
                                    "message" to outcome.message,
                                )
                            },
                    ),
                )
            }
        }
    }

    companion object {
        /**
         * enums.md §15 / auth.md §10.1 — a promotion batch this deployment stored. The
         * `auth.` domain, paired with `auth.promotion.rejected`: the two are the promotion
         * CHANNEL's outcomes, recorded beside the credential that gates it.
         */
        const val AUDIT_ACCEPTED = "auth.promotion.accepted"

        private val MAPPER = PipelineJson.objectMapper()
    }
}
