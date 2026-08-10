package co.datapipelines.staging

/**
 * The staging engine backing a per-execution tempdb (staging.md §3.1, enums.md §7).
 *
 * v1 has exactly one constant, [H2]. `DUCKDB` is reserved for a future analytical engine
 * (staging.md §10.1, ROADMAP) and deliberately absent — enums.md's "(reserved)" values
 * MUST NOT appear in generated code or be accepted by validators in v1.
 *
 * ## Why this enum is declared here and not consumed from `pipeline-contract`
 *
 * enums.md §7's cross-reference names `pipeline-contract` as the authoring module (its
 * `settings.tempdb.engine` field), with `staging` listed as a consumer. That consumption
 * is impossible under the frozen dependency table (module-structure.md §4.2): `staging`
 * may depend on `typesystem` **only**, never on `pipeline-contract`. staging.md §3.1
 * itself declares `enum class StagingEngine { H2 }` inline in this module's spec, so this
 * local declaration follows the staging spec exactly.
 *
 * The consequence, owned by the `dag` module (which depends on both): the executor maps
 * the pipeline's `settings.tempdb.engine` to this enum when calling [StagingFactory.create].
 * With a single shared constant name the mapping is trivial and additive-frozen. This
 * cross-module duplication is flagged for consolidation — the layering-correct home for a
 * type both `pipeline-contract` and `staging` need is `typesystem` (the only lower layer
 * both share).
 */
enum class StagingEngine {
    /** In-memory H2. The only supported engine in v1 (enums.md §7). */
    H2,
}
