package co.datapipelines.lake.profiles

import co.datapipelines.lake.definitions.LakeTableStatus
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One `lake_profiles` row (metadata-db §4.55) with its values parsed. A NULL actor is the system (D1). */
data class StoredLakeProfile(
    val id: UUID,
    val name: String,
    val isDefault: Boolean,
    val values: LakeProfile,
    val seedPending: Boolean,
    val createdAt: Instant,
    val createdBy: UUID?,
    val updatedAt: Instant,
    val updatedBy: UUID?,
)

/** A managed table's profile reference and its `policy_json`, locked for a profile or pause change. */
internal data class LakeTablePolicyRow(
    val id: UUID,
    val namespace: String,
    val name: String,
    val status: LakeTableStatus,
    val profileId: UUID,
    val profileName: String,
    val policyJson: String,
)

/**
 * The SQL of the profiles and of a table's profile reference and pauses (V60). Every statement is parameterised; a
 * value document is bound as ONE JSONB parameter, never concatenated. Transactions are the caller's.
 */
@Suppress("TooManyFunctions") // one method per statement
internal class LakeProfileRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun find(name: String): StoredLakeProfile? =
        jdbc.query("$SELECT_PROFILE WHERE name = :name", mapOf("name" to name), PROFILE_MAPPER).singleOrNull()

    fun findForUpdate(name: String): StoredLakeProfile? =
        jdbc.query("$SELECT_PROFILE WHERE name = :name FOR UPDATE", mapOf("name" to name), PROFILE_MAPPER).singleOrNull()

    fun list(): List<StoredLakeProfile> = jdbc.query("$SELECT_PROFILE ORDER BY name", PROFILE_MAPPER)

    /** Inserts a profile that is not the default; null when the name is taken. */
    fun insert(
        name: String,
        valuesJson: String,
        actor: UUID,
    ): StoredLakeProfile? =
        try {
            jdbc
                .query(
                    "INSERT INTO lake_profiles (name, values_json, created_by, updated_by) " +
                        "VALUES (:name, CAST(:values AS jsonb), :actor, :actor) RETURNING $PROFILE_COLUMNS",
                    mapOf("name" to name, "values" to valuesJson, "actor" to actor),
                    PROFILE_MAPPER,
                ).single()
        } catch (_: DuplicateKeyException) {
            null
        }

    /** Replaces the values; an edited profile is never seeded again (`seed_pending` cleared, D3). */
    fun updateValues(
        id: UUID,
        valuesJson: String,
        actor: UUID,
    ): StoredLakeProfile =
        jdbc
            .query(
                "UPDATE lake_profiles SET values_json = CAST(:values AS jsonb), seed_pending = FALSE, updated_at = NOW(), " +
                    "updated_by = :actor WHERE id = :id RETURNING $PROFILE_COLUMNS",
                mapOf("id" to id, "values" to valuesJson, "actor" to actor),
                PROFILE_MAPPER,
            ).single()

    /** Clears the mark from the current default; answers its name, or null when none held it. */
    fun clearDefault(actor: UUID): String? =
        jdbc
            .query(
                "UPDATE lake_profiles SET is_default = FALSE, updated_at = NOW(), updated_by = :actor WHERE is_default RETURNING name",
                mapOf("actor" to actor),
            ) { rs, _ -> rs.getString("name") }
            .singleOrNull()

    fun setDefault(
        id: UUID,
        actor: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_profiles SET is_default = TRUE, updated_at = NOW(), updated_by = :actor WHERE id = :id",
            mapOf("id" to id, "actor" to actor),
        )

    /** The values document of [tableId]'s profile, or null when there is no such table. */
    fun valuesForTable(tableId: UUID): String? =
        jdbc
            .query(
                "SELECT p.values_json::text AS values_json FROM lake_tables_managed t JOIN lake_profiles p ON p.id = t.profile_id " +
                    "WHERE t.id = :id",
                mapOf("id" to tableId),
            ) { rs, _ -> rs.getString("values_json") }
            .singleOrNull()

    fun tableForUpdate(tableId: UUID): LakeTablePolicyRow? =
        jdbc
            .query(
                "SELECT t.id, n.name AS namespace, t.name, t.status, t.profile_id, p.name AS profile_name, " +
                    "t.policy_json::text AS policy_json FROM lake_tables_managed t " +
                    "JOIN lake_namespaces n ON n.id = t.namespace_id JOIN lake_profiles p ON p.id = t.profile_id " +
                    "WHERE t.id = :id FOR UPDATE OF t",
                mapOf("id" to tableId),
                TABLE_MAPPER,
            ).singleOrNull()

    fun table(tableId: UUID): LakeTablePolicyRow? =
        jdbc
            .query(
                "SELECT t.id, n.name AS namespace, t.name, t.status, t.profile_id, p.name AS profile_name, " +
                    "t.policy_json::text AS policy_json FROM lake_tables_managed t " +
                    "JOIN lake_namespaces n ON n.id = t.namespace_id JOIN lake_profiles p ON p.id = t.profile_id WHERE t.id = :id",
                mapOf("id" to tableId),
                TABLE_MAPPER,
            ).singleOrNull()

    fun assign(
        tableId: UUID,
        profileId: UUID,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET profile_id = :profile, profile_assigned_at = NOW() WHERE id = :id",
            mapOf("id" to tableId, "profile" to profileId),
        )

    fun updatePolicy(
        tableId: UUID,
        policyJson: String,
    ): Int =
        jdbc.update(
            "UPDATE lake_tables_managed SET policy_json = CAST(:policy AS jsonb) WHERE id = :id",
            mapOf("id" to tableId, "policy" to policyJson),
        )

    /**
     * The start-up seed (D3, cut L5): ONE statement copies the configured window and bytes cap into `production` and
     * clears its mark, only while the mark is set. Answers the seeded document, or null when nothing was pending.
     */
    fun seedProduction(
        window: String,
        maxBytesRewrittenPerRun: Long,
    ): String? =
        jdbc
            .query(
                "UPDATE lake_profiles SET values_json = jsonb_set(jsonb_set(values_json, '{maintenance,window}', " +
                    "to_jsonb(CAST(:window AS text))), '{maintenance,max_bytes_rewritten_per_run}', to_jsonb(CAST(:cap AS bigint))), " +
                    "seed_pending = FALSE, updated_at = NOW() WHERE name = 'production' AND seed_pending " +
                    "RETURNING values_json::text AS values_json",
                mapOf("window" to window, "cap" to maxBytesRewrittenPerRun),
            ) { rs, _ -> rs.getString("values_json") }
            .singleOrNull()

    private companion object {
        const val PROFILE_COLUMNS =
            "id, name, is_default, values_json::text AS values_json, seed_pending, created_at, created_by, updated_at, updated_by"
        const val SELECT_PROFILE = "SELECT $PROFILE_COLUMNS FROM lake_profiles"

        val PROFILE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                StoredLakeProfile(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    isDefault = rs.getBoolean("is_default"),
                    values = LakeProfileDocument.parse(rs.getString("values_json")),
                    seedPending = rs.getBoolean("seed_pending"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    updatedAt = rs.getTimestamp("updated_at").toInstant(),
                    updatedBy = rs.getObject("updated_by", UUID::class.java),
                )
            }

        val TABLE_MAPPER =
            RowMapper { rs: ResultSet, _: Int ->
                LakeTablePolicyRow(
                    id = rs.getObject("id", UUID::class.java),
                    namespace = rs.getString("namespace"),
                    name = rs.getString("name"),
                    status = LakeTableStatus.fromWire(rs.getString("status")),
                    profileId = rs.getObject("profile_id", UUID::class.java),
                    profileName = rs.getString("profile_name"),
                    policyJson = rs.getString("policy_json"),
                )
            }
    }
}
