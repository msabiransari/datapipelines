package co.datapipelines.scripting

/**
 * One parsed script, produced by [ScriptEngine.compile] and consumed by the SAME
 * engine's [ScriptEngine.evaluate].
 *
 * The handle is deliberately opaque: carrying no members means a caller cannot reach
 * past the seam into one engine's internals, and an engine change cannot break a
 * caller. It is NOT portable across engines — a script compiled by the JSONata engine
 * cannot be evaluated by the JavaScript one (`ScriptEngine.type` says which is which;
 * future work may widen the contract if a caller ever needs to route handles).
 *
 * Threading: a compiled script is safe to evaluate CONCURRENTLY from many threads
 * (proven for the JSONata engine by the conformance suite's 32-thread determinism
 * case) — compile once per template version, evaluate everywhere.
 */
interface CompiledScript
