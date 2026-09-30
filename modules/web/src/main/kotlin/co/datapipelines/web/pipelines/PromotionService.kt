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
import co.datapipelines.templates.Template
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TransformBlocks
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
    /**
     * 178 — §10.2 computed once for the page and the lens; the page reads THIS, never its own
     * copy of the rule. Required (no default): building one needs the set repository, and the
     * lens's set arm is never silently empty (#300).
     */
    private val views: PromotableViews,
    /**
     * 074 — the endpoints published over the pipelines being promoted. Nullable so a deployment
     * wired before 074 (and every unit test that predates it) keeps building endpoint-free
     * batches rather than needing a stub.
     */
    private val endpointPromotion: EndpointPromotion? = null,
    /** #194 lane D — the parameter-set half of promotion (§8.3). Required: set roots are never silently dropped (#300). */
    private val parameterSetPromotion: co.datapipelines.web.parameters.ParameterSetPromotion,
    /** #10 L1c — the transfer families' half of promotion (§12, D61). Required: their roots are never silently dropped. */
    private val visualizationPromotion: co.datapipelines.web.visualizations.VisualizationPromotion,
    private val dashboardPromotion: co.datapipelines.web.visualizations.DashboardPromotion,
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
        /** #194 lane D — the same set for parameter sets (§8.3), the page's rows through the model. */
        val promotableParameterSets: List<Candidate>,
        /** #10 L1c — the admitted dashboards' pins newer than the target: the page's visualization rows. */
        val promotableVisualizations: List<Candidate>,
        /** #10 L1c — the admitted dashboards (pipeline-lens-true AND newer): the page's dashboard rows. */
        val promotableDashboards: List<Candidate>,
        /** How many live pipelines were examined — so an empty listing reads as "in sync", not "broken". */
        val examined: Int,
    )

    /**
     * §10.2 — what this workspace could promote to the configured target, right now.
     *
     * Since 178 the rule lives in [PromotableView] and the inventory comes through the
     * lens's cache (`inventory-cache-ttl-seconds`): the listing a promoter sees here and the
     * set the lens admits on every other screen are one computation. An unreadable target is
     * still this page's error state — the one place a promoter is TOLD the target is down
     * rather than shown an empty list — so it is re-raised with the code and reason the
     * client recorded.
     */
    fun plan(
        workspaceId: UUID,
        workspaceName: String,
    ): Plan {
        val computed = views.compute(workspaceId, workspaceName)
        val (view, inventory) =
            when (computed) {
                is PromotableViews.Computed.Ready -> {
                    computed.view to computed.inventory
                }

                is PromotableViews.Computed.Unavailable -> {
                    throw ApiException(
                        computed.code,
                        "The promotion target at ${client.targetBaseUrl} could not be read: ${computed.reason}.",
                        mapOf("target" to client.targetBaseUrl, "reason" to computed.reason),
                    )
                }
            }
        return Plan(
            targetBaseUrl = client.targetBaseUrl,
            targetDeployment = inventory.deployment,
            targetAuthoringEnabled = inventory.authoringEnabled,
            workspace = workspaceName,
            promotable = view.pipelines.map { Candidate(it.name, it.displayName, it.localVersion, it.targetVersion) },
            promotableParameterSets =
                view.parameterSets.map { Candidate(it.name, it.displayName, it.localVersion, it.targetVersion) },
            promotableVisualizations =
                view.visualizations.map { Candidate(it.name, it.displayName, it.localVersion, it.targetVersion) },
            promotableDashboards = view.dashboards.map { Candidate(it.name, it.displayName, it.localVersion, it.targetVersion) },
            examined = view.examinedPipelines,
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
    ): PromotionWire.Applied = promote(workspaceId, workspaceName, names, emptyList())

    fun promote(
        workspaceId: UUID,
        workspaceName: String,
        names: List<String>,
        /**
         * #194 lane D — the parameter-set roots, pushed after the templates and before the
         * pipelines (§8.3). A DELIBERATE overload, not a defaulted parameter: the promotion
         * E2E invokes `promote` reflectively by the three-argument signature, and a Kotlin
         * default would silently remove it.
         */
        parameterSetNames: List<String>,
    ): PromotionWire.Applied = promote(workspaceId, workspaceName, names, parameterSetNames, emptyList(), emptyList())

    /**
     * #10 L1c — the full form: the two transfer families' roots ride the batch after the sets and
     * pipelines (D61's order), each dashboard root bringing its pinned visualizations as dependency
     * ENTRIES of the visualization arm (deduplicated against the roots, O1) and its own payload alone
     * into the dashboard arm, so the receiver's reader binds each entry as what it is and lands the
     * visualizations before the dashboards that pin them. Another DELIBERATE overload, for the same
     * reason its siblings are: the promotion E2Es invoke `promote` reflectively BY SIGNATURE, and a
     * defaulted parameter would silently erase the shape the callers reflect on.
     */
    fun promote(
        workspaceId: UUID,
        workspaceName: String,
        names: List<String>,
        parameterSetNames: List<String>,
        /** The visualization roots — the page's visualization table (lens-true: hidden names REFUSE, never drop). */
        visualizationNames: List<String>,
        /** The dashboard roots — each brings its pinned visualizations as dependency entries (§10.4's skip rule). */
        dashboardNames: List<String>,
    ): PromotionWire.Applied {
        require(names.isNotEmpty() || parameterSetNames.isNotEmpty() || visualizationNames.isNotEmpty() || dashboardNames.isNotEmpty()) {
            "promote() needs at least one root"
        }
        // FRESH, never the lens's cached copy: §10.3's guards run against the target as it is
        // now, and the same view the page computes is rebuilt over that fresh answer.
        val inventory = client.inventory(workspaceName)
        refuseAuthoringTarget(inventory)

        val view = views.compute(workspaceId, inventory)
        val closure = Closure(workspaceId, view)
        names.distinct().forEach { name -> closure.addRoot(name, inventory) }
        // #194 lane D — the set roots AFTER the templates: their pins merge into the batch's
        // template closure, the payloads ride the set slot (§8.3's order). #300: the collaborator
        // is required — set roots are never silently dropped from a batch.
        val targets = inventory.parameterSetByName()
        // A set root the lens hides, that does not exist, or that has no promotable release is
        // REFUSED like a pipeline root — the parameters' catalogued 404 naming the NAME the caller
        // submitted, nothing about what the lens hides (the 312 security pass: it used to be
        // silently dropped, and the operator's count and the audit row under-reported the request).
        val setEntries =
            parameterSetNames
                .distinct()
                .map { name ->
                    parameterSetPromotion.entryFor(workspaceId, name, targets[name])
                        ?: throw ApiErrors.parameterNotFound(name)
                }
        setEntries
            .flatMap { parameterSetPromotion.templatePins(it) }
            .forEach(closure::addTemplate)
        // #10 L1c — the transfer roots' entries and their template pins, merged into the batch's
        // closure: [addTransferRoots] states the guards and the refuse-not-drop rule.
        val (visualizationEntries, dashboardEntries) =
            addTransferRoots(workspaceId, view, inventory, closure, visualizationNames, dashboardNames)
        verifyDatasources(closure, inventory)

        val batch =
            PromotionWire.Batch(
                sourceEnv = deploymentName,
                keyFingerprint = PromotionServerKeys.fingerprint(promotionProperties.target.serverKey),
                workspace = workspaceName,
                templates = closure.templatePayloads(inventory),
                parameterSets = setEntries,
                pipelines = closure.pipelinePayloads(inventory),
                endpoints = endpointPromotion?.entriesFor(workspaceId, closure.pipelineNames()).orEmpty(),
                visualizations = visualizationEntries,
                dashboards = dashboardEntries,
            )
        log.info(
            "event=pipeline.promotion.pushing target={} workspace={} roots={} templates={} sets={} pipelines={}" +
                " endpoints={} visualizations={} dashboards={}",
            client.targetBaseUrl,
            workspaceName,
            names.size + batch.parameterSets.size + batch.visualizations.size + batch.dashboards.size,
            batch.templates.size,
            batch.parameterSets.size,
            batch.pipelines.size,
            batch.endpoints.size,
            batch.visualizations.size,
            batch.dashboards.size,
        )
        return client.push(batch).also { client.invalidate(workspaceName) }
    }

    /**
     * #10 L1c — the transfer roots' entries and their template pins, merged into the batch's closure.
     * A root the view hides or that has no promotable release REFUSES with the family's 404 naming
     * the submitted name (76e8af98's refuse-not-drop, the families' spelling); a dashboard's missing
     * pin dependency refuses too — the batch must be able to keep the dashboard's promise.
     *
     * O1's arms: a dashboard's pinned visualizations are ENTRIES OF THE VISUALIZATION arm — the
     * receiver binds each `dashboards` entry with the DASHBOARD reader, which refuses a visualization
     * body — deduplicated by wire identity (name + version + body hash) against the explicit
     * visualization roots and against each other; the dashboard's own payload alone fills the
     * dashboard arm.
     */
    private fun addTransferRoots(
        workspaceId: UUID,
        view: PromotableView,
        inventory: PromotionWire.Inventory,
        closure: Closure,
        visualizationNames: List<String>,
        dashboardNames: List<String>,
    ): Pair<List<JsonNode>, List<JsonNode>> {
        val visualizationTargets = inventory.visualizationByName()
        val visualizationEntries =
            visualizationNames
                .distinct()
                .map { name ->
                    visualizationPromotion.entryFor(workspaceId, name, visualizationTargets[name], view)
                        ?: throw ApiErrors.visualizationNotFound(name)
                }
        val dashboardTargets = inventory.dashboardByName()
        val dashboardRoots =
            dashboardNames
                .distinct()
                .map { name ->
                    dashboardPromotion
                        .entriesForRoot(workspaceId, name, dashboardTargets[name], view, visualizationTargets, visualizationPromotion)
                        ?: throw ApiErrors.dashboardNotFound(name)
                }
        val transferEntries =
            distinctByWireIdentity(visualizationEntries + dashboardRoots.flatMap { it.dependencies })
        transferEntries.forEach { entry ->
            visualizationPromotion.templatePins(entry).forEach(closure::addTemplate)
        }
        return transferEntries to dashboardRoots.map { it.dashboard }
    }

    /** The transfer entries distinct by the identity a receiver lands them under — name + version + body hash. */
    private fun distinctByWireIdentity(entries: List<JsonNode>): List<JsonNode> {
        val seen = mutableSetOf<Triple<String, Int, String>>()
        return entries.filter { entry ->
            seen.add(Triple(entry.path("name").asText(), entry.path("version").asInt(), entry.path("body_hash").asText()))
        }
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
        /** §10.2 over the FRESH inventory — the root guard's "is it newer" answer (178: one rule, page and push). */
        private val view: PromotableView,
    ) {
        /** Pinned pipeline versions, children before parents. Key is `name@version`. */
        private val pipelineOrder = LinkedHashMap<String, PinnedPipeline>()

        /** Pinned template versions, imports before importers. Key is `id@version`. */
        private val templateOrder = LinkedHashMap<String, TemplateRef>()

        /** The names of every pipeline this batch carries — what 074's endpoint scoping asks for. */
        fun pipelineNames(): List<String> = pipelineOrder.values.map { it.name }.distinct()

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
        @Suppress("ThrowsCount") // §10.3's guards: each refusal is its own catalogued code, named at the point it is decided
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
            // The page's rule, not a second spelling of it: a root the view does not list is
            // not newer — a lower or equal version, OR the same content under a higher number
            // (§10.2: hash is for machines). Both were "nothing to push" on the page.
            if (target != null && view.pipeline(name) == null) {
                throw ApiException(
                    PipelineErrorCodes.Versioning.PROMOTION_NOT_NEWER,
                    "Pipeline '$name' is at version ${version.version} here and the target already serves " +
                        "version ${target.currentVersion}" +
                        (if (target.bodyHash == version.bodyHash) " with the same content" else "") +
                        ". Same-version pushes are a bug, not a no-op.",
                    mapOf("pipeline" to name, "version" to version.version, "target_version" to target.currentVersion),
                )
            }
            addPipeline(record.id, name, version.version)
        }

        /** Walks children first, then templates, then records this pipeline (§10.4 order). */
        @Suppress("ThrowsCount") // every absent dependency in the closure is its own 404/409, not one merged failure
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
                    pipelines.findByNameAnyStatus(workspaceId, child.name)
                        ?: throw ApiErrors.pipelineNotFound(child.name)
                addPipeline(childRecord.id, child.name, child.version)
            }
            parsed.nodes.forEach { node -> addTemplate(node.template) }

            pipelineOrder[key] = PinnedPipeline(id, name, version, detail.bodyHash, body, parsed)
        }

        /** A template version and the transitive `imports_json` closure beneath it. */
        fun addTemplate(ref: TemplateRef) {
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
                    templatePayloadOf(stored)
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
         * One template version, built FIELD BY FIELD rather than serialized wholesale.
         *
         * Two reasons, and the first is a bug this replaced: `Template` carries `created_at` as
         * an `Instant`, and the pipeline module's mapper has no JSR-310 module, so
         * `valueToTree` threw at push time — a defect only an end-to-end push could reach.
         * The second reason outlives the first: `created_at` and `created_by` are the SOURCE
         * deployment's, and neither means anything on the target. The import path reads exactly
         * the fields below (a `TemplateDraft` plus §9.2's `version` and `body_hash`), so
         * sending exactly those is both correct and honest about what crosses.
         */
        private fun templatePayloadOf(stored: Template): JsonNode {
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
            // 7b: the transform blocks are version content (inside the hash the receiver
            // recomputes), so the payload carries them exactly when the type is a transform.
            stored.contract?.let { node.set<JsonNode>("contract", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
            stored.invariants?.let { node.set<JsonNode>("invariants", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
            stored.tests?.let { node.set<JsonNode>("tests", TransformBlocks.mapper.valueToTree<JsonNode>(it)) }
            // 7e: the cited facts ride the payload OUTSIDE the hash (transform-nodes §2.3); the
            // receiver keeps the ids that resolve there and drops the rest (owner ruling 2026-09-25).
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

    /**
     * [version] is nullable because since D55 the commonest reason a pipeline "has no released
     * version" is that nobody has released it yet — `current_version` is null, not a number
     * pointing at a draft. `details.version` then carries an empty string rather than a 0 that
     * would name a version no pipeline can have.
     */
    private fun notReleased(
        name: String,
        version: Int?,
        why: String,
    ): ApiException =
        ApiException(
            PipelineErrorCodes.Versioning.PROMOTION_NOT_RELEASED,
            "Pipeline '$name' cannot be promoted: $why. Release it from the UI first — " +
                "drafts are never promoted (versioning §10.3).",
            mapOf("pipeline" to name, "version" to (version?.toString() ?: "")),
        )

    private companion object {
        val MAPPER = PipelineJson.objectMapper()
    }
}
