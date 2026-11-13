package co.datapipelines.integration

import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** V55 up/down on a pg_dump copy of the populated test application's metadata; never mutates its source database. */
internal object ManagedLakeMigrationRehearsal {
    @Suppress("LongMethod") // Keep dump, down/up, full data comparison and scoped cleanup in one ordered rehearsal.
    fun run() {
        val pg = SharedE2e.postgres
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val name = "scratch_p4_$suffix"
        val dump = "/tmp/dp799_$suffix.dump"
        val copy = SharedE2e.scratchDatabase("p4_$suffix")
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
                ).exitCode shouldBe
                0
            DriverManager.getConnection(copy.jdbcUrl, copy.username, copy.password).use { connection ->
                val before = snapshot(connection)
                scalar(connection, "SELECT count(*) FROM pipeline_versions") { it.getInt(1) > 0 } shouldBe true
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE dp799_history55 AS SELECT * FROM flyway_schema_history WHERE version = '55'")
                    statement.execute("DROP TABLE lake_delivery_attempts")
                    statement.execute("DROP TABLE lake_deliveries")
                    statement.execute("ALTER TABLE pipeline_versions DROP COLUMN writes_managed_lake")
                    statement.execute("DELETE FROM flyway_schema_history WHERE version = '55'")
                }
                scalar(
                    connection,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = 'pipeline_versions' " +
                        "AND column_name = 'writes_managed_lake'",
                ) { it.getInt(1) } shouldBe 0
                val root = repoRoot()
                val migration = root.resolve("modules/app/src/main/resources/db/migration/V55__lake_deliveries.sql").toFile().readText()
                connection.createStatement().use { statement ->
                    statement.execute(migration)
                    statement.execute("INSERT INTO flyway_schema_history SELECT * FROM dp799_history55")
                    statement.execute("DROP TABLE dp799_history55")
                }
                scalar(connection, "SELECT count(*) FROM pipeline_versions WHERE writes_managed_lake IS DISTINCT FROM FALSE") {
                    it.getInt(1)
                } shouldBe 0
                snapshot(connection) shouldBe before
                println(
                    "case=v55_demo_copy tables=${before.size} version_rows=nonempty " +
                        "down=absent up=default_false preserved=all_existing_rows",
                )
            }
        } finally {
            pg.execInContainer("rm", "-f", "--", dump).exitCode shouldBe 0
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { connection ->
                connection.createStatement().use { it.execute("DROP DATABASE \"$name\" WITH (FORCE)") }
            }
        }
    }

    private fun snapshot(connection: Connection): Map<String, String> {
        val tables =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public' " +
                            "AND tablename NOT IN ('lake_deliveries', 'lake_delivery_attempts') ORDER BY tablename COLLATE \"C\"",
                    ).use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
            }
        return tables.associateWith { table ->
            require(table.matches(Regex("[a-z0-9_]+")))
            scalar(
                connection,
                "SELECT count(*)::text || ':' || coalesce(md5(string_agg((to_jsonb(t) - 'writes_managed_lake')::text, " +
                    "E'\\n' ORDER BY (to_jsonb(t) - 'writes_managed_lake')::text COLLATE \"C\")), 'empty') FROM \"$table\" t",
            ) { it.getString(1) }
        }
    }

    private fun <T> scalar(
        connection: Connection,
        sql: String,
        read: (java.sql.ResultSet) -> T,
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
}
