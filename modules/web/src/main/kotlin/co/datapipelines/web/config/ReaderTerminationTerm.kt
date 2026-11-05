package co.datapipelines.web.config

import co.datapipelines.datasources.ReaderTermination

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, item 7) — the `reader-termination` term of the reads lifetime
 * rule, as the configuration layer reads it. `app` may depend on `web` only (module-structure.md §5.10), so
 * `LakeRules.checkLakeReads` cannot name the `datasources` constant directly; this forwards it by reference, never as
 * a typed number, the same way [ReaperDelay] is the scheduling layer's term. The derivation lives on
 * [ReaderTermination] (from the LAKE adapter's HTTP bounds), pinned by `ReaderTerminationDerivationTest`.
 */
object ReaderTerminationTerm {
    /** [ReaderTermination.SECONDS], by reference. */
    const val SECONDS: Long = ReaderTermination.SECONDS
}
