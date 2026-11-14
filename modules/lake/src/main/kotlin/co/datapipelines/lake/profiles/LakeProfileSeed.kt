package co.datapipelines.lake.profiles

import co.datapipelines.lake.LakeProperties
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionTemplate

/**
 * The `production` profile's one-time seed (brief 956 D3, lane cut L5): V61 inserts `production` with the built-in
 * values and `seed_pending`; the first start with the lake enabled copies `datapipelines.lake.maintenance.window` and
 * `.max-bytes-rewritten-per-run` into it in ONE statement guarded by `WHERE seed_pending`, and clears the mark. It
 * runs once per database and never again overwrites a profile (a service edit clears the mark too); two instances
 * starting together write the same values, and the second updates nothing. After it nothing reads those two
 * configuration values (`LakeTuningSourceGuardTest` rule d).
 *
 * The seeded document is parsed in the same transaction, so a value the profile rules refuse (the boot rule refuses a
 * cap under twice the 64 MiB target first, `LakeRules.checkLakeBounds`) rolls the seed back and refuses the start
 * with `lake.profile.invalid` naming the key.
 */
class LakeProfileSeed internal constructor(
    private val profiles: LakeProfileRepository,
    private val tx: TransactionTemplate,
    private val maintenance: LakeProperties.Maintenance,
) {
    /** Seeds `production` when its mark is set; answers whether this call seeded it. */
    fun seed(): Boolean {
        val window = maintenance.window
        val cap = maintenance.maxBytesRewrittenPerRun.toBytes()
        val seeded =
            tx.execute {
                profiles.seedProduction(window, cap)?.also(LakeProfileDocument::parse)
            }
        if (seeded != null) {
            log.info("event=lake.profile.seeded profile=production window={} max_bytes_rewritten_per_run={}", window, cap)
        }
        return seeded != null
    }

    private companion object {
        val log = LoggerFactory.getLogger(LakeProfileSeed::class.java)
    }
}
