package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.pipeline.WriteSurface
import java.time.Instant
import java.util.UUID

/**
 * The two artifact families this module persists — ONE lifecycle over two table pairs (V42, the V39 shape
 * twice). Everything the generic [ArtifactRepository] and [ArtifactLifecycle] need to know about a family is
 * here: its tables and constraint names (closed constants — the SQL interpolates them and nothing else; every
 * value is a bind), and the codes each lifecycle refusal carries.
 */
enum class ArtifactKind(
    /** The noun a message uses — "visualization", "dashboard". */
    val noun: String,
    /** The index table (`visualizations`). */
    val index: String,
    /** The versions table (`visualization_versions`). */
    val versions: String,
    /** The versions table's foreign-key column (`visualization_id`). */
    val fk: String,
    val codes: ArtifactCodes,
) {
    VISUALIZATION(
        noun = "visualization",
        index = "visualizations",
        versions = "visualization_versions",
        fk = "visualization_id",
        codes =
            ArtifactCodes(
                bodyInvalid = VisualizationErrorCodes.BODY_INVALID,
                nameInvalid = VisualizationErrorCodes.NAME_INVALID,
                nameTaken = VisualizationErrorCodes.NAME_TAKEN,
                notFound = VisualizationErrorCodes.NOT_FOUND,
                versionConflict = VisualizationErrorCodes.VERSION_CONFLICT,
                notDraft = VisualizationErrorCodes.VERSION_NOT_DRAFT,
                notReleased = VisualizationErrorCodes.VERSION_NOT_RELEASED,
                notDiscarded = VisualizationErrorCodes.VERSION_NOT_DISCARDED,
                lastRelease = VisualizationErrorCodes.VERSION_LAST_RELEASE,
                notEligible = VisualizationErrorCodes.VERSION_NOT_ELIGIBLE,
                idTaken = VisualizationErrorCodes.IMPORT_ID_TAKEN,
                authoringDisabled = VisualizationErrorCodes.AUTHORING_DISABLED,
                dependencyNotReleased = VisualizationErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            ),
    ),
    DASHBOARD(
        noun = "dashboard",
        index = "dashboards",
        versions = "dashboard_versions",
        fk = "dashboard_id",
        codes =
            ArtifactCodes(
                bodyInvalid = DashboardErrorCodes.BODY_INVALID,
                nameInvalid = DashboardErrorCodes.NAME_INVALID,
                nameTaken = DashboardErrorCodes.NAME_TAKEN,
                notFound = DashboardErrorCodes.NOT_FOUND,
                versionConflict = DashboardErrorCodes.VERSION_CONFLICT,
                notDraft = DashboardErrorCodes.VERSION_NOT_DRAFT,
                notReleased = DashboardErrorCodes.VERSION_NOT_RELEASED,
                notDiscarded = DashboardErrorCodes.VERSION_NOT_DISCARDED,
                lastRelease = DashboardErrorCodes.VERSION_LAST_RELEASE,
                notEligible = DashboardErrorCodes.VERSION_NOT_ELIGIBLE,
                idTaken = DashboardErrorCodes.IMPORT_ID_TAKEN,
                authoringDisabled = DashboardErrorCodes.AUTHORING_DISABLED,
                dependencyNotReleased = DashboardErrorCodes.RELEASE_DEPENDENCY_NOT_RELEASED,
            ),
    ),
    ;

    /** `uq_<index>_workspace_name` — its violation is [ArtifactCodes.nameTaken]. */
    val nameConstraint: String get() = "uq_${index}_workspace_name"

    /** `<index>_pkey` — its violation on an import is [ArtifactCodes.idTaken] (C29). */
    val idConstraint: String get() = "${index}_pkey"

    /** `uq_<versions>_one_draft` and `<versions>_pkey` — either refuses a first-writer race's loser. */
    val draftIndex: String get() = "uq_${versions}_one_draft"
    val versionKey: String get() = "${versions}_pkey"
}

/** The catalogued code of every lifecycle refusal, per family (§13.22 / §13.23). */
data class ArtifactCodes(
    val bodyInvalid: String,
    val nameInvalid: String,
    val nameTaken: String,
    val notFound: String,
    val versionConflict: String,
    val notDraft: String,
    val notReleased: String,
    val notDiscarded: String,
    val lastRelease: String,
    val notEligible: String,
    val idTaken: String,
    val authoringDisabled: String,
    val dependencyNotReleased: String,
)

/**
 * One index row (`visualizations` / `dashboards`, metadata-db §4.28/§4.30). [displayName] / [description] index
 * the CURRENT version's body; [currentVersion] is the sticky pointer (D60), null until the first release. There
 * is no stored entity status: the lifecycle derives it.
 */
data class ArtifactRecord(
    val id: UUID,
    val workspaceId: UUID,
    val name: String,
    val displayName: String,
    val description: String,
    val currentVersion: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: UUID,
)

/** One version row's lifecycle metadata, without the body (the `ParameterSetVersionDetail` twin). */
data class ArtifactVersionDetail(
    val artifactId: UUID,
    val version: Int,
    val status: PipelineVersionStatus,
    /** SHA-256 (hex) of the JSONB text projection of the stored body — the precondition token (versioning §4). */
    val bodyHash: String,
    val createdAt: Instant,
    val createdBy: UUID,
    val releasedAt: Instant? = null,
    val releasedBy: UUID? = null,
    val discardedAt: Instant? = null,
    val discardedBy: UUID? = null,
    val updatedBy: UUID? = null,
    val updatedAt: Instant? = null,
    val createdVia: String = WriteSurface.SESSION.wire,
    val updatedVia: String = WriteSurface.SESSION.wire,
)

/** A stored version with its body and the index row it belongs to — what an authoring read answers. */
data class ArtifactVersion<B>(
    val record: ArtifactRecord,
    val detail: ArtifactVersionDetail,
    val body: B,
)

/** One page of a lensed listing and the truthful total behind it (#399's name search). */
data class ArtifactPage<B>(
    val items: List<ArtifactVersion<B>>,
    val total: Int,
)

/** One virtual folder of an artifact tree — a name prefix, derived per request from the live artifacts beneath it. */
data class ArtifactFolder(
    val path: String,
    val segment: String,
    val count: Int,
)

/** A live artifact and the RELEASED version its pointer names — the promoter lens's input (versioning §10.2). */
data class CurrentArtifactVersion(
    val id: UUID,
    val name: String,
    val displayName: String,
    val version: Int,
    val bodyHash: String,
)

/** How a family's body is stored and indexed — the one thing the generic repository cannot know. */
interface BodyCodec<B : Any> {
    /** The stored form — [ArtifactJson.writeBody]; the database hashes its JSONB projection. */
    fun write(body: B): String

    /** Binds a stored body. */
    fun read(json: String): B

    fun displayName(body: B): String

    fun description(body: B): String?

    /** The two families' codecs. */
    object Visualization : BodyCodec<VisualizationBody> {
        override fun write(body: VisualizationBody): String = ArtifactJson.writeBody(body)

        override fun read(json: String): VisualizationBody = ArtifactJson.readVisualization(json)

        override fun displayName(body: VisualizationBody): String = body.displayName

        override fun description(body: VisualizationBody): String? = body.description
    }

    object Dashboard : BodyCodec<DashboardBody> {
        override fun write(body: DashboardBody): String = ArtifactJson.writeBody(body)

        override fun read(json: String): DashboardBody = ArtifactJson.readDashboard(json)

        override fun displayName(body: DashboardBody): String = body.displayName

        override fun description(body: DashboardBody): String? = body.description
    }
}
