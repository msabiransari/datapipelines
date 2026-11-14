package co.datapipelines.lake.profiles

import co.datapipelines.lake.LakeProperties
import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.definitions.LakeDefinitionFixtures
import co.datapipelines.lake.definitions.LakeDefinitionsHarness
import co.datapipelines.lake.definitions.RecordedAudit
import co.datapipelines.pipeline.WriteSurface
import org.springframework.util.unit.DataSize
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The profile and pause services wired as `LakeProfilesAutoConfiguration` wires them, over the module's one Postgres
 * and the definitions harness (tables are created through `LakeDefinitionService`, the production path). [reset]
 * empties the definitions and puts the two built-in profiles back exactly as V60 leaves them, so suites that run after
 * these see the migration's state. [clock] is the pause service's catalog clock (the database's `now()` by default).
 */
internal class LakeProfilesHarness {
    val definitions = LakeDefinitionsHarness()
    val jdbc = definitions.jdbc
    val audit = definitions.audit
    val actor: UUID = definitions.actor
    val profiles = LakeProfiles.over(definitions.named, definitions.tx, audit)

    @Volatile
    var clock: () -> Instant = { LakeTestDb.leases.catalogNowPlus(Duration.ZERO) }

    val pauses = LakeTablePauses(LakeProfileRepository(definitions.named), definitions.tx, audit) { clock() }

    fun seed(
        window: String,
        cap: DataSize,
    ): LakeProfileSeed = LakeProfileSeed(LakeProfileRepository(definitions.named), definitions.tx, LakeProperties.Maintenance(window, cap))

    fun reset() {
        definitions.reset()
        restoreBuiltIns()
        clock = { LakeTestDb.leases.catalogNowPlus(Duration.ZERO) }
        audit.events.clear()
    }

    /** A new managed table `<namespace>.<name>` with draft v1, created through the definition service. */
    fun table(
        name: String,
        namespace: String = "sales",
    ): UUID = definitions.service.createDraft(namespace, name, LakeDefinitionFixtures.ORDERS_V1, actor, WriteSurface.SESSION).tableId

    /** The profile events the sink recorded, in order. */
    fun profileEvents(): List<RecordedAudit> = audit.events.filter { it.event in LakeProfileAuditEvents.ALL }

    fun profileIdOf(tableId: UUID): UUID =
        requireNotNull(jdbc.queryForObject("SELECT profile_id FROM lake_tables_managed WHERE id = ?", UUID::class.java, tableId))

    fun assignedAt(tableId: UUID): Instant =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT profile_assigned_at FROM lake_tables_managed WHERE id = ?",
                java.sql.Timestamp::class.java,
                tableId,
            ),
        ).toInstant()

    fun policyJson(tableId: UUID): String =
        requireNotNull(jdbc.queryForObject("SELECT policy_json::text FROM lake_tables_managed WHERE id = ?", String::class.java, tableId))

    fun profileCount(): Int = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM lake_profiles", Int::class.java))

    /** The two built-ins with V60's values, marks and NULL actors; every other profile removed (no table refers to one). */
    private fun restoreBuiltIns() {
        jdbc.update("UPDATE lake_profiles SET is_default = FALSE WHERE is_default")
        jdbc.update("DELETE FROM lake_profiles WHERE name NOT IN ('production', 'development')")
        restore("production", LakeProfileFixtures.BUILT_IN, default = true)
        restore("development", DEVELOPMENT, default = false)
    }

    private fun restore(
        name: String,
        values: String,
        default: Boolean,
    ) {
        val updated =
            jdbc.update(
                "UPDATE lake_profiles SET values_json = CAST(? AS jsonb), is_default = ?, seed_pending = ?, created_by = NULL, " +
                    "updated_by = NULL WHERE name = ?",
                values,
                default,
                default,
                name,
            )
        check(updated == 1) { "built-in profile $name missing" }
    }

    companion object {
        /** V60's `development` document: the built-in values with the whole-day window. */
        val DEVELOPMENT: String = LakeProfileFixtures.document("maintenance.window" to "\"00:00-00:00\"")
    }
}
