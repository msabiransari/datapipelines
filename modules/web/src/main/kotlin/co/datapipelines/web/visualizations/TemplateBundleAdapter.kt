package co.datapipelines.web.visualizations

import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TransformBlocks
import co.datapipelines.visualization.TemplateBundle
import co.datapipelines.web.templates.TemplateImportService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The [TemplateBundle] port's web half (the L1c wiring): the template export and import services are `web`'s
 * (`TemplateImportService`), so the visualization module's transfer lives behind the port and this adapter composes
 * them — the parameter-set transfer's composition ([co.datapipelines.web.parameters.ParameterSetTransferService]),
 * lifted behind the port so the transfer SERVICE could land in `modules/visualization`.
 *
 * ## Export
 * The pinned versions and their transitive `imports` closure, deduplicated, each built FIELD BY FIELD rather than
 * serialized wholesale — the sender payload builder's reasons ([co.datapipelines.web.pipelines.PromotionService]
 * `templatePayloadOf`): `created_at` is an `Instant` no wire mapper here binds, and the source deployment's stamps
 * mean nothing on a target. The import reads exactly the fields below (a `TemplateDraft` plus §9.2's preserved
 * fields), so sending exactly those is correct and honest about what crosses.
 *
 * ## Import
 * The `{"templates": [...]}` envelope [TemplateImportService] reads — the envelope root the §21.4/§12 import
 * convention puts them at — on behalf of [actor], idempotent for versions already present.
 */
internal class TemplateBundleAdapter(
    private val templates: TemplateRepository,
    private val templateImport: TemplateImportService,
) : TemplateBundle {
    override fun export(
        workspaceId: UUID,
        pins: List<TemplateRef>,
    ): List<JsonNode> {
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(pins)
        val found = mutableListOf<Template>()
        while (queue.isNotEmpty()) {
            val ref = queue.removeFirst()
            if (seen.add(ref.key)) visit(workspaceId, ref, queue, found)
        }
        return found.map(::payloadOf)
    }

    override fun import(
        workspaceId: UUID,
        templates: List<JsonNode>,
        actor: UUID,
    ) {
        if (templates.isEmpty()) return
        val root: ObjectNode = MAPPER.createObjectNode()
        root.set<JsonNode>("templates", MAPPER.createArrayNode().addAll(templates))
        templateImport.import(MAPPER.writeValueAsString(root), workspaceId, actor)
    }

    private fun visit(
        workspaceId: UUID,
        ref: TemplateRef,
        queue: ArrayDeque<TemplateRef>,
        found: MutableList<Template>,
    ) {
        val version = templates.lookupVersion(workspaceId, ref.id, ref.version) ?: return
        templates.findVersion(workspaceId, ref.id, ref.version)?.let(found::add)
        version.imports.forEach { queue.addLast(TemplateRef(it.id, it.version)) }
    }

    /** One template version, FIELD BY FIELD — the promotion payload builder's shape and reasons. */
    private fun payloadOf(stored: Template): JsonNode {
        val node = MAPPER.createObjectNode()
        node.put("schema_version", stored.schemaVersion)
        node.put("id", stored.id)
        node.put("engine", stored.engine)
        node.put("type", stored.type.wire)
        stored.dialect?.let { node.put("dialect", it.wire) }
        node.put("display_name", stored.displayName)
        node.put("description", stored.description)
        node.put("body", stored.body)
        node.put("is_library", stored.isLibrary)
        // The transform blocks are version content (inside the hash the receiver recomputes), so the payload
        // carries them exactly when the type is a transform.
        stored.contract?.let { node.set<JsonNode>("contract", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
        stored.invariants?.let { node.set<JsonNode>("invariants", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
        stored.tests?.let { node.set<JsonNode>("tests", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
        // The cited facts ride OUTSIDE the hash (transform-nodes §2.3); the import keeps the ids that resolve
        // there and drops the rest (owner ruling 2026-09-25) — TemplateImportService's own lenient half.
        stored.implements?.let { ids -> node.putArray("implements").apply { ids.forEach { add(it) } } }
        node.put("version", stored.version)
        node.put("body_hash", stored.bodyHash)
        val imports = node.putArray("imports")
        stored.imports.forEach { imported ->
            imports
                .addObject()
                .put("id", imported.id)
                .put("version", imported.version)
                .put("alias", imported.alias)
        }
        return node
    }

    private companion object {
        private val MAPPER = co.datapipelines.visualization.ArtifactJson.mapper
    }
}
