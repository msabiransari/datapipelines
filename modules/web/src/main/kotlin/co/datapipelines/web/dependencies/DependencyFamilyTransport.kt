package co.datapipelines.web.dependencies

import co.datapipelines.application.endpoints.EndpointPath
import co.datapipelines.application.lens.LensedView
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.datasources.DatasourceNameGrammar
import co.datapipelines.dependencies.FamilyKey
import co.datapipelines.dependencies.ResolvedIdentity
import co.datapipelines.dependencies.wire.RefusalReason
import co.datapipelines.dependencies.wire.RequestField
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.templates.TemplateNameGrammar
import java.util.UUID

/**
 * The selector a family's route admits (record §11.1 request table): the field that names the root, and whether a
 * `version` is required (artifact families) or forbidden (schedule, datasource).
 */
enum class SelectorShape(
    val keyField: RequestField,
    val versioned: Boolean,
) {
    /** `name` + `version` — pipeline, template, parameter_set, visualization, dashboard, api_endpoint (the path). */
    NAME_AT_VERSION(RequestField.NAME, versioned = true),

    /** `name` alone — datasource. */
    NAME(RequestField.NAME, versioned = false),

    /** `id` alone — schedule. */
    ID(RequestField.ID, versioned = false),
    ;

    /** The reason a native-validator refusal of [keyField] carries. */
    val keyRefusal: RefusalReason get() = if (keyField == RequestField.ID) RefusalReason.ID_INVALID else RefusalReason.NAME_INVALID
}

/** A family's NATIVE grammar for its root key: the canonical lookup key, or null when the grammar refuses the value. */
fun interface NativeKey {
    fun canonical(value: String): String?
}

/**
 * How a LENSED principal's root is admitted (record §12 as amended by erratum (8)(b): the lens decides the ROOT only;
 * nothing lens-related enters the walk).
 */
sealed interface RootLens {
    /** No lens applies (datasource): no `LensedView` is obtained, so an unreachable promotion target cannot fail it. */
    data object None : RootLens

    /** A family whose native reads are lensed. Both halves answer false for a root the lens does not admit. */
    interface Lensed : RootLens {
        /** Before the walk, from the request alone — e.g. the name arm, or a native lensed read by the request's key. */
        fun admitsRequest(
            principal: AuthenticatedPrincipal,
            view: LensedView,
            request: DependenciesRequest,
        ): Boolean

        /**
         * After the walk, from the RESOLVED root: the native id-keyed lensed version read of a family whose detail GET
         * reads by id (the name half alone cannot see a narrowing lens's "RELEASED only" rule). Default: admitted.
         */
        fun admitsResolved(
            principal: AuthenticatedPrincipal,
            view: LensedView,
            request: DependenciesRequest,
            root: ResolvedIdentity,
        ): Boolean = true
    }
}

/** One family's transport facts — everything G1's neutral descriptor deliberately does not carry. */
data class FamilyTransportRow(
    val family: FamilyKey,
    val selector: SelectorShape,
    val nativeKey: NativeKey,
    /** The code that family's own detail read answers for an absent object today — the hidden root's answer too (§12). */
    val notFoundCode: String,
    val rootLens: RootLens,
) {
    /** The literal path segment of the family's route: its wire key. */
    val segment: String get() = family.wire
}

/**
 * The per-family transport table (#875 G4): keyed by [FamilyKey], one row per family a route serves. Built from native
 * symbols — the families' own grammars and not-found constants — never from copies.
 */
class DependencyFamilyTransport(
    rows: Collection<FamilyTransportRow>,
) {
    private val byFamily: Map<FamilyKey, FamilyTransportRow> = rows.associateBy { it.family }

    init {
        require(byFamily.size == rows.size) { "one transport row per family" }
    }

    val families: Set<FamilyKey> get() = byFamily.keys

    /** The row of [family]; a family with no row has no route, so asking for one is a wiring defect. */
    fun row(family: FamilyKey): FamilyTransportRow = requireNotNull(byFamily[family]) { "no transport row for family ${family.wire}" }

    /** The root lens checks of the seven lensed production families — built where the native services are wired. */
    data class ProductionRootLenses(
        val pipeline: RootLens.Lensed,
        val template: RootLens.Lensed,
        val parameterSet: RootLens.Lensed,
        val visualization: RootLens.Lensed,
        val dashboard: RootLens.Lensed,
        val apiEndpoint: RootLens.Lensed,
        val schedule: RootLens.Lensed,
    )

    /** The native grammars, each the family's own definition. */
    object NativeKeys {
        /** pipeline-contract.md §3.2 — also the documented grammar of parameter sets, visualizations and dashboards. */
        val PIPELINE_NAME = NativeKey { value -> value.takeIf(PipelineNameGrammar::matches) }

        /** templates.md §3.2. */
        val TEMPLATE_NAME = NativeKey { value -> value.takeIf(TemplateNameGrammar::matches) }

        /** rest-api.md §19.1 — the stored spelling (the one `/api` prefix stripped) is the family's lookup key. */
        val API_PATH = NativeKey { value -> EndpointPath.parse(value).getOrNull()?.pattern }

        /** The canonical 36-character lower-case UUID form, and nothing that merely parses as one. */
        val SCHEDULE_ID = NativeKey { value -> value.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } }

        /** datasources.md §3.3. */
        val DATASOURCE_NAME = NativeKey { value -> value.takeIf(DatasourceNameGrammar::matches) }
    }

    companion object {
        private fun versioned(
            family: FamilyKey,
            key: NativeKey,
            notFoundCode: String,
            lens: RootLens,
        ) = FamilyTransportRow(family, SelectorShape.NAME_AT_VERSION, key, notFoundCode, lens)

        /** The eight production rows. */
        fun production(lenses: ProductionRootLenses): DependencyFamilyTransport =
            DependencyFamilyTransport(
                listOf(
                    versioned(FamilyKey.PIPELINE, NativeKeys.PIPELINE_NAME, PipelineErrorCodes.Execution.NOT_FOUND, lenses.pipeline),
                    versioned(FamilyKey.TEMPLATE, NativeKeys.TEMPLATE_NAME, PipelineErrorCodes.Template.NOT_FOUND, lenses.template),
                    versioned(
                        FamilyKey.PARAMETER_SET,
                        NativeKeys.PIPELINE_NAME,
                        PipelineErrorCodes.Parameters.NOT_FOUND,
                        lenses.parameterSet,
                    ),
                    versioned(
                        FamilyKey.VISUALIZATION,
                        NativeKeys.PIPELINE_NAME,
                        PipelineErrorCodes.Visualization.NOT_FOUND,
                        lenses.visualization,
                    ),
                    versioned(FamilyKey.DASHBOARD, NativeKeys.PIPELINE_NAME, PipelineErrorCodes.Dashboard.NOT_FOUND, lenses.dashboard),
                    versioned(FamilyKey.API_ENDPOINT, NativeKeys.API_PATH, PipelineErrorCodes.Endpoint.NOT_FOUND, lenses.apiEndpoint),
                    FamilyTransportRow(
                        FamilyKey.SCHEDULE,
                        SelectorShape.ID,
                        NativeKeys.SCHEDULE_ID,
                        PipelineErrorCodes.Schedule.NOT_FOUND,
                        lenses.schedule,
                    ),
                    FamilyTransportRow(
                        FamilyKey.DATASOURCE,
                        SelectorShape.NAME,
                        NativeKeys.DATASOURCE_NAME,
                        PipelineErrorCodes.Datasource.NOT_FOUND,
                        RootLens.None,
                    ),
                ),
            )
    }
}
