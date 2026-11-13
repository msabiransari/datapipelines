package co.datapipelines.pipeline.release

/** One dependency a release flipped (record §6 `dependencies_released`, §11): its family, authored name and exact version. */
data class ReleasedDependency(
    val family: ReleaseFamily,
    val name: String,
    val version: Int,
) {
    /** The response and audit entry: `{family, name, version}`, never a body or an internal id. */
    fun toWire(): Map<String, Any?> = linkedMapOf("family" to family.wire, "name" to name, "version" to version)
}
