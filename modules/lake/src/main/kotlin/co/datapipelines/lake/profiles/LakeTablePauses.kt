package co.datapipelines.lake.profiles

import co.datapipelines.auth.AuditEventSink
import co.datapipelines.lake.definitions.LakeDefinitionErrors
import co.datapipelines.lake.definitions.LakeTableStatus
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** The two pause keys of a table's `policy_json` (record call 38). */
enum class LakePauseKind(
    val key: String,
    internal val field: String,
) {
    /** Stops compaction and manifest rewriting. */
    COMPACTION("pause.compaction_until", "compaction_until"),

    /** Stops snapshot expiry and orphan removal. */
    CLEANUP("pause.cleanup_until", "cleanup_until"),
}

/** A table's two pauses; null = not paused. */
data class LakeTablePause(
    val compactionUntil: Instant?,
    val cleanupUntil: Instant?,
) {
    fun until(kind: LakePauseKind): Instant? =
        when (kind) {
            LakePauseKind.COMPACTION -> compactionUntil
            LakePauseKind.CLEANUP -> cleanupUntil
        }
}

/**
 * A table's two pauses (lake.md "Profiles"; record call 38; brief 956 D6, D7, D8), the only keys `policy_json` keeps:
 * `{"pause": {"compaction_until": "<instant>", "cleanup_until": "<instant>"}}`, each optional. A set is an instant at
 * most [MAX_AHEAD] after the catalog's clock ([catalogNow], the database's `now()`, never the JVM's), refused
 * `lake.profile.invalid` with the key's path beyond that or on a `purged` table; a clear is always allowed. Each change
 * writes `lake.table.pause_changed` in its transaction; setting the same instant or clearing an absent key changes
 * nothing.
 */
class LakeTablePauses internal constructor(
    private val profiles: LakeProfileRepository,
    private val tx: TransactionTemplate,
    private val audit: AuditEventSink,
    private val catalogNow: () -> Instant,
) {
    fun read(tableId: UUID): LakeTablePause = parse((profiles.table(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)).policyJson)

    fun set(
        tableId: UUID,
        kind: LakePauseKind,
        until: Instant,
        actor: UUID,
    ) {
        val now = catalogNow()
        if (until.isAfter(now.plus(MAX_AHEAD))) {
            throw LakeProfileErrors.invalid(kind.key, "beyond_7_days", "a pause ends at most 7 days after now ($now)")
        }
        change(tableId, kind, until, actor)
    }

    fun clear(
        tableId: UUID,
        kind: LakePauseKind,
        actor: UUID,
    ) = change(tableId, kind, null, actor)

    private fun change(
        tableId: UUID,
        kind: LakePauseKind,
        until: Instant?,
        actor: UUID,
    ) {
        tx.executeWithoutResult {
            val table = profiles.tableForUpdate(tableId) ?: throw LakeDefinitionErrors.notFound(tableId)
            if (until != null && table.status == LakeTableStatus.PURGED) {
                throw LakeProfileErrors.invalid(kind.key, "purged_table", "a purged table takes no pause")
            }
            val current = parse(table.policyJson)
            val previous = current.until(kind)
            if (previous == until) return@executeWithoutResult
            val next =
                when (kind) {
                    LakePauseKind.COMPACTION -> current.copy(compactionUntil = until)
                    LakePauseKind.CLEANUP -> current.copy(cleanupUntil = until)
                }
            check(profiles.updatePolicy(tableId, toJson(next)) == 1) { "managed table $tableId vanished under its lock" }
            audit.log(
                LakeProfileAuditEvents.PAUSE_CHANGED,
                userId = actor,
                details =
                    mapOf(
                        "table_id" to tableId.toString(),
                        "namespace" to table.namespace,
                        "table" to table.name,
                        "key" to kind.key,
                        "until" to until?.toString(),
                        "previous" to previous?.toString(),
                    ),
            )
        }
    }

    internal companion object {
        /** Record call 38: no open-ended pause; the longest is 7 days ahead, renewable. */
        val MAX_AHEAD: Duration = Duration.ofDays(7)

        private const val PAUSE = "pause"
        private val mapper = JsonMapper.builder().build()

        /** Reads the pause keys; any other key in `policy_json` is a store that broke V60's contract. */
        fun parse(policyJson: String): LakeTablePause {
            val root = mapper.readTree(policyJson) as? ObjectNode ?: error("policy_json is not an object")
            check(root.fieldNames().asSequence().all { it == PAUSE }) { "policy_json holds keys other than pause" }
            val pause = root.path(PAUSE)
            check(pause.isMissingNode || pause is ObjectNode) { "policy_json pause is not an object" }
            val fields = LakePauseKind.entries.map { it.field }.toSet()
            check(pause.fieldNames().asSequence().all { it in fields }) { "policy_json pause holds an unknown key" }
            fun instant(kind: LakePauseKind): Instant? =
                pause.path(kind.field).takeIf { it.isTextual }?.let {
                    try {
                        Instant.parse(it.textValue())
                    } catch (failure: DateTimeParseException) {
                        throw IllegalStateException("policy_json ${kind.key} is not an instant", failure)
                    }
                }
            return LakeTablePause(instant(LakePauseKind.COMPACTION), instant(LakePauseKind.CLEANUP))
        }

        fun toJson(pause: LakeTablePause): String {
            val keys =
                LakePauseKind.entries
                    .mapNotNull { kind -> pause.until(kind)?.let { kind.field to it.toString() } }
                    .toMap(LinkedHashMap())
            return mapper.writeValueAsString(if (keys.isEmpty()) emptyMap<String, Any>() else mapOf(PAUSE to keys))
        }
    }
}
