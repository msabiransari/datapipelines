package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.11 (140) — the `checks[]` release-check declarations.
 *
 * Every defect is reported under one code, [Validation.CHECK_INVALID], because to the author
 * they are one category: this check cannot stand as written. Run-time outcomes (a statement
 * that returns two columns, a datasource that is down) are NOT validation — they are the
 * `fail` / `error` verdicts of a `pipeline_check_runs` row (§13.17), produced only by the
 * server's own run.
 *
 * ## What "parse-validated like a template body" means for a check
 *
 * A check has NO rendering: `${}` interpolation is refused outright (a check binds values as
 * `:name` SQL parameters and nothing else), and every `:name` the SQL references must be a
 * declared pipeline parameter — the same refusal an undeclared bind earns in a node, decided
 * here by the same text scan the templates module's `SqlBindScanner` performs (the regex is
 * mirrored, not imported: this module may depend on typesystem and calculators only). The
 * read-only / single-statement discipline is enforced at RUN time by the bounded probe the
 * runner rides (`SqlProbe` admits a single SELECT/WITH and nothing else) — save time has no
 * SQL grammar to consult, exactly as for node bodies.
 */
internal object ChecksRules {
    fun check(
        pipeline: Pipeline,
        datasources: DatasourceRegistry,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        if (pipeline.checks.size > MAX_CHECKS) {
            into.add(
                Validation.CHECK_INVALID,
                "checks",
                "A pipeline carries at most $MAX_CHECKS checks; ${pipeline.checks.size} declared.",
                mapOf("count" to pipeline.checks.size, "max" to MAX_CHECKS),
            )
        }
        val seen = mutableSetOf<String>()
        pipeline.checks.forEachIndexed { index, check ->
            checkId(index, check, seen, into)
            checkName(index, check, into)
            checkDatasource(index, check, datasources, workspaceId, into)
            checkSql(index, check, pipeline, into)
            checkExpected(index, check, into)
        }
    }

    /**
     * §12.11 — the id obeys the §15.1 identifier grammar (`[a-z0-9_]+`, 1–63, never the
     * reserved namespace), and is unique within the body: the run rows key on it
     * (`pipeline_check_runs` is latest-per-(version, check_id)), so a duplicate would make
     * one check's verdict overwrite the other's.
     */
    private fun checkId(
        index: Int,
        check: PipelineCheck,
        seen: MutableSet<String>,
        into: FailureCollector,
    ) {
        if (!IDENTIFIER.matches(check.id)) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].id",
                "Check id '${check.id.truncateForError()}' must match [a-z0-9_]+, length 1-63.",
                mapOf("value" to check.id.truncateForError()),
            )
            return
        }
        if (isReservedIdentifier(check.id)) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].id",
                "Check id '${check.id.truncateForError()}' is reserved (tempdb or the __…__ namespace).",
                mapOf("value" to check.id.truncateForError()),
            )
            return
        }
        if (!seen.add(check.id)) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].id",
                "Check id '${check.id.truncateForError()}' is used by another check in this body.",
                mapOf("value" to check.id.truncateForError()),
            )
        }
    }

    /**
     * §12.11 — the name is the human sentence a release dialog shows ("Manhattan rideshare
     * share, Q4 2024, from the raw rollup"). Blank is refused; bounded so a payload cannot
     * park a megabyte of prose in the body.
     */
    private fun checkName(
        index: Int,
        check: PipelineCheck,
        into: FailureCollector,
    ) {
        if (check.name.isBlank() || check.name.length > MAX_NAME_CHARS) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].name",
                "Check '${check.id.truncateForError()}' needs a name of 1-$MAX_NAME_CHARS characters.",
                mapOf("check" to check.id.truncateForError()),
            )
        }
    }

    /**
     * §12.11 — the datasource must be visible to the saving workspace (the §12.5 rule as for
     * nodes), and `tempdb` is refused outright: the staging database exists only inside an
     * execution (§4.8), and a check runs with no execution context — there would be nothing
     * to check against.
     */
    private fun checkDatasource(
        index: Int,
        check: PipelineCheck,
        datasources: DatasourceRegistry,
        workspaceId: java.util.UUID,
        into: FailureCollector,
    ) {
        if (check.datasource == NodeSource.TEMPDB_LITERAL) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].datasource",
                "Check '${check.id.truncateForError()}' cannot read tempdb: the staging database " +
                    "exists only inside an execution, and a check runs with no execution context.",
                mapOf("check" to check.id.truncateForError(), "datasource" to check.datasource),
            )
            return
        }
        if (check.datasource.isBlank() || datasources.describe(check.datasource, workspaceId) == null) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].datasource",
                "Check '${check.id.truncateForError()}' reads from '${check.datasource.truncateForError()}', " +
                    "which is not a datasource registered in this environment.",
                mapOf("check" to check.id.truncateForError(), "datasource" to check.datasource.truncateForError()),
            )
        }
    }

    /**
     * §12.11 — the SQL is one statement with NO rendering: a `${` opens an interpolation a
     * check has no engine for, and every `:name` bind must name a DECLARED pipeline
     * parameter (§7.2 — the calculator context is not available to a check).
     */
    private fun checkSql(
        index: Int,
        check: PipelineCheck,
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        if (check.sql.isBlank()) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].sql",
                "Check '${check.id.truncateForError()}' carries no SQL.",
                mapOf("check" to check.id.truncateForError()),
            )
            return
        }
        if (check.sql.contains("\${")) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].sql",
                "Check '${check.id.truncateForError()}' interpolates with \${}: a check has no rendering — " +
                    "bind values as :name SQL parameters.",
                mapOf("check" to check.id.truncateForError()),
            )
        }
        scanBinds(check.sql)
            .filter { it !in pipeline.parameters }
            .forEach { bind ->
                into.add(
                    Validation.CHECK_INVALID,
                    "checks[$index].sql",
                    "Check '${check.id.truncateForError()}' binds :$bind, which no declared parameter of " +
                        "this pipeline provides.",
                    mapOf("check" to check.id.truncateForError(), "parameter" to bind),
                )
            }
    }

    /**
     * §12.11 — `expected.kind` is the closed list `value | range | rows`, and the kind's
     * required members are present and coherent: `value` needs `value` (tolerance absolute,
     * default 0), `range` needs `min` ≤ `max`, `rows` needs a non-negative `rows`.
     */
    private fun checkExpected(
        index: Int,
        check: PipelineCheck,
        into: FailureCollector,
    ) {
        val expected = check.expected
        if (expected.kind !in CheckExpectation.KINDS) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].expected.kind",
                "Check '${check.id.truncateForError()}' expected.kind '${expected.kind.truncateForError()}' " +
                    "is not one of value | range | rows.",
                mapOf("check" to check.id.truncateForError(), "kind" to expected.kind.truncateForError()),
            )
            return
        }
        if (expected.tolerance != null && expected.tolerance < 0) {
            into.add(
                Validation.CHECK_INVALID,
                "checks[$index].expected.tolerance",
                "Check '${check.id.truncateForError()}' declares a negative tolerance; tolerance is absolute.",
                mapOf("check" to check.id.truncateForError(), "tolerance" to expected.tolerance),
            )
        }
        when (expected.kind) {
            CheckExpectation.KIND_VALUE -> {
                if (expected.value == null) {
                    missing(index, check, "a 'value' expectation needs expected.value.", into)
                }
            }

            CheckExpectation.KIND_RANGE -> {
                if (expected.min == null || expected.max == null) {
                    missing(index, check, "a 'range' expectation needs expected.min and expected.max.", into)
                } else if (expected.min > expected.max) {
                    missing(index, check, "a 'range' expectation needs expected.min <= expected.max.", into)
                }
            }

            CheckExpectation.KIND_ROWS -> {
                if (expected.rows == null || expected.rows < 0) {
                    missing(index, check, "a 'rows' expectation needs a non-negative expected.rows.", into)
                }
            }
        }
    }

    private fun missing(
        index: Int,
        check: PipelineCheck,
        reason: String,
        into: FailureCollector,
    ) {
        into.add(
            Validation.CHECK_INVALID,
            "checks[$index].expected",
            "Check '${check.id.truncateForError()}': $reason",
            mapOf("check" to check.id.truncateForError(), "kind" to check.expected.kind),
        )
    }

    /**
     * Every distinct `:name` bind in [sql], first-seen order — the mirror of the templates
     * module's `SqlBindScanner` regex (see this object's KDoc for why it is mirrored). The
     * lookbehind refuses a colon that starts a cast (`::`) or a word suffix (`a:b`).
     */
    private fun scanBinds(sql: String): List<String> =
        BIND
            .findAll(sql)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** `:name` — a colon starting neither a cast nor a word suffix, then a §6.1 key. */
    private val BIND = Regex("""(?<![:\w]):([a-z_][a-z0-9_]*)""")

    /** §12.11 — at most this many checks per pipeline body. */
    const val MAX_CHECKS = 20

    /** §12.11 — a check's display name is bounded prose. */
    const val MAX_NAME_CHARS = 200
}
