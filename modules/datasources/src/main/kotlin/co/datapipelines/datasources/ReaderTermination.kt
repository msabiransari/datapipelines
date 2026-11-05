package co.datapipelines.datasources

/**
 * #818d (dp-lake record §10 RULING 4, second amendment, item 7) — `reader-termination`: the longest from the start of
 * a managed generation's hard close to its last reader's DuckDB query stopping. One term of the reads lifetime rule
 * (`LakeRules.checkLakeReads` reads [SECONDS]; never a typed copy, never a configuration key).
 *
 * ## The derivation
 *
 * The close's first act is interrupt-first (`LakeInstanceOwner.interruptReaders`, one thread per handle, before
 * Hikari's shutdown waits on anything), so a reader that is COMPUTING stops within the engine's interrupt check — a
 * vector, milliseconds. A reader blocked in an object-store request stops only when that request chain returns (the
 * engine's HTTP retry loop does not check the interrupt), and [LakeHttpBounds] bounds the chain:
 *
 * `SECONDS = ceil((LakeHttpBounds.requestChainMillis() + HAND_OFF_MARGIN_MILLIS) / 1000) = ceil((90 100 + 1 000) / 1000) = 92`.
 *
 * [HAND_OFF_MARGIN_MILLIS] covers what sits between the hard-close decision and the interrupt landing: the hand-off
 * to a `dp-pool-close` thread, the start of one interrupt thread per handle, the driver's lock on the connection, and
 * the engine's next interrupt check after the request returns. Each is microseconds to milliseconds; one second is
 * a margin, not a measurement, and the stalled-store integration test observes the whole term on the real reader.
 *
 * `ReaderTerminationDerivationTest` recomputes [SECONDS] from the [LakeHttpBounds] constants and fails on any drift.
 */
object ReaderTermination {
    /** The hand-off and interrupt-landing margin above, milliseconds. */
    const val HAND_OFF_MARGIN_MILLIS: Long = 1_000L

    /** The derivation above, in seconds. */
    const val SECONDS: Long = 92L
}
