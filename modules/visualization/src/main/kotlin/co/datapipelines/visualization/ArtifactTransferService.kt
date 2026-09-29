package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

/** What a dashboard import did: the dashboard's own answer and each bundled visualization's. */
data class DashboardImport(
    val dashboard: ArtifactImported,
    val visualizations: List<ArtifactImported>,
)

/**
 * The export and import acts of both families (the spec's §12, rest-api §21.4's envelope shape) — the
 * parameter-set transfer's SERVICE, living here behind the [TemplateBundle] port; L1c wires the routes.
 *
 * ## The envelopes
 * A visualization's: `{"visualization": body + lifecycle fields, "templates": [its transform pin's imports closure],
 * "manifest": {…}}`. A dashboard's: `{"dashboard": …, "visualizations": [each pinned visualization's OWN envelope],
 * "manifest": {…, the pinned pipelines' and set's REFERENCES}}` — the pipelines and the set travel by reference,
 * never by body: a dashboard export assumes they are promoted first. Only a RELEASED current version exports.
 *
 * ## The import
 * The WHOLE envelope's shape is judged before anything lands; then templates → visualizations → the artifact
 * (D61's order). Each payload is bound by the ONE strip-by-name helper ([bodyOf]): the nine lifecycle keys are
 * removed from the payload's TOP level only, and the strict mapper refuses everything else — a lifecycle key
 * smuggled into a nested object (`renderer.id`) never binds. The id is kept, the hash verified, a taken id is
 * `import.id_taken` (C29). Like the REST parameter-set import (C35) this path is NOT atomic across its parts: a
 * refused dashboard leaves the templates and visualizations it already landed (each idempotent on re-import).
 */
class ArtifactTransferService(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val bundle: TemplateBundle,
) {
    /** The export envelope of [id]'s CURRENT release. */
    fun exportVisualization(
        workspaceId: UUID,
        id: UUID,
    ): ObjectNode = visualizationEnvelope(workspaceId, released(visualizations.repository, workspaceId, id))

    /** The export envelope of dashboard [id]'s CURRENT release, its pinned visualizations' envelopes inside it. */
    fun exportDashboard(
        workspaceId: UUID,
        id: UUID,
    ): ObjectNode {
        val version = released(dashboards.repository, workspaceId, id)
        val pins =
            version.body.visualizations
                .map { it.visualization }
                .distinct()
        val bundled =
            pins.map { pin ->
                val record =
                    visualizations.repository.findRecordByName(workspaceId, pin.name)
                        ?: throw notReleased(visualizations.repository.kind, pin.name)
                visualizationEnvelope(workspaceId, releasedAt(visualizations.repository, workspaceId, record, pin.version))
            }
        val envelope = MAPPER.createObjectNode()
        envelope.set<JsonNode>("dashboard", payloadOf(version))
        envelope.set<JsonNode>("visualizations", MAPPER.createArrayNode().addAll(bundled))
        envelope.set<JsonNode>(
            "manifest",
            manifest("dashboard", version).also { manifest ->
                manifest.set<JsonNode>("visualization_pins", MAPPER.valueToTree(pins))
                manifest.set<JsonNode>(
                    "pipeline_pins",
                    MAPPER.valueToTree(
                        version.body.sources
                            .map { it.pipeline }
                            .distinct(),
                    ),
                )
                manifest.set<JsonNode>("parameter_set_pin", MAPPER.valueToTree(version.body.parameterSet))
            },
        )
        return envelope
    }

    /** Imports a visualization envelope: its templates first, then the visualization (id kept, hash verified). */
    fun importVisualization(
        workspaceId: UUID,
        envelope: JsonNode,
        actor: UUID,
    ): ArtifactImported {
        val parsed = parseVisualization(envelope)
        bundle.import(workspaceId, parsed.templates, actor)
        return visualizations.import(workspaceId, parsed.export, actor)
    }

    /** Imports a dashboard envelope: every template, then every bundled visualization, then the dashboard. */
    fun importDashboard(
        workspaceId: UUID,
        envelope: JsonNode,
        actor: UUID,
    ): DashboardImport {
        val root = objectOrRefuse(envelope, DashboardErrorCodes.BODY_INVALID, "")
        val dashboard =
            exportOf(
                objectOrRefuse(root.get("dashboard"), DashboardErrorCodes.BODY_INVALID, "dashboard"),
                DashboardBody::class.java,
                DashboardErrorCodes.BODY_INVALID,
            )
        val bundled = (root.get("visualizations") as? ArrayNode)?.map(::parseVisualization).orEmpty()
        bundled.forEach { bundle.import(workspaceId, it.templates, actor) }
        val landed = bundled.map { visualizations.import(workspaceId, it.export, actor) }
        return DashboardImport(dashboards.import(workspaceId, dashboard, actor), landed)
    }

    // ---- envelopes ---------------------------------------------------------------------------------------

    private fun visualizationEnvelope(
        workspaceId: UUID,
        version: ArtifactVersion<VisualizationBody>,
    ): ObjectNode {
        val pins = listOfNotNull(version.body.transform?.template).map { TemplateRef(it.name, it.version) }
        val envelope = MAPPER.createObjectNode()
        envelope.set<JsonNode>("visualization", payloadOf(version))
        envelope.set<JsonNode>("templates", MAPPER.createArrayNode().addAll(bundle.export(workspaceId, pins)))
        envelope.set<JsonNode>(
            "manifest",
            manifest("visualization", version).also {
                it.set<JsonNode>(
                    "template_pins",
                    MAPPER.valueToTree(
                        pins.map { pin ->
                            mapOf("id" to pin.id, "version" to pin.version)
                        },
                    ),
                )
            },
        )
        return envelope
    }

    private fun manifest(
        noun: String,
        version: ArtifactVersion<*>,
    ): ObjectNode =
        MAPPER
            .createObjectNode()
            .put("${noun}_id", version.record.id.toString())
            .put("${noun}_version", version.detail.version)
            .put("${noun}_body_hash", version.detail.bodyHash)
            .put("exported_at", Instant.now().toString())

    // ---- parsing -----------------------------------------------------------------------------------------

    private data class ParsedVisualization(
        val export: ArtifactExport<VisualizationBody>,
        val templates: List<JsonNode>,
    )

    private fun parseVisualization(envelope: JsonNode): ParsedVisualization {
        val root = objectOrRefuse(envelope, VisualizationErrorCodes.BODY_INVALID, "")
        val payload = objectOrRefuse(root.get("visualization"), VisualizationErrorCodes.BODY_INVALID, "visualization")
        val templates = (root.get("templates") as? ArrayNode)?.toList().orEmpty()
        return ParsedVisualization(exportOf(payload, VisualizationBody::class.java, VisualizationErrorCodes.BODY_INVALID), templates)
    }

    /** The payload's lifecycle fields and its strictly bound body — every miss is the family's `body_invalid`. */
    private fun <B : Any> exportOf(
        payload: ObjectNode,
        type: Class<B>,
        bodyInvalid: String,
    ): ArtifactExport<B> {
        val id =
            payload
                .get("id")
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val name = payload.get("name")?.takeIf(JsonNode::isTextual)?.asText()
        val hash = payload.get("body_hash")?.takeIf(JsonNode::isTextual)?.asText()
        val body = bodyOf(payload, type)
        if (listOfNotNull(id, name, hash, body).size != REQUIRED_EXPORT_FIELDS) throw malformed(bodyInvalid, "payload")
        return ArtifactExport(
            id = checkNotNull(id),
            name = checkNotNull(name),
            version = payload.get("version")?.takeIf(JsonNode::isInt)?.asInt(),
            bodyHash = checkNotNull(hash),
            releasedAt =
                payload
                    .get(
                        "released_at",
                    )?.takeIf(JsonNode::isTextual)
                    ?.asText()
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
            body = checkNotNull(body),
        )
    }

    private fun objectOrRefuse(
        node: JsonNode?,
        bodyInvalid: String,
        path: String,
    ): ObjectNode = node as? ObjectNode ?: throw malformed(bodyInvalid, path)

    // ---- the released version an export needs --------------------------------------------------------------

    private fun <B : Any> released(
        repository: ArtifactRepository<B>,
        workspaceId: UUID,
        id: UUID,
    ): ArtifactVersion<B> {
        val record = repository.findRecord(workspaceId, id) ?: throw notFound(repository.kind, id.toString())
        val current = record.currentVersion ?: throw notReleased(repository.kind, record.name)
        return releasedAt(repository, workspaceId, record, current)
    }

    private fun <B : Any> releasedAt(
        repository: ArtifactRepository<B>,
        workspaceId: UUID,
        record: ArtifactRecord,
        version: Int,
    ): ArtifactVersion<B> {
        val found = repository.findVersion(workspaceId, record.id, version) ?: throw notFound(repository.kind, record.id.toString())
        if (found.detail.status != PipelineVersionStatus.RELEASED) throw notReleased(repository.kind, record.name)
        return found
    }

    private fun notFound(
        kind: ArtifactKind,
        id: String,
    ) = DatapipelinesException(kind.codes.notFound, "The ${kind.noun} $id was not found.", mapOf("id" to id.safeEcho()))

    private fun notReleased(
        kind: ArtifactKind,
        name: String,
    ) = DatapipelinesException(
        kind.codes.notFound,
        "The ${kind.noun} '${name.safeEcho()}' has no released version to export — a draft never crosses environments.",
        mapOf(kind.noun to name.safeEcho()),
    )

    private fun malformed(
        bodyInvalid: String,
        path: String,
    ) = DatapipelinesException(
        bodyInvalid,
        "The export envelope is malformed at '${path.ifEmpty { "(envelope)" }}'.",
        mapOf("reason" to "malformed_envelope", "path" to path),
    )

    companion object {
        private val MAPPER = ArtifactJson.mapper

        /** An export payload is complete when its id, name, hash and body are all present. */
        private const val REQUIRED_EXPORT_FIELDS = 4

        /**
         * The lifecycle keys an exported payload carries BESIDE the body — removed BY NAME, at the payload's top level
         * only, before the strict bind (the parameter-set transfer's #299 convention). Every other undeclared key —
         * including one of these nested inside the body — survives the strip and refuses through the mapper.
         */
        val LIFECYCLE_KEYS: Set<String> =
            setOf("id", "name", "version", "created_at", "updated_at", "current_version", "status", "body_hash", "released_at")

        /** The payload [payload] as a strict [type] body — the ONE bind the transfer surfaces share. Null when it does not bind. */
        fun <B : Any> bodyOf(
            payload: ObjectNode,
            type: Class<B>,
        ): B? {
            val stripped = MAPPER.createObjectNode()
            payload.properties().forEach { (key, value) -> if (key !in LIFECYCLE_KEYS) stripped.set<JsonNode>(key, value) }
            return runCatching { MAPPER.treeToValue(stripped, type) }.getOrNull()
        }

        /** The exported payload of [version]: its body and the nine lifecycle fields beside it. */
        fun payloadOf(version: ArtifactVersion<*>): ObjectNode {
            val payload = MAPPER.valueToTree<ObjectNode>(version.body)
            payload.put("id", version.record.id.toString())
            payload.put("name", version.record.name)
            payload.put("version", version.detail.version)
            payload.put("status", version.detail.status.name)
            payload.put("body_hash", version.detail.bodyHash)
            payload.put("created_at", version.detail.createdAt.toString())
            payload.put("updated_at", version.record.updatedAt.toString())
            version.record.currentVersion?.let { payload.put("current_version", it) }
            version.detail.releasedAt?.let { payload.put("released_at", it.toString()) }
            return payload
        }
    }
}
