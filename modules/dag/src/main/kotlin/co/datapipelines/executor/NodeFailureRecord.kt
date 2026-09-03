package co.datapipelines.executor

import co.datapipelines.pipeline.NodeSource
import co.datapipelines.pipeline.NodeType
import com.fasterxml.jackson.annotation.JsonProperty
import java.sql.SQLException

/**
 * The node half of the failure record (057 / T85): which node failed, against what.
 *
 * Attach facts **where they exist**: the node runner decorates an escaping
 * [NodeFailedSignal] with the context it holds at the failure site (the datasource dialect
 * once the registry resolved it, the rendered SQL once the template produced it), and
 * [PipelineExecutor.failNode] fills anything still missing from the node itself when it
 * records the failure. Every carrier — `node_failed`, the terminal `pipeline_failed`, and
 * `error_json` — then ships the SAME record unchanged.
 *
 * Serializes snake_case for the wire; the fields are additive to the `error` object of
 * rest-api §6.4.4/§6.4.6 and absent (not null) when unknown.
 */
data class NodeErrorContext(
    val id: String,
    /** `DQL` / `DML` / `DDL` / `PIPELINE` — [NodeType.wire]. */
    val type: String,
    /** The datasource name for a `source: "<name>"` node; null for `tempdb` and PIPELINE nodes. */
    val datasource: String? = null,
    /** The datasource's dialect once resolved, or the tempdb engine's dialect; null when unknown. */
    val dialect: String? = null,
    /** The pinned template's id — a path, e.g. `acme/finance/monthly_revenue`; null for PIPELINE nodes. */
    val template: String? = null,
    @field:JsonProperty("template_version") @get:JsonProperty("template_version")
    val templateVersion: Int? = null,
) {
    companion object {
        /** Builds the record's node context from the executable node plus the dialect in hand, if any. */
        fun of(
            node: ExecutableNode,
            dialect: String? = null,
        ): NodeErrorContext =
            NodeErrorContext(
                id = node.id,
                type = node.type.wire,
                datasource = (node.source as? NodeSource.Datasource)?.name,
                dialect = dialect,
                template = node.template.id.ifEmpty { null },
                templateVersion = node.template.version.takeIf { it > 0 },
            )
    }
}

/**
 * The exception half of the failure record (057 / T85): the class, message, top stack frames,
 * and the `caused_by` chain of the ORIGINAL failure — the text the server log already carries,
 * now transported to the client too.
 *
 * `caused_by` is a FLAT ordered list, outermost cause first, root cause LAST — the orientation
 * `Throwable.getCause` walks. Humans read it reversed (root first); that reversal is a display
 * concern (the editor's inspector), never a wire concern.
 *
 * Both bounds are load-bearing, and each has a house precedent:
 *  - [FRAMES_CAP] caps every level's frames — 40 frames ≈ 4 KB, bounding the record an SSE
 *    frame, an `execution_events` row and an `error_json` column each carry per level.
 *  - [CHAIN_WALK_LIMIT] caps the cause-chain walk, exactly as
 *    `ConnectionLease.CHAIN_WALK_LIMIT` bounds that walk: a pathological chain (a driver
 *    looping causes, or an exception built under `-Xss` pressure) must not turn the failure
 *    record into an amplifier.
 */
data class ExceptionDetail(
    @field:JsonProperty("class") @get:JsonProperty("class") @param:JsonProperty("class")
    val className: String,
    val message: String? = null,
    val frames: List<String> = emptyList(),
    @field:JsonProperty("caused_by") @get:JsonProperty("caused_by") @param:JsonProperty("caused_by")
    val causedBy: List<ExceptionDetail> = emptyList(),
) {
    companion object {
        /** Top stack frames carried per chain level (057: "capped (say 40 per level)"). */
        const val FRAMES_CAP = 40

        /**
         * Levels of the `cause` chain walked. The `ConnectionLease.CHAIN_WALK_LIMIT` precedent
         * (16) is the house number for a bounded cause/nextException walk; real driver chains
         * are 1–3 deep, so this bound never bites a legitimate failure.
         */
        const val CHAIN_WALK_LIMIT = 16

        /**
         * Builds the detail for [throwable] with both bounds applied.
         *
         * `SQLException.getNextException` chains are deliberately NOT walked: the mapper's
         * `describe` already folds the driver's text into the message half, and walking a
         * second chain here would double-count driver output the record already carries.
         */
        fun of(throwable: Throwable): ExceptionDetail {
            val chain = ArrayList<ExceptionDetail>(CHAIN_WALK_LIMIT)
            val seen = HashSet<Throwable>(CHAIN_WALK_LIMIT)
            var cause: Throwable? = throwable.cause
            while (cause != null && chain.size < CHAIN_WALK_LIMIT && seen.add(cause)) {
                chain += level(cause)
                cause = cause.cause
            }
            return level(throwable, chain)
        }

        private fun level(
            t: Throwable,
            causedBy: List<ExceptionDetail> = emptyList(),
        ): ExceptionDetail =
            ExceptionDetail(
                className = t.javaClass.name,
                // Same bound and same reasoning as ErrorCodeMapper.MAX_MESSAGE_CHARS: the
                // message is driver-authored text of unbounded length (H2/MSSQL/Oracle append
                // the whole failing statement); 2000 keeps the diagnostic, drops the amplifier.
                message = t.message?.take(ErrorCodeMapper.MAX_MESSAGE_CHARS),
                frames = t.stackTrace.take(FRAMES_CAP).map(StackTraceElement::toString),
                causedBy = causedBy,
            )
    }
}

/**
 * `datapipelines.executions.error-detail` (Configuration §3.11): how much of the failure
 * record travels.
 *
 * [FULL] is the default because this is a self-hosted product whose users are engineers —
 * the stack trace IS the diagnostic (057/T85: the owner had to open the database to learn
 * why three demo executions failed). [STRUCTURED] omits `exception` and `sql` and keeps
 * everything else, for deployments whose pipeline authors are not trusted to see driver
 * internals.
 */
enum class ErrorDetail(
    val wire: String,
) {
    FULL("full"),
    STRUCTURED("structured"),
    ;

    companion object {
        /** Resolves a wire value, or null when unknown (binding rejects those at startup). */
        fun fromWire(value: String): ErrorDetail? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Enriches a mapped error with the failure-record facts a node runner holds at the failure
 * site (057): the node context and, at [ErrorDetail.FULL], the rendered SQL in `:name` form.
 *
 * Never overwrites facts already attached — the datasource path decorates first (it knows the
 * dialect), this outer wrapper only fills what is still null.
 */
internal fun MappedError.withNodeFacts(
    failedNode: ExecutableNode,
    dialect: String?,
    renderedSql: String?,
    detail: ErrorDetail,
): MappedError {
    val context = this.node ?: NodeErrorContext.of(failedNode, dialect)
    val sql =
        when {
            this.sql != null -> this.sql
            detail != ErrorDetail.FULL || renderedSql == null -> null
            renderedSql.length > MAX_SQL_CHARS -> renderedSql.take(MAX_SQL_CHARS) + TRUNCATION_MARKER
            else -> renderedSql
        }
    return copy(node = context, sql = sql)
}

/**
 * Bound on the rendered-SQL half of the failure record. The render budget permits up to 64M
 * characters (the engine backstop); echoing that into an SSE frame, an `execution_events` row
 * and `error_json` per failed node is the same amplifier [ErrorCodeMapper.MAX_MESSAGE_CHARS]
 * exists to remove. 16K characters holds any human-authored statement whole; the marker names
 * the cut when a generated one exceeds it.
 */
internal const val MAX_SQL_CHARS = 16_384

/** Appended when the rendered SQL was longer than [MAX_SQL_CHARS], so the cut is never silent. */
internal const val TRUNCATION_MARKER = "\n-- truncated at 16384 characters"
