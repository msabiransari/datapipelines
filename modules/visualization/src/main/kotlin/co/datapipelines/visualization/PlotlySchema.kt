package co.datapipelines.visualization

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.core.io.ClassPathResource
import java.io.InputStream

/**
 * The reduced Plotly schema, loaded once from the committed resource (the spec's §11.3 step 1). The
 * resource is an artifact of [PlotlySchemaReducer] over the checked-in upstream source; its provenance
 * (version, upstream commit, source hash) rides INSIDE it and is hash-pinned by the provenance test.
 */
object PlotlySchema {
    private val root: JsonNode by lazy { ArtifactJson.mapper.readTree(openSchema()) }

    /**
     * The resource opened through THIS class's loader, never the calling thread's context loader: the first load
     * happens on whichever thread first validates a Plotly configuration, and a shared Reactor worker carries the
     * context loader of the thread that created it — in a test JVM that cycles application contexts, a stopped
     * Tomcat webapp loader, which refuses every resource ("this web application instance has been stopped already").
     */
    internal fun openSchema(): InputStream = ClassPathResource(SCHEMA_RESOURCE, PlotlySchema::class.java.classLoader).inputStream

    /** The reduced resource's classpath location. */
    const val SCHEMA_RESOURCE = "co/datapipelines/visualization/plot-schema-reduced.json"

    /** The release the schema was reduced from — the resource's own recorded facts. */
    val plotlyVersion: String get() = root.path("plotly_version").asText()

    val upstreamCommit: String get() = root.path("upstream_commit").asText()

    /** A trace type's attribute tree, or null when the schema does not carry it. */
    fun traceAttributes(type: String): JsonNode? = root.path("traces").path(type).takeIf { it.isObject }

    /** The layout attributes container. */
    fun layoutAttributes(): JsonNode = root.path("layout").path("layoutAttributes")

    /** The Plotly config attributes container. */
    fun configAttributes(): JsonNode = root.path("config")

    /** The recorded source hash — the provenance test's handle. */
    fun sourceSha256(): String = root.path("source_sha256").asText()
}
