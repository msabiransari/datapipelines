package co.datapipelines.lake

import org.apache.iceberg.CatalogProperties
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.Table
import org.apache.iceberg.TableProperties
import org.apache.iceberg.aws.s3.S3FileIO
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.io.FileIO
import org.apache.iceberg.jdbc.JdbcCatalog
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Where the catalog tables live: DP's own metadata Postgres (§5.1). The password is never printed. */
class CatalogDatabase(
    val url: String,
    val username: String,
    private val password: String,
) {
    /** The `DriverManager` properties of a catalog connection; never logged. */
    internal fun connectionProperties(): Properties =
        Properties().apply {
            setProperty("user", username)
            setProperty("password", password)
            setProperty("ApplicationName", APPLICATION_NAME)
        }

    override fun toString(): String = "CatalogDatabase(url=$url, username=$username, password=${LakeProperties.redact(password)})"

    companion object {
        /** `pg_stat_activity.application_name` of every catalog connection — how an operator tells them apart. */
        const val APPLICATION_NAME = "datapipelines-lake-catalog"
    }
}

/**
 * Opens Iceberg `JdbcCatalog` instances on DP's Postgres, ONE per lease (§8a): every connection of the instance
 * carries that lease ([FencedJdbcClientPool]), deletes are deferred ([DeferringFileIO]), and the catalog never
 * issues DDL (`jdbc.init-catalog-tables=false`; V53 created the tables in the V1 shape the library reads without
 * altering — A0, `CatalogShapeIntegrationTest`).
 *
 * The catalog name is the lake's logical name ([LakeProperties.name], `iceberg_tables.catalog_name`); the
 * warehouse is the storage root, though every table DP creates passes its location explicitly (call 1).
 */
class LakeCatalogs(
    private val properties: LakeProperties,
    private val database: CatalogDatabase,
    private val fileIoFactory: (Map<String, String>) -> FileIO = ::s3FileIo,
) {
    /** The catalog under [lease]. The caller closes it, and must close it BEFORE releasing the lease. */
    fun open(lease: LeaseHandle): LakeCatalogSession {
        val catalog =
            JdbcCatalog(
                { props -> DeferringFileIO(fileIoFactory(props)) },
                { _ ->
                    FencedJdbcClientPool(
                        url = database.url,
                        connectionProperties = database.connectionProperties(),
                        lease = lease,
                        lockTimeoutSeconds = properties.catalog.lockTimeoutSeconds,
                        statementTimeoutSeconds = properties.catalog.statementTimeoutSeconds,
                    )
                },
                false,
            )
        try {
            catalog.initialize(properties.name, catalogProperties())
        } catch (
            // Iceberg wraps the SQLSTATE the fence raises in several RuntimeException types; classify() reads the cause
            // chain, and anything it does not recognise is rethrown unchanged.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            runCatching { catalog.close() }
            throw LakeCatalogException.classify(e) ?: e
        }
        return LakeCatalogSession(lease, catalog, properties.storage.root)
    }

    /**
     * The catalog's properties: the JDBC URI (the pool ignores it and connects with [CatalogDatabase]; the
     * library requires the key), the warehouse, no DDL, and S3FileIO's keys. Holds the
     * S3 secret when one is configured — never logged, never returned to a caller.
     */
    internal fun catalogProperties(): Map<String, String> {
        val storage = properties.storage
        return buildMap {
            put(CatalogProperties.URI, database.url)
            put(CatalogProperties.WAREHOUSE_LOCATION, storage.root)
            put(INIT_CATALOG_TABLES, "false")
            // SCHEMA_VERSION is deliberately NOT set: with V53's `iceberg_type` column present the library picks V1
            // from the column itself; the property only acts when the column is ABSENT, and then it ALTERs the
            // table — DDL at run time (A0, CatalogShapeIntegrationTest). The boot check refuses that shape instead.
            put(S3_PATH_STYLE, storage.pathStyle.toString())
            put(CLIENT_REGION, storage.region.ifBlank { DEFAULT_SIGNING_REGION })
            put(HTTP_CLIENT_TYPE, "urlconnection")
            if (storage.endpoint.isNotBlank()) put(S3_ENDPOINT, storage.endpoint)
            if (storage.hasStaticCredentials) {
                put(S3_ACCESS_KEY_ID, storage.accessKey)
                put(S3_SECRET_ACCESS_KEY, storage.secretKey)
            }
        }
    }

    companion object {
        /** `JdbcUtil.INIT_CATALOG_TABLES_PROPERTY` (package-private in Iceberg). */
        const val INIT_CATALOG_TABLES = "jdbc.init-catalog-tables"

        /** `JdbcUtil.SCHEMA_VERSION_PROPERTY` (package-private in Iceberg). Never set by DP — see [catalogProperties]. */
        const val SCHEMA_VERSION = "jdbc.schema-version"

        // S3FileIOProperties / AwsClientProperties / HttpClientProperties keys, spelled as E1 ran them.
        private const val S3_ENDPOINT = "s3.endpoint"
        private const val S3_PATH_STYLE = "s3.path-style-access"
        private const val S3_ACCESS_KEY_ID = "s3.access-key-id"
        private const val S3_SECRET_ACCESS_KEY = "s3.secret-access-key"
        private const val CLIENT_REGION = "client.region"
        private const val HTTP_CLIENT_TYPE = "http-client.type"

        /**
         * The SigV4 region when only an endpoint is configured (an S3-compatible store; `LakeRules` admits
         * endpoint-without-region). MinIO and most compatible stores accept it; AWS itself always has a region.
         */
        const val DEFAULT_SIGNING_REGION = "us-east-1"

        private fun s3FileIo(props: Map<String, String>): FileIO = S3FileIO().apply { initialize(props) }
    }
}

/**
 * One `JdbcCatalog` under one lease. Every write goes through [write], which maps the fence's SQLSTATEs and a
 * lost connection to [LakeCatalogException]; after an [LakeCatalogException.OutcomeUnknown] the session refuses
 * further work (its pool may hold the broken connection, and the outcome must be settled first, P4).
 */
class LakeCatalogSession internal constructor(
    val lease: LeaseHandle,
    private val catalog: JdbcCatalog,
    private val storageRoot: String,
) : AutoCloseable {
    private val outcomeUnknown = AtomicBoolean(false)

    /** Runs [block] on the catalog, classifying its failure (pipeline-contract §13.25). */
    fun <T> write(block: (JdbcCatalog) -> T): T {
        check(!outcomeUnknown.get()) { "this lake catalog session saw an unknown outcome; settle before any further write" }
        try {
            return block(catalog)
        } catch (
            // As in open(): the library's exception types vary, the cause chain's SQLSTATE decides; unknown ones rethrow.
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            val classified = LakeCatalogException.classify(e) ?: throw e
            if (classified is LakeCatalogException.OutcomeUnknown) outcomeUnknown.set(true)
            throw classified
        }
    }

    /** Creates [namespace] (single-level, call 2) unless it exists. Needs this session's NAMESPACE lease on it. */
    fun createNamespace(namespace: String) {
        LakeNames.requireName(namespace)
        require(lease.kind == LeaseKind.NAMESPACE && lease.name == namespace) {
            "creating namespace $namespace needs its namespace lease, not $lease"
        }
        write { catalog ->
            val ns = Namespace.of(namespace)
            if (!catalog.namespaceExists(ns)) catalog.createNamespace(ns)
        }
    }

    /**
     * Creates `<namespace>.<table>` at `<storage root>/<namespace>/<tableId>/` (call 1) with the closed property
     * list ([LakeTableProperties.create]). Needs this session's TABLE lease on `<namespace>.<table>`.
     */
    fun createTable(
        namespace: String,
        table: String,
        tableId: UUID,
        schema: Schema,
        spec: PartitionSpec,
        definitionVersion: Long,
    ): Table {
        LakeNames.requireName(namespace)
        LakeNames.requireName(table)
        require(lease.kind == LeaseKind.TABLE && lease.name == LakeNames.tableLeaseName(namespace, table)) {
            "creating table $namespace.$table needs its table lease, not $lease"
        }
        return write { catalog ->
            catalog
                .buildTable(TableIdentifier.of(Namespace.of(namespace), table), schema)
                .withPartitionSpec(spec)
                .withLocation("$storageRoot/$namespace/$tableId")
                .withProperties(LakeTableProperties.create(definitionVersion))
                .create()
        }
    }

    /** Loads `<namespace>.<table>`: no writer lease for the read; managed generations hold reader leases. */
    fun loadTable(
        namespace: String,
        table: String,
    ): Table = write { it.loadTable(TableIdentifier.of(Namespace.of(namespace), table)) }

    override fun close() = catalog.close()
}

/** The managed lake's names (§5.1): namespace and table both `[a-z0-9_]{1,63}`; a table lease is `<ns>.<table>`. */
object LakeNames {
    private val NAME = Regex("^[a-z0-9_]{1,63}$")

    fun requireName(name: String) = require(NAME.matches(name)) { "lake names are [a-z0-9_]{1,63}: '$name'" }

    fun tableLeaseName(
        namespace: String,
        table: String,
    ): String = "$namespace.$table"
}

/** The CLOSED property list every table DP creates carries (§5.1, call 22). Nothing else, never author-editable. */
object LakeTableProperties {
    /** DP's own key: the definition version the table's definition-owned state was applied from (P3). */
    const val DEFINITION_VERSION = "dp.definition.version"

    fun create(definitionVersion: Long): Map<String, String> =
        mapOf(
            TableProperties.COMMIT_NUM_RETRIES to "0",
            TableProperties.METADATA_DELETE_AFTER_COMMIT_ENABLED to "false",
            TableProperties.FORMAT_VERSION to "2",
            TableProperties.PARQUET_COMPRESSION to "zstd",
            DEFINITION_VERSION to definitionVersion.toString(),
        )
}
