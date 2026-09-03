package co.datapipelines.web.pipelines

import co.datapipelines.auth.PromotionProperties
import co.datapipelines.auth.PromotionServerKeys
import co.datapipelines.pipeline.Node
import co.datapipelines.pipeline.NodeOutput
import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.Pipeline
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineJson
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.web.api.ApiErrors
import co.datapipelines.web.api.ApiException
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The SENDER: the delta, the dependency closure, the push order and the pre-flight checks
 * (versioning §10.2–§10.5).
 *
 * ## The listing rule is one function
 * [plan] computes exactly §10.2's set — RELEASED, and a version strictly greater than the
 * target's (a pipeline the target does not have counts as version 0), with same-hash entries
 * dropped because version is for humans and hash is for machines. The UI renders that list and
 * nothing else; drafts and same-version entries are never listed anywhere, which is a property
 * of this function rather than a rule the screen has to remember.
 *
 * ## The guards do not trust the UI
 * §10.3: the same constraints are enforced on the push path regardless of what the screen
 * showed. [promote] re-reads every selected pipeline and re-checks `not_released` and
 * `not_newer` against a FRESH inventory — the plan the human looked at may be minutes old, and
 * a release that landed in between must not sail through on a stale decision. The receiver's
 * §9.2 import table guards its own end. Both ends, always.
 *
 * ## The closure the export bundle does not compute
 * §10.4: template versions in the transitive `imports_json` closure — which export DOES
 * compute — plus the child pipelines PIPELINE nodes reference, recursively, which export does
 * NOT. Promotion computes both, at the exact PINNED versions, because a pin is what the target
 * has to be able to resolve.
 */
class PromotionService(
    private val pipelines: PipelineRepository,
    private val templates: TemplateRepository,
    private val client: PromotionTargetClient,
    private val promotionProperties: PromotionProperties,
    private val deploymentName: String,
    private val deserializer: PipelineDeserializer = PipelineDeserializer(),
) {
    private val log = LoggerFactory.getLogger(PromotionService::class.java)

    /** One row of §10.2's listing: what the target has, what this deployment would send. */
    data class Candidate(
        val name: String,
        val displayName: String,
        val localVersion: Int,
        /** The target's current version, or 0 when the target does not have this pipeline (§10.2). */
        val targetVersion: Int,
    )

    /** What the promotion screen renders (§10.1, D8). */
    data class Plan(
        val targetBaseUrl: String,
        val targetDeployment: String,
        val targetAuthoringEnabled: Boolean,
        val workspace: String,
        /** §10.2's set, exactly. Empty means "nothing to promote", which is the common state. */
        val promotable: List<Candidate>,
        /** How many live pipelines were examined — so an empty listing reads as "in sync", not "broken". */
        val examined: Int,
    )

    /** §10.2 — what this workspace could promote to the configured target, right now. */
    fun plan(
        workspaceId: UUID,
        workspaceName: String,
    ): Plan {
        val inventory = client.inventory(workspaceName)
        val onTarget = inventory.pipelineByName()
        val local = pipelines.findAll(workspaceId)
        val promotable =
            local.mapNotNull { record ->
                val version = pipelines.findCurrentVersionDetail(workspaceId, record.id) ?: return@mapNotNull null
                if (version.status != PipelineVersionStatus.RELEASED) return@mapNotNull null
                val target = onTarget[record.name]
                // Same hash ⇒ nothing to push, whatever the numbers say (§10.2).
                if (target != null && target.bodyHash == version.bodyHash) return@mapNotNull null
                if (target != null && record.currentVersion <= target.currentVersion) return@mapNotNull null
                Candidate(record.name, record.displayName, record.currentVersion, target?.currentVersion ?: 0)
            }
        return Plan(
            targetBaseUrl = client.targetBaseUrl,
            targetDeployment = inventory.deployment,
            targetAuthoringEnabled = inventory.authoringEnabled,
            workspace = workspaceName,
            promotable = promotable.sortedBy { it.name },
            examined = local.size,
        )
    }

    /**
     * §10.3–§10.5 — promote [names] from [workspaceId] to the configured target.
     *
     * The order of operations is the order the checks must happen in: refuse an authoring
     * target before doing any work, guard each selection, build the closure, verify every
     * datasource the WHOLE batch needs exists there, and only then push.
     */
    fun promote(
        workspaceId: UUID,
        workspaceName: String,
        names: List<String>,
    ): PromotionWire.Applied {
        require(names.isNotEmpty()) { "promote() needs at least one pipeline name" }
        val inventory = client.inventory(workspaceName)
        refuseAuthoringTarget(inventory)

        val closure = Closure(workspaceId)
        names.distinct().forEach { name -> closure.addRoot(name, inventory) }
        verifyDatasources(closure, inventory)

        val batch =
            PromotionWire.Batch(
                sourceEnv = deploymentName,
                keyFingerprint = PromotionServerKeys.fingerprint(promotionProperties.target.serverKey),
                workspace = workspaceName,
                templates = closure.templatePayloads(inventory),
                pipelines = closure.pipelinePayloads(inventory),
            )
        log.info(
            "event=pipeline.promotion.pushing target={} workspace={} roots={} templates={} pipelines={}",
            client.targetBaseUrl,
            workspaceName,
            names.size,
            batch.templates.size,
            batch.pipelines.size,
        )
        return client.push(batch)
    }

    /**
     * §10.1 D7 on the sender's side. The receiver refuses the same thing with the same code
     * (both ends guard); refusing here as well means a human sees it before a batch is built,
     * not after one is rejected.
     */
    private fun refuseAuthoringTarget(inventory: PromotionWire.Inventory) {
        if (!inventory.authoringEnabled) return
        throw ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_TARGET_IS_AUTHORING,
            "The promotion target '${inventory.deployment}' has authoring enabled and does not accept promoted " +
                "content — dev is where drafts live (versioning D7).",
            mapOf("target" to client.targetBaseUrl, "target_deployment" to inventory.deployment, "authoring_enabled" to true),
        )
    }

    /**
     * §10.5 — every datasource name the BATCH references must exist on the target, verified
     * before anything is pushed. One consolidated refusal naming every absent name, mirroring
     * the import service's combined report, rather than a mid-batch failure that leaves the
     * target holding half a closure.
     */
    private fun verifyDatasources(
        closure: Closure,
        inventory: PromotionWire.Inventory,
    ) {
        val present = inventory.datasources.toSet()
        val missing = closure.datasourceNames().filterNot { it in present }.sorted()
        if (missing.isEmpty()) return
        throw ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_MISSING_DATASOURCES,
            "The target '${inventory.deployment}' has no datasource named ${missing.joinToString(", ")}. " +
                "Register them there first; nothing was pushed.",
            mapOf(
                "target" to client.targetBaseUrl,
                "target_deployment" to inventory.deployment,
                "missing_datasources" to missing,
            ),
        )
    }

    /**
     * The dependency closure of one promotion batch, accumulated in §10.4 order.
     *
     * Insertion order IS push order. Roots are added last within [pipelineOrder] because
     * [addPipeline] walks a pipeline's children BEFORE recording the pipeline itself — so a
     * child is always at a lower index than every parent that runs it, which is exactly
     * "children before parents". A pipeline reached twice (a shared child) is recorded once,
     * at its first, deepest position.
     */
    private inner class Closure(
        private val workspaceId: UUID,
    ) {
        /** Pinned pipeline versions, children before parents. Key is `name@version`. */
        private val pipelineOrder = LinkedHashMap<String, PinnedPipeline>()

        /** Pinned template versions, imports before importers. Key is `id@version`. */
        private val templateOrder = LinkedHashMap<String, TemplateRef>()

        private val visitedPipelines = mutableSetOf<String>()
        private val visitedTemplates = mutableSetOf<String>()

        /** A pipeline at the version this batch carries, with its parsed body. */
        inner class PinnedPipeline(
            val id: UUID,
            val name: String,
            val version: Int,
            val bodyHash: String,
            val body: String,
            val parsed: Pipeline,
        )

        /**
         * A pipeline the HUMAN selected: §10.3's two guards apply to it, at its CURRENT
         * version. Its dependencies ride along as dependencies and are governed by §10.4's
         * skip rule instead — a child pinned at an old version is not "not newer", it is the
         * version the parent actually runs.
         */
        fun addRoot(
            name: String,
            inventory: PromotionWire.Inventory,
        ) {
            val record =
                pipelines.findByName(workspaceId, name)
                    ?: throw ApiErrors.pipelineNotFound(name)
            val version =
                pipelines.findCurrentVersionDetail(workspaceId, record.id)
                    ?: throw notReleased(name, record.currentVersion, "no released version exists")
            if (version.status != PipelineVersionStatus.RELEASED) {
                throw notReleased(name, version.version, "its current version is ${version.status}")
            }
            val target = inventory.pipelineByName()[name]
            if (target != null && record.currentVersion <= target.currentVersion) {
                throw ApiException(
                    PipelineErrorCodes.Versioning.PROMOTION_NOT_NEWER,
                    "Pipeline '$name' is at version ${record.currentVersion} here and the target already serves " +
                        "version ${target.currentVersion}. Same-version pushes are a bug, not a no-op.",
                    mapOf("pipeline" to name, "version" to record.currentVersion, "target_version" to target.currentVersion),
                )
            }
            addPipeline(record.id, name, record.currentVersion)
        }

        /** Walks children first, then templates, then records this pipeline (§10.4 order). */
        private fun addPipeline(
            id: UUID,
            name: String,
            version: Int,
        ) {
            val key = "$name@$version"
            if (!visitedPipelines.add(key)) return
            val detail =
                pipelines.findVersionDetail(workspaceId, id, version)
                    ?: throw ApiErrors.pipelineNotFound("$name@$version")
            if (detail.status != PipelineVersionStatus.RELEASED) {
                throw notReleased(name, version, "version $version is ${detail.status}")
            }
            val body =
                pipelines.findVersionBody(workspaceId, id, version)
                    ?: throw ApiErrors.pipelineNotFound("$name@$version")
            val parsed = deserializer.readOrThrow(body)

            parsed.nodes.filter { it.type == NodeType.PIPELINE }.forEach { node ->
                val child = node.pipeline ?: return@forEach
                val childRecord =
                    pipelines.findByNameIncludingDeleted(workspaceId, child.name)
                        ?: throw ApiErrors.pipelineNotFound(child.name)
                addPipeline(childRecord.id, child.name, child.version)
            }
            parsed.nodes.forEach { node -> addTemplate(node.template) }

            pipelineOrder[key] = PinnedPipeline(id, name, version, detail.bodyHash, body, parsed)
        }

        /** A template version and the transitive `imports_json` closure beneath it. */
        private fun addTemplate(ref: TemplateRef) {
            if (ref.id.isBlank()) return
            if (!visitedTemplates.add(ref.key)) return
            val version = templates.lookupVersion(workspaceId, ref.id, ref.version) ?: return
            version.imports.forEach { addTemplate(TemplateRef(it.id, it.version)) }
            templateOrder[ref.key] = ref
        }

        /** Every datasource name the whole batch references — §10.5's input. */
        fun datasourceNames(): Set<String> =
            pipelineOrder.values
                .flatMap { it.parsed.nodes }
                .flatMap(::datasourcesOf)
                .toSet()

        /**
         * §10.4's skip rule for templates: already present at the same version AND the same
         * hash is a no-op, so it is left out of the batch entirely.
         */
        fun templatePayloads(inventory: PromotionWire.Inventory): List<JsonNode> {
            val onTarget = inventory.templateById()
            return templateOrder.values.mapNotNull { ref ->
                val stored = templates.findVersion(workspaceId, ref.id, ref.version) ?: return@mapNotNull null
                val target = onTarget[ref.id]
                if (target != null && target.currentVersion == ref.version && target.bodyHash == stored.bodyHash) {
                    null
                } else {
                    MAPPER.valueToTree(stored)
                }
            }
        }

        /** The same skip rule for pipelines, over the closure in children-before-parents order. */
        fun pipelinePayloads(inventory: PromotionWire.Inventory): List<JsonNode> {
            val onTarget = inventory.pipelineByName()
            // One read for the whole closure — `released_at` is per (pipeline, version).
            val releasedAt = pipelines.releasedAtFor(workspaceId, pipelineOrder.values.map { it.id to it.version })
            return pipelineOrder.values.mapNotNull { pinned ->
                val target = onTarget[pinned.name]
                if (target != null && target.currentVersion == pinned.version && target.bodyHash == pinned.bodyHash) {
                    null
                } else {
                    payloadOf(pinned, releasedAt)
                }
            }
        }

        /**
         * The pipeline's stored body plus the three §9.2 lifecycle fields a preserved-version
         * import honours: the identity, the version number, and the hash the receiver
         * recomputes. `released_at` rides along so the target's draft-run derivation stays
         * truthful (§8).
         */
        private fun payloadOf(
            pinned: PinnedPipeline,
            releasedAt: Map<Pair<UUID, Int>, java.time.Instant?>,
        ): JsonNode {
            val node = MAPPER.readTree(pinned.body) as com.fasterxml.jackson.databind.node.ObjectNode
            node.put("id", pinned.id.toString())
            node.put("version", pinned.version)
            node.put("body_hash", pinned.bodyHash)
            releasedAt[pinned.id to pinned.version]?.let { node.put("released_at", it.toString()) }
            return node
        }
    }

    /** A node's datasource references: its `source`, and a write-back `output.datasource`. */
    private fun datasourcesOf(node: Node): List<String> =
        buildList {
            if (node.type != NodeType.PIPELINE && node.source != NodeSource.TEMPDB_LITERAL && node.source.isNotBlank()) {
                add(node.source)
            }
            (node.output as? NodeOutput.Datasource)?.let { add(it.datasource) }
        }

    private fun notReleased(
        name: String,
        version: Int,
        why: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_NOT_RELEASED,
            "Pipeline '$name' cannot be promoted: $why. Drafts are never promoted (versioning §10.3).",
            mapOf("pipeline" to name, "version" to version),
        )

    private companion object {
        val MAPPER = PipelineJson.objectMapper()
    }
}
