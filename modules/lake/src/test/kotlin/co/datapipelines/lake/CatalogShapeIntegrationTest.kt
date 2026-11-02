package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.iceberg.PartitionSpec
import org.apache.iceberg.Schema
import org.apache.iceberg.catalog.Namespace
import org.apache.iceberg.catalog.TableIdentifier
import org.apache.iceberg.inmemory.InMemoryFileIO
import org.apache.iceberg.jdbc.JdbcCatalog
import org.apache.iceberg.types.Types
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * A0 (#785): the catalog tables V53 creates are exactly the shape Iceberg 1.12.0's `JdbcCatalog` reads WITHOUT
 * issuing DDL, and DP's catalog properties never make it issue any.
 *
 * Each case runs in a database of its own: `JdbcCatalog.updateSchemaIfRequired` looks for the `iceberg_type`
 * column through `DatabaseMetaData.getColumns` in ANY schema, so schemas of one database would see each other.
 * An event trigger on `ddl_command_end` records every DDL command the library's connections run.
 *
 * - V53's shape (parsed from the shipped file), DP's properties: zero DDL; create, commit, load all work.
 * - The five-column (V0) shape, DP's properties: zero DDL (the library warns and runs V0) — and the boot check
 *   refuses that shape (`LakeBootCheckIntegrationTest`).
 * - RED: the V0 shape with `jdbc.schema-version=V1` — the library ALTERs the table at start-up.
 * - RED: `jdbc.init-catalog-tables=true` on an empty database — the library CREATEs its tables; DP's properties on
 *   the same empty database create nothing.
 */
class CatalogShapeIntegrationTest {
    @Test
    fun `DP's catalog properties carry no schema version and never initialise tables`() {
        val props = catalogs(LakeTestDb.catalogDatabase()).catalogProperties()
        props shouldContain (LakeCatalogs.INIT_CATALOG_TABLES to "false")
        props shouldNotContainKey LakeCatalogs.SCHEMA_VERSION
    }

    @Test
    fun `V53's shape runs create, commit and load with zero DDL`() {
        val db = database("a0_shipped", icebergDdl())
        withSession(db) { catalog ->
            catalog.createNamespace(Namespace.of("ns"))
            val table = catalog.createTable(TableIdentifier.of("ns", "t"), SCHEMA, PartitionSpec.unpartitioned())
            table.updateProperties().set("k", "v").commit()
            catalog.loadTable(TableIdentifier.of("ns", "t")).properties()["k"] shouldBe "v"
        }
        ddl(db).shouldBeEmpty()
        query(db, "SELECT iceberg_type FROM iceberg_tables") shouldBe listOf("TABLE")
    }

    @Test
    fun `the five-column shape runs with zero DDL under DP's properties`() {
        val db = database("a0_v0", v0Ddl())
        withSession(db) { catalog ->
            catalog.createNamespace(Namespace.of("ns"))
            catalog.createTable(TableIdentifier.of("ns", "t"), SCHEMA, PartitionSpec.unpartitioned())
        }
        ddl(db).shouldBeEmpty()
        query(db, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'iceberg_tables'") shouldBe listOf("5")
    }

    @Test
    fun `RED - the five-column shape with schema-version V1 is ALTERed at start-up`() {
        val db = database("a0_v0_v1", v0Ddl())
        val props = catalogs(LakeTestDb.catalogDatabase(LakeTestDb.urlFor(db))).catalogProperties() + (LakeCatalogs.SCHEMA_VERSION to "V1")
        open(db, props).close()
        ddl(db) shouldContain "ALTER TABLE"
    }

    @Test
    fun `RED - init-catalog-tables true creates the tables on an empty database, DP's properties create nothing`() {
        val green = database("a0_empty_dp", ddl = "")
        // DP's properties: the library finds no table, creates none, and the first use fails.
        shouldThrowAny { withSession(green) { it.namespaceExists(Namespace.of("ns")) } }
        ddl(green).shouldBeEmpty()

        val red = database("a0_empty_init", ddl = "")
        val props =
            catalogs(LakeTestDb.catalogDatabase(LakeTestDb.urlFor(red))).catalogProperties() + (LakeCatalogs.INIT_CATALOG_TABLES to "true")
        open(red, props).close()
        ddl(red) shouldContain "CREATE TABLE"
    }

    /** A fresh database holding [ddl] (run BEFORE the recorder exists) and the DDL recorder. */
    private fun database(
        name: String,
        ddl: String,
    ): String {
        LakeTestDb.freshDatabase(name)
        LakeTestDb.connect(name).use { connection ->
            connection.createStatement().use { s ->
                if (ddl.isNotBlank()) s.execute(ddl)
                s.execute("CREATE TABLE a0_ddl_log (tag TEXT NOT NULL)")
                s.execute(
                    "CREATE FUNCTION a0_log_ddl() RETURNS event_trigger LANGUAGE plpgsql AS \$\$ DECLARE r record; BEGIN" +
                        " FOR r IN SELECT * FROM pg_event_trigger_ddl_commands() LOOP INSERT INTO a0_ddl_log VALUES (r.command_tag);" +
                        " END LOOP; END \$\$",
                )
                s.execute("CREATE EVENT TRIGGER a0_log_ddl ON ddl_command_end EXECUTE FUNCTION a0_log_ddl()")
            }
        }
        return name
    }

    private fun ddl(db: String): List<String> = query(db, "SELECT tag FROM a0_ddl_log")

    private fun query(
        db: String,
        sql: String,
    ): List<String> =
        LakeTestDb.connect(db).use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    /** Production's [LakeCatalogs] on [db], under a fabricated lease (no fence trigger exists in an A0 database). */
    private fun withSession(
        db: String,
        block: (JdbcCatalog) -> Unit,
    ) {
        catalogs(LakeTestDb.catalogDatabase(LakeTestDb.urlFor(db))).open(FABRICATED).use { it.write(block) }
    }

    /** A bare `JdbcCatalog` on [db] with [props] — the red arms. */
    private fun open(
        db: String,
        props: Map<String, String>,
    ): JdbcCatalog {
        val database = LakeTestDb.catalogDatabase(LakeTestDb.urlFor(db))
        return JdbcCatalog(
            { _ -> InMemoryFileIO() },
            { _ -> FencedJdbcClientPool(database.url, database.connectionProperties(), FABRICATED, 5, 30) },
            false,
        ).apply { initialize(PROPERTIES.name, props) }
    }

    private fun catalogs(database: CatalogDatabase): LakeCatalogs =
        LakeCatalogs(PROPERTIES, database, fileIoFactory = { props -> InMemoryFileIO().apply { initialize(props) } })

    private companion object {
        val PROPERTIES = LakeProperties(enabled = true, storage = LakeProperties.Storage(bucket = "a0"))
        val SCHEMA = Schema(Types.NestedField.required(1, "id", Types.LongType.get()))
        val FABRICATED = LeaseHandle(LeaseKind.TABLE, "ns.t", UUID.randomUUID(), UUID.randomUUID())

        /** V53's two `CREATE TABLE iceberg_…` statements, verbatim from the shipped migration. */
        fun icebergDdl(): String {
            val v53 = LakeTestDb.v53()
            val statements =
                Regex("""CREATE TABLE iceberg_\w+ \(.*?\n\);""", RegexOption.DOT_MATCHES_ALL).findAll(v53).map { it.value }.toList()
            statements.size shouldBe 2
            return statements.joinToString("\n")
        }

        /** V53's shape without `iceberg_type`: Iceberg's V0 (five-column) catalog table. */
        fun v0Ddl(): String {
            val shipped = icebergDdl()
            val v0 = shipped.replace(Regex("""\n\s+iceberg_type\s+VARCHAR\(5\),"""), "")
            v0 shouldNotBe shipped
            return v0
        }
    }
}
