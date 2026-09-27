package co.datapipelines.parameters

import co.datapipelines.pipeline.DatasourceFacts
import co.datapipelines.pipeline.DatasourceRegistry
import co.datapipelines.pipeline.DryRenderOutcome
import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.TemplateDryRenderer
import co.datapipelines.pipeline.TemplateLookup
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.TemplateVersionStatuses
import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.Dialect
import co.datapipelines.typesystem.LogicalType
import java.util.UUID

/** One stored template version as the fakes serve it. */
internal data class FakeTemplate(
    val dialect: Dialect? = Dialect.POSTGRES,
    val type: TemplateType = TemplateType.SQL,
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    /** The `:binds` the body carries (what `SqlBindScanner` would find). */
    val binds: List<String> = emptyList(),
    /** Names the body interpolates with `${}` — reported only when also declared (the scanner's contract). */
    val interpolates: List<String> = emptyList(),
)

/**
 * The template registry as the save-time validator sees it — the `TemplateDryRenderer` and
 * `TemplateVersionStatuses` ports over a map. Unknown ids answer `TemplateNotFound`, known ids at an
 * unknown version `VersionNotFound` — the real split (`TemplateDryRendererImpl.lookup`).
 */
internal class FakeTemplates(
    private val versions: MutableMap<TemplateRef, FakeTemplate> = mutableMapOf(),
) : TemplateDryRenderer,
    TemplateVersionStatuses {
    operator fun set(
        ref: TemplateRef,
        template: FakeTemplate,
    ) {
        versions[ref] = template
    }

    override fun lookup(
        workspaceId: UUID,
        ref: TemplateRef,
    ): TemplateLookup {
        val version = versions[ref]
        return when {
            version != null -> TemplateLookup.Found(version.dialect, version.type)
            versions.keys.any { it.id == ref.id } -> TemplateLookup.VersionNotFound
            else -> TemplateLookup.TemplateNotFound
        }
    }

    override fun dryRender(
        workspaceId: UUID,
        ref: TemplateRef,
        context: Map<String, Any?>,
    ): DryRenderOutcome = error("the set validator renders through the SelectorProbe port, never the pipeline's dry render")

    override fun interpolatedParameters(
        workspaceId: UUID,
        ref: TemplateRef,
        declared: Set<String>,
        guarded: Set<String>,
    ): List<String> = versions[ref]?.interpolates.orEmpty().filter { it in declared }

    override fun boundParameters(
        workspaceId: UUID,
        ref: TemplateRef,
    ): List<String> = versions[ref]?.binds.orEmpty()

    override fun statusOf(
        workspaceId: UUID,
        templateId: String,
        version: Int,
    ): PipelineVersionStatus? = versions[TemplateRef(templateId, version)]?.status
}

/** A datasource registry over a name → dialect map, workspace-blind (scoping is the repository suite's to prove). */
internal class FakeDatasources(
    private val dialects: Map<String, Dialect> = mapOf("warehouse" to Dialect.POSTGRES),
) : DatasourceRegistry {
    override fun describe(
        name: String,
        workspaceId: UUID,
    ): DatasourceFacts? = dialects[name]?.let { DatasourceFacts(it) }
}

/** One scripted answer of the probe for one template id. */
internal data class ProbeScript(
    val render: SelectorRender =
        SelectorRender.Rendered(
            "SELECT s AS value, n AS display_value, d AS is_default FROM t WHERE c = :country ORDER BY n",
        ),
    val outcome: SelectorProbeOutcome = SelectorProbeOutcome.Probed(SELECT_COLUMNS, emptyList()),
) {
    companion object {
        val SELECT_COLUMNS =
            listOf(
                ColumnSchema("value", LogicalType.STRING),
                ColumnSchema("display_value", LogicalType.STRING),
                ColumnSchema("is_default", LogicalType.BOOLEAN),
            )

        fun rows(vararg rows: List<Any?>): ProbeScript = ProbeScript(outcome = SelectorProbeOutcome.Probed(SELECT_COLUMNS, rows.toList()))
    }
}

/**
 * A RECORDING [SelectorProbe] — every render and every probe is kept, so a test can prove steps 5–6
 * were CALLED and with what (MISTAKES.md: a strict mock makes a missing call unobservable; this double
 * makes it the assertion). Scripted per template id; an unscripted id answers the default script.
 */
internal class RecordingProbe(
    private val scripts: MutableMap<String, ProbeScript> = mutableMapOf(),
) : SelectorProbe {
    data class Render(
        val template: TemplateRef,
        val context: Map<String, Any?>,
    )

    data class Probe(
        val datasource: String,
        val sql: String,
        val binds: Map<String, Any?>,
        val maxRows: Int,
    )

    val renders = mutableListOf<Render>()
    val probes = mutableListOf<Probe>()

    operator fun set(
        templateId: String,
        script: ProbeScript,
    ) {
        scripts[templateId] = script
    }

    private var lastTemplate: String? = null

    override fun render(
        workspaceId: UUID,
        template: TemplateRef,
        context: Map<String, Any?>,
    ): SelectorRender {
        renders += Render(template, context)
        lastTemplate = template.id
        return (scripts[template.id] ?: ProbeScript()).render
    }

    override fun probe(
        workspaceId: UUID,
        datasource: String,
        sql: String,
        binds: Map<String, Any?>,
        maxRows: Int,
    ): SelectorProbeOutcome {
        probes += Probe(datasource, sql, binds, maxRows)
        return (scripts[lastTemplate] ?: ProbeScript()).outcome
    }
}
