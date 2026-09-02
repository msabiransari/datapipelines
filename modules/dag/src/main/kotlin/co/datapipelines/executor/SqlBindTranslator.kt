package co.datapipelines.executor

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.SqlTypeValue
import org.springframework.jdbc.core.StatementCreatorUtils
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterUtils
import java.sql.PreparedStatement

/**
 * The named→positional translation bound node execution runs on (042 C1/C2).
 *
 * ## Why this is Spring's parser, not a scanner of our own
 *
 * A SQL string that mixes `:name` references with quotes, comments and casts needs exactly
 * what [NamedParameterUtils] already implements — quote-aware, comment-aware parameter
 * extraction over the full dialect alphabet. A hand-rolled scanner would be a second parser
 * to keep correct, and one this codebase has no other reason to own. The pinned
 * spring-jdbc's exact behaviour is pinned in `NamedParameterTranslationTest`, including
 * the dialect constructs it mis-reads (MySQL `#` comments, MSSQL `[a:b]` identifiers,
 * Oracle `q'[…]'`, Postgres `$$…$$`) — a jar bump that changes any of it fails the build.
 *
 * ## What the contract is
 *
 * The rendered SQL is template-authored *structure* plus bound *values*. Anything the
 * parser sees as a `:name` parameter must name a key of the execution context — the
 * pipeline's declared parameters — and is refused loudly when it does not (042 C2):
 * a silently-null predicate returns wrong data instead of an error, and a name that only
 * exists because a dialect construct was mis-parsed deserves the same refusal, because the
 * alternative is a silently corrupted statement.
 *
 * Keys that exist with a null value are bound as null deliberately: an optional parameter
 * the author chose to reference is the author's semantics, and `IS NOT DISTINCT FROM :x`
 * is the legitimate spelling. Keys that do not exist are never bindable — they are either
 * an authoring defect or a mis-parsed construct, and the message below names the `:name`
 * form for both.
 */
internal object SqlBindTranslator {
    /** Rendered SQL after translation: `?` placeholders and the values to bind in order. */
    data class BoundSql(
        /** The rendered SQL exactly as the template produced it — what larger statements embed. */
        val originalSql: String,
        /** The positional form (`?` placeholders) when parameters are bound, [originalSql] otherwise. */
        val sql: String,
        val bindValues: List<Any?>,
    ) {
        /** False exactly when the original SQL carried no `:name` reference. */
        val hasBindParameters: Boolean get() = bindValues.isNotEmpty()
    }

    /**
     * Translates [sql] against the execution context [values] (042 C1).
     *
     * Only the String-overload surface of [NamedParameterUtils] is used: [ParsedSql]'s
     * parameter-name list is package-private, so the missing-parameter gate leans on the
     * property `buildValueArray(String, Map)` already has — a name with NO registered key
     * throws `InvalidDataAccessApiUsageException` instead of binding null (pinned in
     * `NamedParameterTranslationTest`). A key that exists with a null value binds null
     * deliberately: an optional parameter the author chose to reference is the author's
     * semantics.
     *
     * @throws DatapipelinesException with `pipeline.node.sql_parameter_missing` when the SQL
     *   references a name the context does not declare (042 C2).
     */
    fun translate(
        sql: String,
        values: Map<String, Any?>,
    ): BoundSql {
        val translated = NamedParameterUtils.substituteNamedParameters(sql, MapSqlParameterSource(values))
        return try {
            BoundSql(
                originalSql = sql,
                sql = translated,
                bindValues = NamedParameterUtils.buildValueArray(sql, values).toList(),
            )
        } catch (e: InvalidDataAccessApiUsageException) {
            throw DatapipelinesException(
                code = PipelineErrorCodes.Node.SQL_PARAMETER_MISSING,
                message =
                    "The rendered SQL references a bind parameter the execution context does not " +
                        "declare — ${e.message}. A :name reference must name a declared pipeline " +
                        "parameter.",
                details = emptyMap(),
            )
        }
    }

    /**
     * Binds [values] positionally — the exact path `NamedParameterJdbcTemplate` uses to put
     * its value array on the statement (`StatementCreatorUtils.setParameterValue`), so the
     * temporal types (`LocalDate`, `Instant`) and decimals bind through the same code the
     * house repositories already depend on.
     */
    fun bind(
        statement: PreparedStatement,
        values: List<Any?>,
    ) {
        values.forEachIndexed { index, value ->
            StatementCreatorUtils.setParameterValue(statement, index + 1, SqlTypeValue.TYPE_UNKNOWN, value)
        }
    }
}
