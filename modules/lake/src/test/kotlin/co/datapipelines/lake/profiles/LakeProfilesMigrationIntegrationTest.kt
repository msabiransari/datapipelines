package co.datapipelines.lake.profiles

import co.datapipelines.lake.LakeTestDb
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

/**
 * P1 (brief 956 A): V61 on a fresh database with no user, and on an upgrade database whose managed tables carry the
 * old four-key `policy_json`. Each case migrates its own empty database with the shipped files, so the state checked
 * is the migration's and nothing a suite wrote.
 */
class LakeProfilesMigrationIntegrationTest {
    @Test
    fun `on a fresh database with no user both built-ins exist with system actors and production is the one default`() {
        val jdbc = migrated("lake_profiles_fresh", upTo = Int.MAX_VALUE)
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
        val rows =
            jdbc.queryForList(
                "SELECT name, is_default, seed_pending, created_by, updated_by, values_json::text AS values_json FROM lake_profiles " +
                    "ORDER BY name",
            )
        rows.map { it["name"] } shouldBe listOf("development", "production")
        rows.forEach { row ->
            (row["created_by"] to row["updated_by"]) shouldBe (null to null)
        }
        val (development, production) = rows
        (production["is_default"] to production["seed_pending"]) shouldBe (true to true)
        (development["is_default"] to development["seed_pending"]) shouldBe (false to false)
        LakeProfileDocument.parse(production["values_json"] as String) shouldBe LakeProfileFixtures.builtIn()
        val dev = LakeProfileDocument.parse(development["values_json"] as String)
        dev.maintenanceWindow.text shouldBe "00:00-00:00"
        dev shouldBe LakeProfileFixtures.builtIn().copy(maintenanceWindow = dev.maintenanceWindow)
        jdbc.queryForObject("SELECT count(*) FROM lake_profiles WHERE is_default", Int::class.java) shouldBe 1
    }

    @Test
    fun `on an upgrade database every existing table gets production, an assignment time and an empty policy`() {
        val jdbc = migrated("lake_profiles_upgrade", upTo = 60)
        val user = UUID.randomUUID()
        val namespace = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, 'p1@example.com', 'P1', 'google', 'p1')",
            user,
        )
        jdbc.update("INSERT INTO lake_namespaces (id, name, created_by) VALUES (?, 'sales', ?)", namespace, user)
        listOf("orders", "returns").forEach { name ->
            jdbc.update(
                "INSERT INTO lake_tables_managed (id, namespace_id, name, policy_json, created_by) VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                UUID.randomUUID(),
                namespace,
                name,
                OLD_POLICY,
                user,
            )
        }
        applyFrom(jdbc, 61)
        val tables =
            jdbc.queryForList(
                "SELECT t.policy_json::text AS policy_json, t.profile_assigned_at, p.name AS profile FROM lake_tables_managed t " +
                    "JOIN lake_profiles p ON p.id = t.profile_id",
            )
        tables.size shouldBe 2
        tables.forEach { row ->
            row["profile"] shouldBe "production"
            row["policy_json"] shouldBe "{}"
            row["profile_assigned_at"] shouldNotBe null
        }
        jdbc.queryForObject("SELECT name FROM lake_profiles WHERE is_default", String::class.java) shouldBe "production"
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'lake_tables_managed' AND column_name = 'profile_id'",
            String::class.java,
        ) shouldBe "NO"
    }

    private fun migrated(
        database: String,
        upTo: Int,
    ): JdbcTemplate {
        val jdbc = JdbcTemplate(DriverManagerDataSource(LakeTestDb.urlFor(LakeTestDb.freshDatabase(database)), "dp", LakeTestDb.password))
        versioned().filter { it.first <= upTo }.forEach { jdbc.execute(it.second.readText()) }
        return jdbc
    }

    private fun applyFrom(
        jdbc: JdbcTemplate,
        version: Int,
    ) = versioned().filter { it.first >= version }.forEach { jdbc.execute(it.second.readText()) }

    private fun versioned() = LakeTestDb.migrations().map { requireNotNull(VERSION.find(it.name)).groupValues[1].toInt() to it }

    private companion object {
        val VERSION = Regex("""^V(\d+)__""")

        /** The four keys `LakeTablePolicy` wrote before V61. */
        const val OLD_POLICY =
            """{"retention": {"protection_days": 7, "orphan_grace_hours": 24},
                "maintenance": {"window": "02:00-05:00", "max_bytes_rewritten_per_run": 53687091200}}"""
    }
}
