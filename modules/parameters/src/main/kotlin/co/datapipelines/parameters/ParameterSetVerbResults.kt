package co.datapipelines.parameters

/*
 * The parameter-set family's lifecycle verb results (#372) — the same names and shapes as the
 * visualization module's `ArtifactVerbResults` (the pipelines mould's vocabulary), declared here
 * because modules/parameters depends on no visualization module and the repo keeps such small
 * per-module copies (the 332 lane's `CountingDataSource` precedent). The controller records its
 * audit rows from these, never from a re-read after the fact.
 */

/** Where the served pointer moved (D60): the value before the statement and the value after it, read in that statement's transaction. */
data class PointerMove(
    val before: Int?,
    val after: Int?,
)

/** A discard or restore's answer: the version it touched, and where the pointer moved. */
data class VersionMoved(
    val detail: ParameterSetVersionDetail,
    val pointer: PointerMove,
)

/** A switch's answer: the set's name — the record the verb already reads for its 404 — and where the pointer moved. */
data class Switched(
    val name: String,
    val pointer: PointerMove,
)

/**
 * What a draft purge did (versioning §5.4) — the sealed outcome the audit row's `scope` word records
 * (the pipelines mould's rule at `PipelinesController`). The repository knows which arm ran: its own
 * version-count branch is the answer.
 */
sealed interface Purged {
    /** The scope word the audit row records. */
    val scope: String

    /** The draft went; other versions remain, so the set stays. */
    data object Version : Purged {
        override val scope: String get() = "version"
    }

    /** The draft was the ONLY version: the set's row went with it (§3.2). */
    data object Entity : Purged {
        override val scope: String get() = "entity"
    }
}
