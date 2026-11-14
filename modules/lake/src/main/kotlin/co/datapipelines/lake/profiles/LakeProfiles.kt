package co.datapipelines.lake.profiles

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.definitions.LakeDefinitionErrors
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * The deployment's lake profiles (lake.md "Profiles"; record §11a call 35; brief 956 D1, D2, D7, D8): read a table's
 * profile, create and edit a profile, move the default mark, assign a table. No route, tool or permission reaches
 * these methods before P8 (cut L6); every write takes the acting user's id for its audit row and writes that row in
 * its own transaction, once per change (a save that changes nothing writes nothing).
 *
 * A change takes effect at the next read and rewrites nothing: no Iceberg commit, no revision row, no protection
 * deadline (record call 35, "Not in a profile").
 */
class LakeProfiles internal constructor(
    private val profiles: LakeProfileRepository,
    private val tx: TransactionTemplate,
    private val audit: AuditEventSink,
) {
    /** The profile [tableId] follows now; `lake.definition.not_found` when there is no such table. */
    fun forTable(tableId: UUID): LakeProfile =
        LakeProfileDocument.parse(profiles.valuesForTable(tableId) ?: throw LakeDefinitionErrors.notFound(tableId))

    fun find(name: String): StoredLakeProfile? = profiles.find(name)

    fun list(): List<StoredLakeProfile> = profiles.list()

    /** The name of [tableId]'s profile; `lake.definition.not_found` when there is no such table. */
    fun profileNameOf(tableId: UUID): String = (profiles.table(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)).profileName

    /** Creates profile [name] from a complete [valuesJson]; it is not the default. */
    fun create(
        name: String,
        valuesJson: String,
        actor: UUID,
    ): StoredLakeProfile {
        if (!NAME.matches(name)) throw LakeProfileErrors.invalid("name", "grammar", "a profile name is [a-z0-9_]{1,63}")
        val values = LakeProfileDocument.parse(valuesJson)
        return inTransaction {
            val created =
                profiles.insert(name, LakeProfileDocument.toJson(values), actor)
                    ?: throw LakeProfileErrors.invalid("name", "taken", "a profile named $name already exists")
            audit.log(
                LakeProfileAuditEvents.CREATED,
                userId = actor,
                details = mapOf("profile_id" to created.id.toString(), "profile" to name),
            )
            created
        }
    }

    /**
     * Replaces profile [name]'s values with the complete [valuesJson]. The keys whose values changed are audited; an
     * identical document changes nothing. An edited `production` is never seeded again (D3).
     */
    fun edit(
        name: String,
        valuesJson: String,
        actor: UUID,
    ): StoredLakeProfile {
        val values = LakeProfileDocument.parse(valuesJson)
        return inTransaction {
            val current = profiles.findForUpdate(name) ?: throw LakeProfileErrors.notFound(name)
            val changed = changedKeys(current.values, values)
            if (changed.isEmpty()) return@inTransaction current
            val updated = profiles.updateValues(current.id, LakeProfileDocument.toJson(values), actor)
            audit.log(
                LakeProfileAuditEvents.UPDATED,
                userId = actor,
                details = mapOf("profile_id" to current.id.toString(), "profile" to name, "changed" to changed),
            )
            updated
        }
    }

    /**
     * Moves the default mark to [name]: the next new table takes it. The mark is cleared from the old default and set
     * on [name] in one transaction, so a reader never sees none; there is no way to clear it alone.
     */
    fun makeDefault(
        name: String,
        actor: UUID,
    ) {
        inTransaction {
            val target = profiles.findForUpdate(name) ?: throw LakeProfileErrors.notFound(name)
            if (target.isDefault) return@inTransaction
            val previous = profiles.clearDefault(actor)
            check(profiles.setDefault(target.id, actor) == 1) { "profile $name vanished under its lock" }
            audit.log(
                LakeProfileAuditEvents.UPDATED,
                userId = actor,
                details =
                    mapOf(
                        "profile_id" to target.id.toString(),
                        "profile" to name,
                        "changed" to listOf("default"),
                        "previous_default" to previous,
                    ),
            )
        }
    }

    /** Moves managed table [tableId] to profile [name]; `profile_assigned_at` moves. Assigning its own profile changes nothing. */
    fun assign(
        tableId: UUID,
        name: String,
        actor: UUID,
    ) {
        inTransaction {
            val table = profiles.tableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            val target = profiles.find(name) ?: throw LakeProfileErrors.notFound(name)
            if (table.profileId == target.id) return@inTransaction
            check(profiles.assign(tableId, target.id) == 1) { "managed table $tableId vanished under its lock" }
            audit.log(
                LakeProfileAuditEvents.ASSIGNED,
                userId = actor,
                details =
                    mapOf(
                        "table_id" to tableId.toString(),
                        "namespace" to table.namespace,
                        "table" to table.name,
                        "profile" to name,
                        "previous_profile" to table.profileName,
                    ),
            )
        }
    }

    /** The dotted keys whose canonical values differ, so a change is judged on parsed values, never on JSON spelling. */
    private fun changedKeys(
        before: LakeProfile,
        after: LakeProfile,
    ): List<String> {
        val old = LakeProfileDocument.flatten(before)
        return LakeProfileDocument
            .flatten(after)
            .filter { (key, value) -> old[key] != value }
            .keys
            .toList()
    }

    private fun <T> inTransaction(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return tx.execute { block() } as T
    }

    companion object {
        private val NAME = Regex("^[a-z0-9_]{1,63}$")

        /** The service over [jdbc] (the append runtime passes its bounded template, so `forTable` is bounded too). */
        fun over(
            jdbc: NamedParameterJdbcTemplate,
            tx: TransactionTemplate,
            audit: AuditEventSink,
        ): LakeProfiles = LakeProfiles(LakeProfileRepository(jdbc), tx, audit)
    }
}
