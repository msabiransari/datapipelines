package co.datapipelines.lake.definitions

import co.datapipelines.pipeline.AuthoringGuard
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import java.util.UUID

/**
 * The `lake.definition.*` refusals (pipeline-contract §13.25), one factory each, so a code and its `details` keys are
 * spelled once. Details carry ids, versions, names and reasons only: never a storage location or a credential.
 */
object LakeDefinitionErrors {
    fun invalidBody(
        path: String,
        reason: String,
        message: String,
        cause: Throwable? = null,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_INVALID_BODY,
        "The table definition is invalid at $path: $message",
        mapOf("path" to path, "reason" to reason),
        cause,
    )

    fun notFound(
        tableId: UUID?,
        version: Int? = null,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND,
        if (version == null) "No managed table $tableId." else "Managed table $tableId has no definition version $version.",
        mapOf("table_id" to tableId?.toString(), "version" to version),
    )

    fun notFoundByName(
        namespace: String,
        table: String,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_NOT_FOUND,
        "No managed table $namespace.$table.",
        mapOf("namespace" to namespace, "table" to table),
    )

    fun notDraft(
        tableId: UUID,
        version: Int?,
        status: String?,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_NOT_DRAFT,
        "Version $version of managed table $tableId is not its draft${status?.let { " (it is $it)" } ?: ""}.",
        mapOf("table_id" to tableId.toString(), "version" to version, "status" to status),
    )

    fun notReleased(
        tableId: UUID,
        version: Int,
        status: String,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_NOT_RELEASED,
        "Version $version of managed table $tableId is $status; only a released version can be discarded.",
        mapOf("table_id" to tableId.toString(), "version" to version, "status" to status),
    )

    fun versionConflict(tableId: UUID) =
        DatapipelinesException(
            PipelineErrorCodes.Lake.DEFINITION_VERSION_CONFLICT,
            "The draft of managed table $tableId changed since it was read. Read it again and retry.",
            mapOf("table_id" to tableId.toString()),
        )

    fun nameTaken(
        namespace: String,
        table: String,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_NAME_TAKEN,
        "A managed table $namespace.$table already exists.",
        mapOf("namespace" to namespace, "table" to table),
    )

    fun appliedVersion(
        tableId: UUID,
        version: Int,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_APPLIED_VERSION,
        "Version $version is the definition this environment has applied to managed table $tableId; it cannot be discarded.",
        mapOf("table_id" to tableId.toString(), "version" to version),
    )

    fun tableBusy(
        tableId: UUID,
        reason: String,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.DEFINITION_TABLE_BUSY,
        "Managed table $tableId is busy ($reason). Nothing changed; try again shortly.",
        mapOf("table_id" to tableId.toString(), "reason" to reason),
    )

    fun authoringDisabled() =
        DatapipelinesException(
            PipelineErrorCodes.Lake.DEFINITION_AUTHORING_DISABLED,
            "This deployment has authoring disabled (${AuthoringGuard.CONFIG_KEY}=false) — it is a promotion receiver. " +
                "Write lake table definitions where authoring is enabled and promote them.",
            mapOf("capability" to "lake-definitions-authoring", "config_key" to AuthoringGuard.CONFIG_KEY),
        )
}
