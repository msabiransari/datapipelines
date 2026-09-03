package co.datapipelines.web.pipelines

import co.datapipelines.auth.AuditLogger
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.UserService
import co.datapipelines.auth.WorkspaceContext
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.web.api.ApiException
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
    private val userService: UserService,
    private val auditLogger: AuditLogger,
    private val transactionTemplate: TransactionTemplate,
    private val authoringEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(PromotionReceiveService::class.java)

    fun apply(batch: PromotionWire.Batch): PromotionWire.Applied {
        refuseIfAuthoring()
        val workspace = inventory.contextFor(batch.workspace)
        val actor = userService.systemActor()

        withActiveWorkspace(workspace) {
            transactionTemplate.executeWithoutResult {
                if (batch.templates.isNotEmpty()) {
                    templateImportService.import(templatesPayload(batch), workspace.id, actor.id)
                }
                batch.pipelines.forEach { pipeline ->
                    pipelineImportService.import(pipeline.toString(), workspace.id, actor.id)
                }
            }
        }

        // R7: the promoted rows are stamped with the system actor, and WHERE they came from is
        // recorded here — the source deployment's name and a fingerprint of the key that
        // authorised the push. Never the key.
        auditLogger.log(
            event = AUDIT_ACCEPTED,
            userId = actor.id,
            details =
                mapOf(
                    "source_env" to batch.sourceEnv,
                    "key_fingerprint" to batch.keyFingerprint,
                    "workspace" to batch.workspace,
                    "templates" to batch.templates.size,
                    "pipelines" to batch.pipelines.size,
                ),
        )
        log.info(
            "event=$AUDIT_ACCEPTED source_env={} workspace={} templates={} pipelines={} actor={}",
            batch.sourceEnv,
            batch.workspace,
            batch.templates.size,
            batch.pipelines.size,
            actor.id,
        )
        return PromotionWire.Applied(batch.workspace, batch.sourceEnv, batch.templates.size, batch.pipelines.size)
    }

    /**
     * Stamps the batch's target workspace onto the promotion principal for the duration of
     * [block], then restores whatever was there.
     *
     * The import services take `workspaceId` explicitly — but not everything on the write path
     * does. `contractDatasourceRegistry` (the port `PipelineValidator` asks "is this datasource
     * registered?") resolves through **the principal's ACTIVE workspace**, and falls back to
     * GLOBAL-ONLY visibility when there is none. The promotion credential pins no workspace, so
     * without this every workspace-BOUND datasource on the receiver read as unregistered and
     * the whole batch was refused `pipeline.import.missing_datasource` — found by the
     * two-deployment E2E, and by nothing smaller: a single-context test shares one principal
     * and never sees it.
     *
     * The payload's workspace is the honest value here: it is the one the import writes into,
     * resolved by name against this deployment's own rows, and it is restored on the way out so
     * nothing leaks into the request's later handling.
     */
    private fun <T> withActiveWorkspace(
        workspace: WorkspaceContext,
        block: () -> T,
    ): T {
        val context = SecurityContextHolder.getContext()
        val previous = context.authentication
        val principal = previous?.principal as? AuthenticatedPrincipal
        if (principal == null) return block()
        context.authentication =
            UsernamePasswordAuthenticationToken(principal.copy(workspace = workspace), null, previous.authorities)
        return try {
            block()
        } finally {
            context.authentication = previous
        }
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
