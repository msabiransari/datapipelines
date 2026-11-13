package co.datapipelines.application.lake

import co.datapipelines.datasources.LakeTableCatalog
import co.datapipelines.datasources.SqlTableExtraction
import co.datapipelines.datasources.SqlTableReferenceExtractor
import co.datapipelines.parameters.ParameterDefinition
import co.datapipelines.parameters.ParameterSetRepository
import co.datapipelines.parameters.SelectorProbe
import co.datapipelines.parameters.SelectorRender
import co.datapipelines.parameters.SelectorSourceKind
import co.datapipelines.pipeline.ContextKeys
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.NodeType
import co.datapipelines.pipeline.OrgContext
import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.ParameterBinder
import co.datapipelines.pipeline.PipelineDeserializer
import co.datapipelines.pipeline.PipelineRepository
import co.datapipelines.pipeline.ReadLens
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.release.ReleaseFamily
import co.datapipelines.pipeline.release.ReleasedVersion
import co.datapipelines.pipeline.release.ReleasedVersionObserver
import co.datapipelines.templates.NodeSqlResolution
import co.datapipelines.templates.NodeSqlResolver
import co.datapipelines.templates.TemplateCompleteness
import co.datapipelines.templates.TemplateCompletenessClassifier
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import co.datapipelines.typesystem.ParameterCardinality
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils

/**
 * The lake reference scanner (#778, reference-scanner slice D-A … D-D; dp-lake design record §7 "Pipeline-to-table
 * check"): for one RELEASED pipeline or parameter-set version, which registered lake tables each LAKE rendering reads —
 * written into the index (metadata-db §4.41–§4.42) in the caller's transaction.
 *
 * ## One site at a time
 *
 * A SITE is a pipeline node whose `source` is a LAKE datasource (a PIPELINE node is its child's own version, scanned
 * when that version is; CALCULATOR and SCRIPT nodes run no SQL), or a parameter-set selector whose `source.datasource`
 * is LAKE. Each is (1) rendered the way the editor renders it — [NodeSqlResolver] with no inputs (the §12.6 sample
 * fallback), or [SelectorProbe.render] against the org tier, the date samples and a type sample per parent — (2) handed
 * to the engine's own parser ([SqlTableReferenceExtractor], `json_serialize_sql` on a parse-only connection), (3)
 * classified by its template body ([TemplateCompletenessClassifier]), and (4) resolved against the datasource's registry
 * ([LakeTableReferenceResolver]) and its OWN default catalog ([LakeDefaultCatalogs], #790 — read from the URL, never
 * the parse engine's). A version's status is its weakest site's ([LakeScanStatus]).
 *
 * ## What it never does
 *
 * - **Open a customer connection.** The render reads the metadata DB; the parser is an embedded in-memory engine — so
 *   the scan runs inside a release's or an import's metadata transaction (`ConnectionLease`'s rule is not met).
 * - **Refuse an author's content.** A failed render and refused SQL are a status (UNSCANNABLE), never an exception: a
 *   release is not blocked by what the scanner cannot read (slice D-F). Only a platform fault throws — and then the
 *   release or import rolls back with it, the owner's seam ruling (2026-11-02).
 * - **Log or store rendered SQL.** It may embed §12.6 sample values; reasons carry construct spellings and template
 *   references only, and the parser's own message (which can quote a token) is reduced to its error type.
 */
@Suppress("LongParameterList") // one collaborator per step of the scan (render, parse, classify, resolve, write)
class LakeReferenceScanService(
    private val pipelines: PipelineRepository,
    private val nodeSql: NodeSqlResolver,
    private val templates: TemplateRepository,
    private val parameterSets: ParameterSetRepository,
    private val selectors: SelectorProbe,
    private val datasources: DatasourceRegistry,
    private val defaultCatalogs: LakeDefaultCatalogs,
    private val registry: LakeTableCatalog,
    private val extractor: SqlTableReferenceExtractor,
    private val index: LakeReferenceIndexRepository,
    private val org: OrgContext,
) : ReleasedVersionObserver {
    private val deserializer = PipelineDeserializer()

    /** The release/import seam: a pipeline or parameter-set version became RELEASED; any other family indexes nothing. */
    override fun released(version: ReleasedVersion) {
        if (version.family !in SCANNED_FAMILIES) return
        scan(ScannedVersion(version.workspaceId, version.family, version.entityId, version.version))
    }

    /** Scans [version] and replaces its index rows. Returns what was written. */
    fun scan(version: ScannedVersion): LakeReferenceScan {
        val (name, sites) =
            when (version.family) {
                ReleaseFamily.PIPELINE -> pipelineSites(version)
                ReleaseFamily.PARAMETER_SET -> selectorSites(version)
                else -> error("unreachable: ScannedVersion admits ${SCANNED_FAMILIES.joinToString()} only")
            }
        var ordinal = 0
        val references = sites.flatMap { site -> site.references.map { it.copy(ordinal = ordinal++) } }
        val scan =
            LakeReferenceScan(
                version = version,
                name = name,
                status = sites.maxOfOrNull { it.status } ?: LakeScanStatus.EXACT,
                reasons = sites.flatMap { it.reasons }.take(MAX_REASONS),
                scannerVersion = SCANNER_VERSION,
                references = references,
            )
        index.replace(scan)
        return scan
    }

    private fun pipelineSites(version: ScannedVersion): Pair<String, List<SiteScan>> {
        val record =
            checkNotNull(pipelines.findById(version.workspaceId, version.entityId)) {
                "pipeline ${version.entityId} is not in its workspace"
            }
        val body =
            checkNotNull(pipelines.findVersionBody(version.workspaceId, version.entityId, version.version)) {
                "pipeline ${version.entityId} has no version ${version.version}"
            }
        val sites =
            deserializer
                .readOrThrow(body)
                .nodes
                .filter { it.type !in NON_SQL_NODES && it.template.id.isNotBlank() }
                .filter { datasources.dialectOf(it.source, version.workspaceId) == Dialect.LAKE }
                .map { node ->
                    val site = Site(nodeId = node.id, selector = null, datasource = node.source, template = node.template)
                    when (
                        val resolution =
                            nodeSql.resolve(
                                version.workspaceId,
                                version.entityId,
                                node.id,
                                version.version,
                                parameterInputs = null,
                                pipelineLens = ReadLens.Everything,
                                templateLens = ReadLens.Everything,
                            )
                    ) {
                        is NodeSqlResolution.Rendered -> parsed(version, site, resolution.positionalSql)
                        is NodeSqlResolution.TemplateMissing -> site.unscannable(KIND_TEMPLATE_MISSING)
                        is NodeSqlResolution.RenderFailed -> site.unscannable(KIND_RENDER_FAILED)
                        is NodeSqlResolution.ParameterRejected -> site.unscannable(KIND_RENDER_FAILED)
                        is NodeSqlResolution.NodeMissing, is NodeSqlResolution.ChildPipeline -> site.unscannable(KIND_RENDER_FAILED)
                    }
                }
        return record.name to sites
    }

    private fun selectorSites(version: ScannedVersion): Pair<String, List<SiteScan>> {
        val stored =
            checkNotNull(parameterSets.findVersion(version.workspaceId, version.entityId, version.version)) {
                "parameter set ${version.entityId} has no version ${version.version}"
            }
        val byName = stored.body.parameters.associateBy { it.name }
        val sites =
            stored.body.parameters
                .filter { it.source?.kind == SelectorSourceKind.TEMPLATE }
                .filter { datasources.dialectOf(checkNotNull(it.source?.datasource), version.workspaceId) == Dialect.LAKE }
                .map { parameter ->
                    val source = checkNotNull(parameter.source)
                    val site =
                        Site(
                            nodeId = null,
                            selector = parameter.name,
                            datasource = checkNotNull(source.datasource),
                            template = checkNotNull(source.template),
                        )
                    when (val render = selectors.render(version.workspaceId, site.template, selectorContext(parameter, byName))) {
                        is SelectorRender.Rendered -> parsed(version, site, positional(render.sql))
                        is SelectorRender.Failed -> site.unscannable(KIND_RENDER_FAILED)
                    }
                }
        return stored.record.name to sites
    }

    /**
     * The selector's dry-render context without a probe (no customer connection inside the transaction): the org tier,
     * the platform date samples, and per parent a type sample — or `<name>_count = 1` for a MULTI parent, whose list
     * never reaches a template (record P29). The save-time dry run renders against the parents' PROBED values; a body
     * that branches on them is PARTIAL by the classifier, so the sample answer is never claimed to be the whole story.
     */
    private fun selectorContext(
        parameter: ParameterDefinition,
        byName: Map<String, ParameterDefinition>,
    ): Map<String, Any?> {
        val context = LinkedHashMap<String, Any?>(org.values)
        context[ContextKeys.CURRENT_DATE] = sample(LogicalType.DATE)
        context[ContextKeys.CURRENT_TIMESTAMP] = sample(LogicalType.TIMESTAMP)
        parameter.dependsOn.distinct().forEach { dependency ->
            val parent = byName[dependency] ?: return@forEach
            if (parent.cardinality == ParameterCardinality.MULTI) {
                context["${dependency}_count"] = 1
            } else {
                context[dependency] = sample(parent.type)
            }
        }
        return context
    }

    private fun parsed(
        version: ScannedVersion,
        site: Site,
        sql: String,
    ): SiteScan {
        val template = templates.findVersion(version.workspaceId, site.template.id, site.template.version)
        val classification =
            template?.let { TemplateCompletenessClassifier.classify(it.body, importsLibraries = it.imports.isNotEmpty()) }
        val completenessReasons =
            classification
                ?.findings
                .orEmpty()
                .take(MAX_FINDINGS_PER_SITE)
                .map { LakeScanReason(site.word, it.kind, it.detail) }
        return when (val extraction = extractor.extract(sql)) {
            is SqlTableExtraction.Unscannable -> {
                site.unscannable(KIND_UNPARSEABLE, detail = extraction.errorType, more = completenessReasons)
            }

            is SqlTableExtraction.Parsed -> {
                val registered = registry.registeredTables(site.datasource).map { LakeTableIdentity(it.namespace, it.name) }
                val engine = extractor.engineFacts()
                // Only a catalog-qualified name needs it; read once per site, and only then.
                val defaultCatalog by lazy { defaultCatalogs.defaultCatalogOf(site.datasource, version.workspaceId) }
                val references =
                    extraction.tables.mapNotNull { written ->
                        val catalog = if (written.catalog != null) defaultCatalog else null
                        val resolved =
                            when (val resolution = LakeTableReferenceResolver.resolve(written, registered, engine, catalog)) {
                                LakeTableReferenceResolver.Resolution.NotATable -> return@mapNotNull null
                                LakeTableReferenceResolver.Resolution.Unresolved -> null
                                is LakeTableReferenceResolver.Resolution.Resolved -> resolution.identity
                            }
                        LakeTableReference(
                            ordinal = 0,
                            nodeId = site.nodeId,
                            selector = site.selector,
                            templateId = site.template.id,
                            templateVersion = site.template.version,
                            datasourceName = site.datasource,
                            writtenCatalog = written.catalog,
                            writtenSchema = written.schema,
                            writtenTable = written.table,
                            resolved = resolved,
                            timeTravel = written.timeTravel,
                            statementIndex = written.statementIndex,
                        )
                    }
                val partial = classification == null || classification.completeness == TemplateCompleteness.PARTIAL
                SiteScan(
                    status = if (partial) LakeScanStatus.PARTIAL else LakeScanStatus.EXACT,
                    reasons = completenessReasons,
                    references = references,
                )
            }
        }
    }

    /** A LAKE site: the node or the selector, its datasource name and the template version that renders it. */
    private data class Site(
        val nodeId: String?,
        val selector: String?,
        val datasource: String,
        val template: TemplateRef,
    ) {
        val word: String get() = nodeId?.let { "node:$it" } ?: "selector:$selector"

        fun unscannable(
            kind: String,
            detail: String = "${template.id}@${template.version}",
            more: List<LakeScanReason> = emptyList(),
        ) = SiteScan(LakeScanStatus.UNSCANNABLE, listOf(LakeScanReason(word, kind, detail)) + more, emptyList())
    }

    private data class SiteScan(
        val status: LakeScanStatus,
        val reasons: List<LakeScanReason>,
        val references: List<LakeTableReference>,
    )

    companion object {
        /**
         * Bumped whenever the scanner's answer for the same content can change (a classifier narrowing, a resolver rule);
         * the boot backfill re-scans every row below it (metadata-db §4.41 `scanner_version`). 2 (#790): `c.s.t` resolves
         * against the site's datasource's own default catalog, no longer the parse engine's `memory`.
         */
        const val SCANNER_VERSION = 2

        /** The scanner's own reason kinds beside the classifier's ([TemplateCompletenessClassifier.Kind]). */
        const val KIND_RENDER_FAILED = "render_failed"
        const val KIND_TEMPLATE_MISSING = "template_missing"
        const val KIND_UNPARSEABLE = "unparseable"

        /** `reasons_json` is bounded: per site, then per version. */
        private const val MAX_FINDINGS_PER_SITE = 10
        private const val MAX_REASONS = 200

        private val NON_SQL_NODES = setOf(NodeType.PIPELINE, NodeType.CALCULATOR, NodeType.SCRIPT)

        /** The §12.6 sample of [type], from the ONE table pipelines and the selector dry run use (`ParameterBinder`). */
        private fun sample(type: LogicalType): Any? =
            ParameterBinder(mapOf(SAMPLE_KEY to Parameter(type = type))).sampleContext()[SAMPLE_KEY]

        private const val SAMPLE_KEY = "sample"

        /** A selector renders `:name` binds intact; the parser takes `?` — Spring's own translation, the executor's. */
        private fun positional(sql: String): String =
            NamedParameterUtils.substituteNamedParameters(NamedParameterUtils.parseSqlStatement(sql), EmptySqlParameterSource.INSTANCE)
    }
}
