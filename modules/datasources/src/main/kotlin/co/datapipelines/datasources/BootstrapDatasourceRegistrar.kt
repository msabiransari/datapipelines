package co.datapipelines.datasources

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

/**
 * Applies a bootstrap datasources file once per startup (sample-data design §6,
 * datasources.md §8A): **create-if-absent by `name`, never update.**
 *
 * ## The two rules that make this safe to run on every boot
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
 *
 * Nothing here logs a credential: entries are named by `name` and `dialect` only, and
 * [Datasource.toString] redacts the password even if one reached a log line by accident.
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
        entries.forEach { datasource ->
            if (repository.existsIncludingDeleted(datasource.name)) {
                skipped += datasource.name
                log.info(
                    "event=datasource.bootstrap_skipped name={} reason=already_present " +
                        "message=\"a datasource with this name already exists (soft-deleted rows count); " +
                        "bootstrap never updates an existing row\"",
                    datasource.name,
                )
            } else {
                registry.save(datasource, actor)
                registered += datasource.name
                log.info(
                    "event=datasource.bootstrap_registered name={} dialect={} readonly={} scope=global",
                    datasource.name,
                    datasource.dialect.wire,
                    datasource.isReadonly,
                )
            }
        }
        log.info(
            "event=datasource.bootstrap_complete file={} registered={} skipped={}",
            path,
            registered.size,
            skipped.size,
        )
        return Summary(registered = registered, skipped = skipped)
    }

    /** Names only — never entities, so a summary cannot become the log line that leaks a secret. */
    data class Summary(
        val registered: List<String>,
        val skipped: List<String>,
    )
}
