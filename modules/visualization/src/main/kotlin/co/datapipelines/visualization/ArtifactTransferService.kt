package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.typesystem.DatapipelinesException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.transaction.support.TransactionOperations
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
 * `import.id_taken` (C29).
 *
 * ## Atomicity (F1, the L1c pass)
 * The VISUALIZATION import keeps the parameter-set mould's accepted shape (C35: templates then the artifact,
 * each idempotent, an interactive re-runnable action — the transfer E2E's case 4 pins it). The DASHBOARD
 * import is ONE transaction (F1 of the orchestrator's pass): templates → every bundled visualization → the
 * dashboard, so a refused dashboard leaves NOTHING landed — at the L1c tip each bundled visualization landed
 * RELEASED in its own transaction before the dashboard was judged, evidence-less and unaudited. The
 * lifecycle's inner `transactions.execute` joins this one, exactly as it joins the promotion receive's (the
 * transfer E2E's case 8 proves the join); production wiring passes the metadata manager's template.
 */
class ArtifactTransferService(
    private val visualizations: VisualizationService,
    private val dashboards: DashboardService,
    private val bundle: TemplateBundle,
    /** The document bounds run on IMPORT too — the L1c HIGH item: the readers, not the bare mapper. */
    private val visualizationReader: VisualizationReader,
    private val dashboardReader: DashboardReader,
    /** The dashboard import's one transaction; [ArtifactLifecycle.DIRECT] in a directly constructed test. */
    private val transactions: TransactionOperations = ArtifactLifecycle.DIRECT,
    /** O2: an import landing RELEASED judges its pins by the RELEASE rules — both transfer surfaces' one judge. */
    private val releaseRules: ArtifactImportReleaseRules,
    /** O7: the envelope arrays' count ceiling before their members bind — the family's one `too_many` shape. */
    private val config: VisualizationConfig = VisualizationConfig(),
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
        // O2: the artifact lands RELEASED — its pin is judged by the RELEASE rules BEFORE the templates
        // land, so a refused import leaves nothing (the save rules' import lens, inside `import`, names
        // the pins this workspace does not hold at all).
        releaseRules.judgeVisualization(workspaceId, parsed.export.body)
        bundle.import(workspaceId, parsed.templates, actor)
        return visualizations.import(workspaceId, parsed.export, actor)
    }

    /**
     * Imports a dashboard envelope: ONE transaction around every template, every bundled visualization and the
     * dashboard (F1) — a refused dashboard leaves nothing landed.
     */
    fun importDashboard(
        workspaceId: UUID,
        envelope: JsonNode,
        actor: UUID,
    ): DashboardImport {
        val root = objectOrRefuse(envelope, DashboardErrorCodes.BODY_INVALID, "")
        val dashboard = dashboardEntry(objectOrRefuse(root.get("dashboard"), DashboardErrorCodes.BODY_INVALID, "dashboard"))
        val bundled = optionalArray(root, "visualizations", DashboardErrorCodes.BODY_INVALID).map(::parseVisualization)
        // The whole act, or nothing (F1): the inner `transactions.execute` of every landing JOINS this
        // transaction (the transfer E2E's case 8 proves the join at the receive; the same mechanism here).
        return checkNotNull(
            transactions.execute {
                bundled.forEach { parsed ->
                    // O2: each bundled artifact lands RELEASED — the RELEASE rules judge its pins first.
                    releaseRules.judgeVisualization(workspaceId, parsed.export.body)
                    bundle.import(workspaceId, parsed.templates, actor)
                }
                val landed = bundled.map { visualizations.import(workspaceId, it.export, actor) }
                // The dashboard's own pins — the set, and every visualization it pins (the bundle just
                // landed) — are judged RELEASE before its landing (O2), inside the same transaction.
                releaseRules.judgeDashboard(workspaceId, dashboard.body)
                DashboardImport(dashboards.import(workspaceId, dashboard, actor), landed)
            },
        )
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

    /** The envelope's OPTIONAL array member: present-but-not-an-array REFUSES (the ruling), absent is empty.
     *  O7: the array's COUNT is bounded before any member is parsed or bound — a dashboard's bundle by
     *  `max-visualizations-per-dashboard` (the cap its own body's occurrences obey), a template closure by
     *  the same key, the transfer families' one envelope ceiling. The refusal is the family's `body_invalid`
     *  with the reader's `too_many` shape (`count`, `max`, `config_key`), before any member's work. */
    private fun optionalArray(
        root: ObjectNode,
        key: String,
        bodyInvalid: String,
    ): List<JsonNode> {
        val node = root.get(key) ?: return emptyList()
        if (node.isNull) return emptyList()
        if (node !is ArrayNode) throw wrongType(bodyInvalid, key)
        val entries = node.toList()
        if (entries.size > config.maxVisualizationsPerDashboard) {
            throw DatapipelinesException(
                bodyInvalid,
                "${entries.size} entries at '$key'; at most ${config.maxVisualizationsPerDashboard} " +
                    "(${VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path}).",
                mapOf(
                    "reason" to "too_many",
                    "path" to key,
                    "count" to entries.size,
                    "max" to config.maxVisualizationsPerDashboard,
                    "config_key" to VisualizationKey.MAX_VISUALIZATIONS_PER_DASHBOARD.path,
                ),
            )
        }
        return entries
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

        /**
         * The payload [payload] as a strict [type] body — the strip half of the ONE bind the transfer
         * surfaces share, module-internal (O8 of the L1c pass: the reader bind is the entry path; an
         * unbounded `treeToValue` must not grow production callers). Null when it does not bind.
         */
        internal fun <B : Any> bodyOf(
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
