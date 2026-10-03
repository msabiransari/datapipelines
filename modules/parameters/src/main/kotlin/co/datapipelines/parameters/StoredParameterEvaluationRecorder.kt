package co.datapipelines.parameters

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import java.sql.SQLException

/**
 * The production [ParameterEvaluationRecorder] (#376): every write goes to the [ParameterEvaluationRepository], and
 * every write is ISOLATED — a storage fault ([DataAccessException]) is one structured ERROR line naming the write, the
 * evaluation, the workspace and the set, with the exception class and the SQLState (never its message: Spring's carries
 * the statement and the driver's text), and the evaluation goes on exactly as it would have (spec §2.3). A failed START
 * answers false, so the evaluation proceeds unrecorded; a failed terminal write leaves the row `RUNNING` for the stale
 * sweep to close `INCOMPLETE`.
 *
 * The one throw is the caller's defect: [EvaluationCaller.PIPELINE] is refused at this entry with an
 * [IllegalArgumentException] until the engine record's §13 consumer binding exists (the owner's §11.10 ruling) — a
 * value declared in the schema so no migration widens it later, never one a caller may produce today.
 */
class StoredParameterEvaluationRecorder(
    private val evaluations: ParameterEvaluationRepository,
) : ParameterEvaluationRecorder {
    override fun started(evaluation: EvaluationStarted): Boolean {
        require(evaluation.attempt.caller != EvaluationCaller.PIPELINE) {
            "the PIPELINE caller is dormant until a pipeline binds a parameter set (the engine record's §13) — nothing produces it yet"
        }
        return isolated(WRITE_START, evaluation.key) { evaluations.insertRunning(evaluation) } ?: false
    }

    override fun queryQueued(query: QueryQueued) {
        isolated(WRITE_QUERY_QUEUED, query.key) { evaluations.insertQuery(query) }
    }

    override fun queryEnded(query: QueryEnded) {
        isolated(WRITE_QUERY_ENDED, query.key) { evaluations.finishQuery(query) }
    }

    override fun ended(evaluation: EvaluationEnded) {
        isolated(WRITE_END, evaluation.key) { evaluations.finish(evaluation) }
        if (evaluation.abandoned.isNotEmpty()) {
            isolated(WRITE_ABANDONED, evaluation.key) { evaluations.finishQueries(evaluation.abandoned) }
        }
    }

    private inline fun <T> isolated(
        write: String,
        key: EvaluationKey,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: DataAccessException) {
            log.error(
                "event=parameter.evaluation_record_failed write={} evaluation_id={} workspace_id={} parameter_set_id={} " +
                    "error={} sql_state={}",
                write,
                key.evaluationId,
                key.workspaceId,
                key.parameterSetId,
                e.javaClass.simpleName,
                sqlState(e),
            )
            null
        }

    private companion object {
        private val log = LoggerFactory.getLogger(StoredParameterEvaluationRecorder::class.java)

        const val WRITE_START = "start"
        const val WRITE_QUERY_QUEUED = "query_queued"
        const val WRITE_QUERY_ENDED = "query_ended"
        const val WRITE_END = "end"
        const val WRITE_ABANDONED = "abandoned_queries"

        /**
         * What [sqlState] answers when no `SQLException` in the chain carries one — the spelling of
         * `FailureShape.NO_SQL_STATE`, which this module cannot import (it does not depend on `persistence`), so the
         * line never logs the string `null`.
         */
        const val NO_SQL_STATE = "none"

        /** The first SQLState in the cause chain, else [NO_SQL_STATE] — the `FailureShape` rule, restated here. */
        fun sqlState(e: Throwable): String =
            generateSequence(e) { it.cause }
                .filterIsInstance<SQLException>()
                .firstNotNullOfOrNull { it.sqlState } ?: NO_SQL_STATE
    }
}
