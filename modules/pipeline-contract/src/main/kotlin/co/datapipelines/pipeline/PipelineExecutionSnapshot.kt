package co.datapipelines.pipeline

/** One database snapshot of execution content and its derived authority metadata, outside the portable body. */
data class PipelineExecutionSnapshot(
    val bodyJson: String,
    val bodyHash: String,
    val status: PipelineVersionStatus,
    val writesManagedLake: Boolean,
)
