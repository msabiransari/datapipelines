package co.datapipelines.lake.profiles

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The `lake.profile.*` refusals (pipeline-contract §13.25), one factory each, so a code and its `details` keys are
 * spelled once. Details carry a key path, a rule and names only.
 */
object LakeProfileErrors {
    /** A key of a profile document or a pause broke [reason]; [path] is the key (`maintenance.target_file_bytes`). */
    fun invalid(
        path: String,
        reason: String,
        message: String,
        index: Int? = null,
    ) = DatapipelinesException(
        PipelineErrorCodes.Lake.PROFILE_INVALID,
        "The lake profile is invalid at $path${index?.let { " (rule $it)" } ?: ""}: $message",
        buildMap {
            put("path", path)
            put("reason", reason)
            if (index != null) put("index", index)
        },
    )

    fun notFound(name: String) =
        DatapipelinesException(
            PipelineErrorCodes.Lake.PROFILE_NOT_FOUND,
            "No lake profile named $name.",
            mapOf("profile" to name),
        )
}
