package co.datapipelines.scripting

import com.dashjoin.jsonata.Jsonata

/**
 * The [CompiledScript] handle for the JSONata engine — deliberately internal, so a
 * caller can never construct one or carry it to another engine (the
 * `evaluate`-casts-and-refuses contract in [JsonataEngine] stays enforceable).
 *
 * `expr` is the parsed library instance, shared across threads by design (the library
 * evaluates each call on a per-thread clone; the conformance suite proves the
 * determinism of exactly this sharing pattern).
 */
internal class JsonataCompiledScript(
    val body: String,
    val expr: Jsonata,
) : CompiledScript
