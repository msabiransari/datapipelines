package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.guard.MetadataWriterGuard
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

/**
 * The write mechanics every API writer shares (#786; record §15.1): the metadata transaction (joined when one is open),
 * the registry invalidation registered AFTER the owning transaction commits (#841 ledger premise 2), the authoring
 * capability refusal, and the one creation step that writes an entity together with its version 1 — so no creation path
 * can leave an entity without a version row (brief E.2: the conversion's selector is legacy-only by construction).
 */
internal class EndpointWrites(
    private val endpoints: PublishedEndpointRepository,
    private val versions: PublishedEndpointVersionRepository,
    private val registry: EndpointRegistry,
    private val transactions: TransactionOperations?,
    val writeGuard: MetadataWriterGuard,
) {
    /** [block] in the metadata transaction (joined when one is open), or bare when none is wired (the unit-test shape). */
    fun <T> inTransaction(block: () -> T): T {
        val tx = transactions ?: return block()
        var result: Result<T>? = null
        tx.executeWithoutResult { result = Result.success(block()) }
        return requireNotNull(result) { "the transaction callback did not run" }.getOrThrow()
    }

    /**
     * Invalidates the serve registry — locally and on every peer — once the transaction that OWNS this write commits, and
     * never when it rolls back (#841 G0 R2): returning from [inTransaction] is not that moment when the callback joined an
     * outer transaction. With no transaction at all (the directly constructed test shape) it runs at once.
     */
    fun invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            registry.invalidate()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = registry.invalidate()
            },
        )
    }

    /**
     * Creates the entity at [parsed] and its version 1 with [status], pointed at by `current_version`, inside the
     * caller's transaction AFTER the workspace guard. Takes the registry key first because path absence and overlap
     * matter (record §15.1 class 3), then the entity insert (with its overlap check) and the version row.
     */
    @Suppress("LongParameterList") // the entity's columns, the v1 body and the actor — one creation step
    fun createWithFirstVersion(
        workspaceId: UUID,
        parsed: EndpointPath.Parsed,
        pipelineId: UUID,
        body: EndpointVersionBody,
        status: PipelineVersionStatus,
        actor: UUID,
    ): PublishedEndpoint {
        writeGuard.endpointRegistry(PipelineErrorCodes.Endpoint.WRITE_BUSY)
        val now = Instant.now()
        val entity =
            endpoints.insert(
                PublishedEndpoint.of(
                    id = UUID.randomUUID(),
                    workspaceId = workspaceId,
                    pathPattern = parsed.pattern,
                    isEnabled = true,
                    createdBy = actor,
                    createdAt = now,
                    updatedAt = now,
                ),
                LegacyEndpointColumns(pipelineId, body.timeoutSeconds, body.description),
            )
        versions.insert(entity.id, FIRST_VERSION, status, body, actor)
        versions.setCurrent(entity.id, FIRST_VERSION)
        invalidateAfterCommit()
        return entity.copy(current = CurrentVersion(FIRST_VERSION, status, body))
    }

    companion object {
        const val FIRST_VERSION = 1

        /** `endpoint.authoring_disabled` (409) — every API draft save, release, discard, restore, purge and switch. */
        fun requireAuthoring(authoring: AuthoringGuard) {
            if (authoring.developmentPosture) return
            throw DatapipelinesException(
                code = PipelineErrorCodes.Endpoint.AUTHORING_DISABLED,
                message =
                    "This deployment has authoring disabled (${AuthoringGuard.CONFIG_KEY}=false) — it is a promotion " +
                        "receiver, not an authoring environment. Create, save, release and manage API versions where " +
                        "authoring is enabled and promote; serving and reads are unaffected.",
                details = mapOf("capability" to "endpoints-authoring", "config_key" to AuthoringGuard.CONFIG_KEY),
            )
        }

        /** Parses and reserves [pathPattern] (§4.1, R-EP5), refusing with the catalogued path codes. */
        fun parsePath(pathPattern: String): EndpointPath.Parsed {
            val parsed =
                EndpointPath.parse(pathPattern).getOrElse {
                    throw DatapipelinesException(
                        code = PipelineErrorCodes.Endpoint.PATH_INVALID,
                        message = it.message.orEmpty(),
                        details = mapOf("path_pattern" to pathPattern),
                    )
                }
            // R-EP5 — the category is the engineer's namespace; the product's (`v<n>`, and the literal `api`) is
            // reserved. Refused here and re-checked when the serve registry is built.
            EndpointPath.reservedCategory(parsed)?.let { segment ->
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.PATH_RESERVED,
                    message =
                        "The category '$segment' is reserved: v<number> is the product's own API namespace and 'api' " +
                            "re-opens the stripped prefix. Pick your own namespace — a business domain, a team, a product line.",
                    details = mapOf("path_pattern" to parsed.pattern, "segment" to segment),
                )
            }
            return parsed
        }

        /** A version body from authored inputs; a pin below 1 is the native not-found (no version 0 exists). */
        fun bodyOf(
            pipelineName: String,
            pipelineVersion: Int,
            timeoutSeconds: Int,
            description: String,
        ): EndpointVersionBody {
            if (pipelineName.isBlank()) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "An API version pins a named pipeline; the pipeline name is blank.",
                    details = mapOf("pipeline" to pipelineName),
                )
            }
            if (pipelineVersion < 1) {
                throw DatapipelinesException(
                    code = PipelineErrorCodes.Endpoint.PIPELINE_VERSION_NOT_FOUND,
                    message = "'$pipelineName' has no version $pipelineVersion; pin an exact version number of 1 or more.",
                    details = mapOf("pipeline" to pipelineName, "pipeline_version" to pipelineVersion),
                )
            }
            return EndpointVersionBody(pipelineName, pipelineVersion, timeoutSeconds, description)
        }
    }
}
