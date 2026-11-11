package co.datapipelines.integration

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * #874 G3 — V59 down and up on a `pg_dump` copy of the running test application's metadata (the
 * [ManagedLakeMigrationRehearsal] mold); its source database is never changed. The copy gets one soft-deleted datasource
 * before the down step if it has none, so the backfill is proven over a soft-deleted row (the brief's non-vacuity floor).
 * After the shipped V59 text runs again: no row without an incarnation, one distinct incarnation per row, generation 1
 * everywhere, and every other column of every table byte-equal to the copy before the down step.
 */
internal object DatasourceIncarnationMigrationRehearsal {
    /** The two V59 columns, left out of the before/after comparison: the up step mints them afresh by design. */
    private val V59_COLUMNS = listOf("incarnation_id", "generation")

    @Suppress("LongMethod", "NestedBlockDepth") // Keep dump, seed, down/up, the counts and scoped cleanup in one ordered rehearsal.
    fun run(createdBy: UUID) {
        val pg = SharedE2e.postgres
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val name = "scratch_g3_$suffix"
        val dump = "/tmp/dp874_$suffix.dump"
        val copy = SharedE2e.scratchDatabase("g3_$suffix")
        try {
            pg
                .execInContainer(
                    "pg_dump",
                    "--username=${pg.username}",
                    "--dbname=${pg.databaseName}",
                    "--format=custom",
                    "--file=$dump",
                ).exitCode shouldBe 0
            pg
                .execInContainer(
                    "pg_restore",
                    "--username=${pg.username}",
                    "--dbname=$name",
                    "--no-owner",
                    "--clean",
                    "--if-exists",
                    dump,
                ).exitCode shouldBe 0
            val (url, user, key) = copy
            DriverManager.getConnection(url, user, key).use { connection ->
                if (count(connection, "SELECT count(*) FROM datasources WHERE is_deleted") == 0) {
                    connection.prepareStatement(SEED_DELETED).use {
                        it.setObject(1, createdBy)
                        it.executeUpdate()
                    }
                }
                val deleted = count(connection, "SELECT count(*) FROM datasources WHERE is_deleted")
                val rows = count(connection, "SELECT count(*) FROM datasources")
                deleted shouldBeGreaterThanOrEqual 1
                val before = snapshot(connection)
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE dp874_history59 AS SELECT * FROM flyway_schema_history WHERE version = '59'")
                    statement.execute("ALTER TABLE datasources DROP CONSTRAINT uq_datasources_incarnation")
                    statement.execute("ALTER TABLE datasources DROP COLUMN generation")
                    statement.execute("ALTER TABLE datasources DROP COLUMN incarnation_id")
                    statement.execute("DELETE FROM flyway_schema_history WHERE version = '59'")
                }
                count(connection, V59_COLUMN_COUNT) shouldBe 0
                count(connection, "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_datasources_incarnation'") shouldBe 0
                val migration = migrationText()
                connection.createStatement().use { statement ->
                    statement.execute(migration)
                    statement.execute("INSERT INTO flyway_schema_history SELECT * FROM dp874_history59")
                    statement.execute("DROP TABLE dp874_history59")
                }
                val nulls = count(connection, "SELECT count(*) FROM datasources WHERE incarnation_id IS NULL")
                val distinct = count(connection, "SELECT count(DISTINCT incarnation_id) FROM datasources")
                val deletedWithIncarnation =
                    count(connection, "SELECT count(DISTINCT incarnation_id) FROM datasources WHERE is_deleted")
                val notFirst = count(connection, "SELECT count(*) FROM datasources WHERE generation <> 1")
                withClue("V59 up on the copy: rows=$rows deleted=$deleted") {
                    count(connection, V59_COLUMN_COUNT) shouldBe 2
                    nulls shouldBe 0
                    distinct shouldBe rows
                    deletedWithIncarnation shouldBe deleted
                    notFirst shouldBe 0
                    snapshot(connection) shouldBe before
                }
                println(
                    "case=v59_demo_copy datasources=$rows soft_deleted=$deleted down=absent " +
                        "incarnation_null=$nulls distinct_incarnations=$distinct generation_not_1=$notFirst preserved=all_other_columns",
                )
            }
        } finally {
            pg.execInContainer("rm", "-f", "--", dump).exitCode shouldBe 0
            pg.createConnection("").use { connection ->
                connection.createStatement().use { it.execute("DROP DATABASE \"$name\" WITH (FORCE)") }
            }
        }
    }

    /** The shipped V59 file, byte for byte. */
    private fun migrationText(): String =
        repoRoot().resolve("modules/app/src/main/resources/db/migration/V59__datasource_incarnation.sql").toFile().readText()

    /** Every public table as `count:md5` of its rows, the two V59 columns removed from each `datasources` row. */
    private fun snapshot(connection: Connection): Map<String, String> {
        val tables =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename COLLATE \"C\"")
                    .use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        val without = V59_COLUMNS.joinToString(" ") { "- '$it'" }
        return tables.associateWith { table ->
            require(table.matches(Regex("[a-z0-9_]+")))
            val row = "(to_jsonb(t) $without)::text"
            scalar(
                connection,
                "SELECT count(*)::text || ':' || coalesce(md5(string_agg($row, E'\\n' ORDER BY $row COLLATE \"C\")), 'empty') " +
                    "FROM \"$table\" t",
            ) { it.getString(1) }
        }
    }

    private fun count(
        connection: Connection,
        sql: String,
    ): Int = scalar(connection, sql) { it.getInt(1) }

    private fun <T> scalar(
        connection: Connection,
        sql: String,
        read: (ResultSet) -> T,
    ): T =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next())
                read(rows)
            }
        }

    private fun repoRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { it.resolve("settings.gradle.kts").toFile().exists() }

    private const val V59_COLUMN_COUNT =
        "SELECT count(*) FROM information_schema.columns WHERE table_name = 'datasources' " +
            "AND column_name IN ('incarnation_id', 'generation')"

    private const val SEED_DELETED =
        "INSERT INTO datasources (name, display_name, dialect, jdbc_url, credential_kind, created_by, is_deleted) " +
            "VALUES ('rehearsal_deleted_874', 'Rehearsal', 'H2', 'jdbc:h2:mem:rehearsal', 'none', ?, TRUE)"
}
