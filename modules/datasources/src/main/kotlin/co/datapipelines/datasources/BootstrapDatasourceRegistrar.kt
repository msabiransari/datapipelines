package co.datapipelines.datasources

import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import java.nio.file.Path
import java.util.UUID

/**
 * Applies a bootstrap datasources file once per startup (sample-data design §6,
 * datasources.md §8A): **create-if-absent by `name`, never update.**
 *
 * ## The three rules that make this safe to run on every boot
 *
 * 1. **Never update.** An existing row is left byte-untouched and logged at INFO — even a
 *    soft-deleted one ([DatasourceRepository.existsIncludingDeleted], because `name` is the
 *    primary key so a soft-deleted name is permanently taken), and even one an operator has
 *    edited into a different shape. That is the operator's guarantee: a restart never reverts
 *    their change, and a datasource they deleted never resurrects itself.
 * 2. **Fail-fast, one entry at a time.** Entries are applied in file order; each runs the FULL
 *    §9 validation — test pool build included, because it is [DatasourceRegistry.save] doing the
 *    work, not a startup-only shortcut — and is written before the next is attempted. The first
 *    failure aborts startup. That is deliberate: a half-registered demo is worse than a loud
 *    one, and rule 1 makes the retry idempotent, so fail-fast costs nothing on the way back up.
 * 3. **Credential desync is reconciled by CONNECTING — never by preferring a source**
 *    (added 2026-09-03 for the 2026-09-02 incident; datasources.md §8A.3). For an entry whose
 *    row already exists AND whose file credential differs from the stored one:
 *      - the STORED credential authenticates → the row is left byte-untouched. Rule 1 stands:
 *        the operator's edit works, so it is kept.
 *      - the stored one does NOT authenticate and the FILE's does → **the credential alone is
 *        updated** — not the name, not the display name, not the URL, not the properties, not
 *        the readonly flag — and `event=datasource.bootstrap_credential_resynced` is logged at
 *        WARN naming the datasource.
 *      - neither authenticates → the row is left alone and
 *        `event=datasource.bootstrap_credential_broken` is logged at ERROR naming the
 *        datasource and the environment variable an operator would change. Startup does NOT
 *        fail: the app has to boot for the operator to be able to fix the row. The ERROR line
 *        is the loud part.
 *      - the stored ciphertext cannot be DECRYPTED (wrong master key, a row restored from
 *        another deployment) → the row is left alone and
 *        `event=datasource.bootstrap_credential_unreadable` is logged at ERROR naming the
 *        config key. A credential that could not be READ is not one that failed a LOGIN, and
 *        overwriting it would paper over a key problem every other datasource shares.
 *
 *    ### Why rule 3 is not "always update"
 *
 *    Because that would revert an operator's edit on every boot, which is precisely what rule
 *    1 exists to prevent. Rule 3 is the NARROW exception, and its narrowness is entirely in
 *    the fact that a probe — not a preference — decides. It fires only on a difference, writes
 *    only on a proven failure paired with a proven success, and writes only one column.
 *
 *    ### What went wrong without it
 *
 *    `sample-trips` was registered on 2026-08-30. `SAMPLE_PG_PASSWORD` later changed; the
 *    sample database's role got the NEW password at the next load, the stored row kept the
 *    OLD one, and nothing compared them. Every multi-node demo pipeline failed at CONNECT with
 *    `password authentication failed for user "dp_demo_ro"` while the datasources screen
 *    showed the datasource as fine — listing does not connect. A clean-machine rehearsal
 *    cannot see this class of defect at all: it only exists on the upgrade path.
 *
 * Nothing here logs a credential: entries are named by `name` and `dialect` only, the rule-3
 * lines name an environment variable and never its value, and [Datasource.toString] redacts
 * the password even if one reached a log line by accident.
 */
class BootstrapDatasourceRegistrar(
    private val registry: DatasourceRegistry,
    private val repository: DatasourceRepository,
    private val reader: BootstrapDatasourceFileReader = BootstrapDatasourceFileReader(),
) {
    private val log = LoggerFactory.getLogger(BootstrapDatasourceRegistrar::class.java)

    /**
     * Registers every entry of [path] that does not already exist, attributing each to [actor]
     * (`datasources.created_by`).
     *
     * @return what happened, for the caller's startup log and for tests.
     * @throws BootstrapDatasourceFileException when the file cannot be read or is invalid.
     * @throws DatasourceValidationException when an entry fails §9 validation.
     */
    fun register(
        path: Path,
        actor: UUID,
    ): Summary {
        val entries = reader.read(path)
        val registered = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val resynced = mutableListOf<String>()
        val broken = mutableListOf<String>()
        entries.forEach { entry ->
            val datasource = entry.datasource
            if (repository.existsIncludingDeleted(datasource.name)) {
                skipped += datasource.name
                log.info(
                    "event=datasource.bootstrap_skipped name={} reason=already_present " +
                        "message=\"a datasource with this name already exists (soft-deleted rows count); " +
                        "bootstrap never updates an existing row\"",
                    datasource.name,
                )
                // Rule 3: the row stays the operator's, but a credential that no longer
                // authenticates is not an edit worth preserving. Only a difference gets here,
                // and only a probe decides what happens next.
                reconcileCredential(entry, resynced, broken)
            } else {
                try {
                    registry.save(datasource, actor)
                    registered += datasource.name
                    log.info(
                        "event=datasource.bootstrap_registered name={} dialect={} readonly={} scope=global",
                        datasource.name,
                        datasource.dialect.wire,
                        datasource.isReadonly,
                    )
                } catch (duplicate: DuplicateKeyException) {
                    // Two instances bootstrapping one fresh database race here: both pass the
                    // existence check, one wins the insert. Losing is the EXPECTED outcome on a
                    // multi-instance first boot — the row the winner wrote is exactly what rule 1
                    // ("never update") says to keep, so the loser behaves as if the row had
                    // already been there. Same catch-and-reread shape as LocalPasswordService.
                    skipped += datasource.name
                    log.info(
                        "event=datasource.bootstrap_skipped name={} reason=concurrent_registration " +
                            "message=\"another instance registered this datasource first; keeping its row\"",
                        datasource.name,
                    )
                    log.debug("bootstrap lost the race for {}", datasource.name, duplicate)
                }
            }
        }
        log.info(
            "event=datasource.bootstrap_complete file={} registered={} skipped={} resynced={} broken={}",
            path,
            registered.size,
            skipped.size,
            resynced.size,
            broken.size,
        )
        return Summary(registered = registered, skipped = skipped, resynced = resynced, broken = broken)
    }

    /**
     * Rule 3 for one already-present entry: compare, and when the two credentials differ, let
     * [DatasourceRegistry.resyncBootstrapCredential] decide by connecting.
     *
     * The comparison itself lives in the registry (the encryptor is there); this method owns
     * the LOG, because only the file reader knows which environment variable an operator would
     * change — and the ERROR line is useless without it.
     */
    private fun reconcileCredential(
        entry: BootstrapDatasource,
        resynced: MutableList<String>,
        broken: MutableList<String>,
    ) {
        val name = entry.datasource.name
        val fileCredential = entry.datasource.password ?: return
        when (registry.resyncBootstrapCredential(name, fileCredential)) {
            CredentialResync.RESYNCED -> {
                resynced += name
                log.warn(
                    "event=datasource.bootstrap_credential_resynced name={} env_key={} " +
                        "message=\"the stored credential no longer authenticates and the bootstrap file's does; " +
                        "the credential alone was updated — no other column was touched\"",
                    name,
                    entry.passwordEnvKey ?: "<literal>",
                )
            }

            CredentialResync.BOTH_FAILED -> {
                broken += name
                log.error(
                    "event=datasource.bootstrap_credential_broken name={} env_key={} " +
                        "message=\"neither the stored credential nor the bootstrap file's authenticates; " +
                        "the row was left unchanged. Fix the environment variable named here (or the " +
                        "database role it belongs to) and restart, or correct the datasource in the UI\"",
                    name,
                    entry.passwordEnvKey ?: "<literal>",
                )
            }

            CredentialResync.STORED_UNREADABLE -> {
                broken += name
                log.error(
                    "event=datasource.bootstrap_credential_unreadable name={} " +
                        "message=\"the stored credential could not be decrypted — datapipelines.db.encryption-key is " +
                        "not the key this row was written with. The row was left unchanged; a resync may only replace " +
                        "a credential that FAILED a login, never one it could not read\"",
                    name,
                )
            }

            CredentialResync.STORED_WORKS,
            CredentialResync.CREDENTIAL_MATCHES,
            CredentialResync.NO_LIVE_ROW,
            CredentialResync.NOT_APPLICABLE,
            -> {
                // Deliberately silent. STORED_WORKS is rule 1 doing its job, CREDENTIAL_MATCHES
                // is the ordinary boot, NO_LIVE_ROW is a soft-deleted row staying deleted, and
                // NOT_APPLICABLE is a registry with no ciphertext — none of them is news, and
                // the bootstrap_skipped line above already said the row was kept.
            }
        }
    }

    /** Names only — never entities, so a summary cannot become the log line that leaks a secret. */
    data class Summary(
        val registered: List<String>,
        val skipped: List<String>,
        /** Rule 3: rows whose credential alone was replaced because the stored one had stopped working. */
        val resynced: List<String> = emptyList(),
        /** Rule 3: rows where NEITHER credential authenticates — left untouched, logged at ERROR. */
        val broken: List<String> = emptyList(),
    )
}
