package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import org.slf4j.LoggerFactory

/** `properties.dialect.managed` — the key that marks the server-owned managed lake datasource (#818, record §6). */
const val MANAGED_DIALECT_KEY = "managed"

/** True for the server-owned managed lake datasource: a LAKE row whose `properties.dialect.managed` is true. */
fun Datasource.isManagedLake(): Boolean =
    dialect == Dialect.LAKE && properties.dialect[MANAGED_DIALECT_KEY]?.toString()?.lowercase() == "true"

/** What [DatasourceRegistry.syncManagedLake] did at boot (#818 A1). */
enum class ManagedLakeSync {
    /** No row held the name: the managed row was created. */
    CREATED,

    /** The managed row existed and differed from configuration: it was rewritten and its pools retired. */
    RESYNCED,

    /** The managed row already matched configuration: nothing was written. */
    UNCHANGED,

    /** A row that is NOT the managed one holds the name, live or soft-deleted: the caller refuses boot. */
    NAME_TAKEN,
}

/**
 * #818 (dp-lake P5, design record §10): the managed datasource's read-side rules, kept out of
 * [DefaultDatasourceRegistry] (at its size ceiling) as pure functions over the [ManagedLakeCatalog]
 * port.
 */
internal class ManagedLakeReads(
    private val catalog: ManagedLakeCatalog,
) {
    private val log = LoggerFactory.getLogger(ManagedLakeReads::class.java)

    /**
     * The catalog read a generation is built from. A failure is `lake.unavailable` with the cause in
     * details, and the caller builds NO generation (record §10: never from anything unverified).
     */
    @Suppress("TooGenericExceptionCaught") // the port is an injected seam; any failure means "unverified"
    fun read(datasourceName: String): List<ManagedLakeTable> =
        try {
            catalog.managedTables(datasourceName)
        } catch (e: RuntimeException) {
            val cause = deepestThrowableMessage(e).take(MAX_CAUSE_CHARS)
            throw DatapipelinesException(
                ManagedLakeErrorCodes.UNAVAILABLE,
                "The managed lake '$datasourceName' is unavailable: the catalog read a new generation needs failed — $cause",
                mapOf("datasource" to datasourceName, "cause" to cause),
                e,
            )
        }

    /**
     * The planner's input for the managed datasource: every EXTERNAL row of [registered] as today,
     * plus every AVAILABLE managed table with its view reading the catalog's metadata location.
     * Each omitted managed table is logged (`lake.table_unavailable_omitted`); its registry row's
     * `last_error` is NOT written, because an omission is not a view failure.
     */
    fun plannerInput(
        datasourceName: String,
        registered: List<LakeRegisteredTable>,
        managed: List<ManagedLakeTable>,
    ): List<LakeRegisteredTable> {
        val omitted = managed.filter { it.unavailableReason != null }
        omitted.forEach { table ->
            log.info(
                "event=lake.table_unavailable_omitted datasource={} table={} reason=\"{}\" " +
                    "message=\"managed table has no view in this generation\"",
                datasourceName,
                table.qualifiedName,
                table.unavailableReason,
            )
        }
        val available =
            managed.filter { it.unavailableReason == null }.map { table ->
                LakeRegisteredTable(
                    namespace = table.namespace,
                    name = table.name,
                    format = table.format,
                    // The catalog row the compare-and-swap writes, never the projection column.
                    location = requireNotNull(table.metadataLocation) { "an available managed table has a catalog row" },
                    partitionColumn = table.partitionColumn,
                    lastError = table.lastError,
                    managed = true,
                    notice = table.deprecationMessage,
                )
            }
        return (registered.filter { !it.managed } + available).sortedWith(TREE_ORDER)
    }

    companion object {
        /** The bound on a failure cause carried into an error's details. */
        private const val MAX_CAUSE_CHARS = 500

        /** Tree order, the order the registry read returns: namespace segments, then name. */
        private val TREE_ORDER = compareBy<LakeRegisteredTable>({ it.namespace.joinToString(".") }, { it.name })

        /**
         * Whether [stored] already carries everything [desired] configures, the secret aside (the
         * caller compares the decrypted secret itself): the fields the registrar owns.
         */
        fun sameManagedShape(
            stored: Datasource,
            desired: Datasource,
        ): Boolean =
            stored.displayName == desired.displayName &&
                stored.description == desired.description &&
                stored.jdbcUrl == desired.jdbcUrl &&
                stored.username == desired.username &&
                stored.credentialKind == desired.credentialKind &&
                stored.properties.dialect == desired.properties.dialect &&
                stored.properties.hikari == desired.properties.hikari &&
                stored.properties.jdbc == desired.properties.jdbc &&
                stored.isReadonly == desired.isReadonly &&
                stored.ownerWorkspaceId == desired.ownerWorkspaceId
    }
}
