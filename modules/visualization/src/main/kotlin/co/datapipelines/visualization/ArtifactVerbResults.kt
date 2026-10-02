package co.datapipelines.visualization

/*
 * The artifact families' lifecycle verb results (#372) — the pipelines mould's vocabulary
 * (`PipelineService.DiscardResult`, `PipelineReleaseService.Purged`) generalised to what the artifact
 * repositories can answer in the write's own transaction. The controllers record their audit rows from
 * these, never from a re-read after the fact (versioning §7, enums.md §15).
 */

/** Where the served pointer moved (D60): the value before the statement and the value after it, read in that statement's transaction. */
data class PointerMove(
    val before: Int?,
    val after: Int?,
)

/** A discard or restore's answer: the version it touched, and where the pointer moved. */
data class VersionMoved(
    val detail: ArtifactVersionDetail,
    val pointer: PointerMove,
)

/** A switch's answer: the artifact's name — the record the verb already reads for its 404 — and where the pointer moved. */
data class Switched(
    val name: String,
    val pointer: PointerMove,
)

/**
 * What a draft purge did (versioning §5.4) — the sealed outcome the audit row's `scope` word records
 * (the pipelines mould's rule at `PipelinesController`). The repository knows which arm ran: its own
 * `versionCount` branch is the answer.
 */
sealed interface Purged {
    /** The scope word the audit row records. */
    val scope: String

    /** The draft went; other versions remain, so the entity stays. */
    data object Version : Purged {
        override val scope: String get() = "version"
    }

    /** The draft was the ONLY version: the entity row went with it (§3.2). */
    data object Entity : Purged {
        override val scope: String get() = "entity"
    }
}
