package co.datapipelines.typesystem

/**
 * Dispatches a [Dialect] to its [IngressTypeMapper] (type-system.md §11.2).
 *
 * ## The fallback branch, and why it is not a `when` here
 *
 * §11.2 specifies the behavior precisely: a [Dialect] value this build has no mapper
 * for — one added to the enum ahead of its mapping table, say — **degrades instead of
 * throwing**. Every column then maps to `STRING` with the §8.2 warning. Adding a
 * `Dialect` value can never turn into a runtime crash.
 *
 * The spec writes that as a `when` with a documented `else`. That exact code does not
 * compile in this build: the `when` over a Kotlin enum is exhaustive, so the compiler
 * emits "'when' is exhaustive so 'else' is redundant here", and
 * `allWarningsAsErrors = true` (module-structure.md §7.1) turns it into a hard error.
 * The table lookup below has identical semantics — total function, unknown key
 * degrades to [FallbackTypeMapper], never throws — without an unwritable branch.
 * Reported to the orchestrator; §11.2 is non-normative implementation notes, so the
 * contract this must satisfy is the *behavior*, which it does.
 *
 * Neither form gives a compile-time nudge when a dialect is added — an `else` disables
 * exhaustiveness checking just as a map lookup does. `TypeMappersTest` is therefore the
 * real guard: it asserts every `Dialect` entry resolves to a mapper that is **not**
 * [FallbackTypeMapper], so a new dialect fails a test instead of silently degrading in
 * production.
 */
object TypeMappers {
    private val BY_DIALECT: Map<Dialect, IngressTypeMapper> =
        mapOf(
            Dialect.POSTGRES to PostgresTypeMapper,
            Dialect.ORACLE to OracleTypeMapper,
            Dialect.MSSQL to MssqlTypeMapper,
            Dialect.MYSQL to MysqlTypeMapper,
            Dialect.H2 to H2IngressMapper,
            Dialect.DUCKDB to DuckDbTypeMapper,
            Dialect.SQLITE to SqliteTypeMapper,
        )

    fun forDialect(dialect: Dialect): IngressTypeMapper = BY_DIALECT[dialect] ?: FallbackTypeMapper
}
