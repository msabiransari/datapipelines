// module-structure.md §5.17 — layer 0 beside `typesystem`: NO internal dependencies, and none
// external either. The module holds the generic DAG primitive, `Dag<T>` / `DagBuilder<T>`
// (dag-executor.md §3), moved here byte-identical from `modules/dag` (#194, parameter-engine
// record P17) so that a module below the executor — the parameter engine — can build a graph
// without inheriting the executor's pipeline-contract, templates, datasources, staging, Redis
// and JDBC set.
//
// The package is `co.datapipelines.dag`, NOT `co.datapipelines.graph`, by decision: the move is
// a relocation, and the owner's ruling (P17, 2026-09-21) was that the mature executor is not
// touched — so its two importers (`executor/PipelineExecutor.kt`, `executor/ExecutableNode.kt`)
// keep their imports unchanged. Renaming the package is a change to the executor; do not
// "tidy" it. `DagPackageTest` pins it.
//
// Stdlib only, on purpose: a graph library would be a dependency carried for ~150 lines of
// code (dag-executor.md §2 principle 1). The house test dependencies (JUnit 5, MockK, Kotest
// assertions) come from the conventions plugin.
plugins { id("datapipelines.common-conventions") }
