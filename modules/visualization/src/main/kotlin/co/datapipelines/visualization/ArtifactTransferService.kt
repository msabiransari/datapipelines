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
 * (D61's order). Each payload is bound by the ONE entry bind ([visualizationEntry] / [dashboardEntry]): the nine
 * lifecycle keys are removed from the payload's TOP level (`name` kept — it is a document key), the family READER
 * then walks the result — so the seven `datapipelines.visualization.*` bounds and the two dashboard bounds hold on
 * import and on the promotion receive exactly as on save — and the strict bind refuses everything else; a lifecycle
 * key smuggled into a nested object (`renderer.id`) refuses as the reader's `unknown_key`. A present-but-non-integer
 * `version` and a present-but-non-array `templates`/`visualizations` are REFUSED (`body_invalid` naming the path),
 * never coerced into the version-less/empty paths. The id is kept, the hash verified, a taken id is
 * `import.id_taken` (C29). Like the REST parameter-set import (C35) this path is NOT atomic across its parts: a
 * refused dashboard leaves the templates and visualizations it already landed (each idempotent on re-import).
 */
class ArtifactTransferService(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val bundle: TemplateBundle,
    /** The document bounds run on IMPORT too — the L1c HIGH item: the readers, not the bare mapper. */
    private val visualizationReader: VisualizationReader,
    private val dashboardReader: DashboardReader,
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
        val dashboard = dashboardEntry(objectOrRefuse(root.get("dashboard"), DashboardErrorCodes.BODY_INVALID, "dashboard"))
        val bundled = optionalArray(root, "visualizations", DashboardErrorCodes.BODY_INVALID).map(::parseVisualization)
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
            // The exported release's evidence summary — the test run id, its verdict, the screenshot hash (L4's
            // tables). NULL until L4 lands, and the manifest SAYS so: an importing deployment records
            // `imported_with_evidence: false` on the audit row, never guesses.
            .putNull("evidence")

    // ---- parsing -----------------------------------------------------------------------------------------

    private data class ParsedVisualization(
        val export: ArtifactExport<VisualizationBody>,
        val templates: List<JsonNode>,
    )

    private fun parseVisualization(envelope: JsonNode): ParsedVisualization {
        val root = objectOrRefuse(envelope, VisualizationErrorCodes.BODY_INVALID, "")
        val payload = objectOrRefuse(root.get("visualization"), VisualizationErrorCodes.BODY_INVALID, "visualization")
        val templates = optionalArray(root, "templates", VisualizationErrorCodes.BODY_INVALID)
        return ParsedVisualization(visualizationEntry(payload), templates)
    }

    /**
     * The entry-level bind of ONE artifact payload — the import routes' and the promotion wire's ONE bind (the L1c
     * HIGH item): the lifecycle keys stripped BY NAME at the top level (`name` kept — the reader's document key),
     * then the family READER's `readOrThrow`, so every document bound (`datapipelines.visualization.*`, the two
     * dashboard bounds, the key tables, the strict types) holds here exactly as on save. A miss is the family's
     * `body_invalid`, with the reader's failure list riding `details.failures`.
     */
    fun visualizationEntry(payload: ObjectNode): ArtifactExport<VisualizationBody> =
        entryOf(payload, VisualizationErrorCodes.BODY_INVALID) { stripped ->
            visualizationReader.readOrThrow(stripped).let { it.name to it.body }
        }

    /** The dashboard twin of [visualizationEntry]. */
    fun dashboardEntry(payload: ObjectNode): ArtifactExport<DashboardBody> =
        entryOf(payload, DashboardErrorCodes.BODY_INVALID) { stripped ->
            dashboardReader.readOrThrow(stripped).let { it.name to it.body }
        }

    private fun <B : Any> entryOf(
        payload: ObjectNode,
        bodyInvalid: String,
        bindBody: (ObjectNode) -> Pair<String, B>,
    ): ArtifactExport<B> {
        val id = textualUuid(payload.get("id"), bodyInvalid, "id")
        val hash = textual(payload.get("body_hash"), bodyInvalid, "body_hash")
        val versionNode = payload.get("version")
        // A present-but-non-integer version REFUSES (the owner's ruling): the old takeIf silently sent it down the
        // version-less path, skipping the hash check on content the sender declared versioned.
        if (versionNode != null && !versionNode.isNull && !versionNode.isInt) {
            throw wrongType(bodyInvalid, "version")
        }
        val (boundName, body) = bindBody(strippedKeepName(payload))
        if (listOfNotNull(id, boundName, hash, body).size != REQUIRED_EXPORT_FIELDS) throw malformed(bodyInvalid, "payload")
        return ArtifactExport(
            id = checkNotNull(id),
            name = boundName,
            version = versionNode?.takeIf(JsonNode::isInt)?.asInt(),
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

    /** The envelope's OPTIONAL array member: present-but-not-an-array REFUSES (the ruling), absent is empty. */
    private fun optionalArray(
        root: ObjectNode,
        key: String,
        bodyInvalid: String,
    ): List<JsonNode> {
        val node = root.get(key) ?: return emptyList()
        if (node.isNull) return emptyList()
        if (node !is ArrayNode) throw wrongType(bodyInvalid, key)
        return node.toList()
    }

    private fun textual(
        node: JsonNode?,
        bodyInvalid: String,
        path: String,
    ): String? {
        if (node == null || node.isNull) return null
        if (!node.isTextual) throw wrongType(bodyInvalid, path)
        return node.asText()
    }

    private fun textualUuid(
        node: JsonNode?,
        bodyInvalid: String,
        path: String,
    ): UUID? {
        val text = textual(node, bodyInvalid, path) ?: return null
        return runCatching { UUID.fromString(text) }.getOrNull()
    }

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

    /** A present field of the wrong JSON type — the ruling: refused, never coerced into an emptier path. */
    private fun wrongType(
        bodyInvalid: String,
        path: String,
    ) = DatapipelinesException(
        bodyInvalid,
        "The export envelope's '$path' has the wrong JSON type.",
        mapOf("path" to path, "reason" to "wrong_type"),
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

        /**
         * The strip the READER bind reads: the nine lifecycle keys removed BY NAME at the top level, `name` KEPT —
         * it is a document-level key the reader splits off itself ([DocumentBinding]).
         */
        private fun strippedKeepName(payload: ObjectNode): ObjectNode {
            val stripped = MAPPER.createObjectNode()
            payload.properties().forEach { (key, value) -> if (key == "name" || key !in LIFECYCLE_KEYS) stripped.set<JsonNode>(key, value) }
            return stripped
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
