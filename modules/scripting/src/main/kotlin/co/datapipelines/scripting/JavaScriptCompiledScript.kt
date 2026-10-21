package co.datapipelines.scripting

import org.graalvm.polyglot.Source

/**
 * The [CompiledScript] handle for the JavaScript engine — internal, like the JSONata handle, so a
 * caller can neither build one nor carry it to the other engine.
 *
 * [source] is the author's body inside the engine's `main` wrapper, built `cached(false)`: the
 * runtime's one Engine lives as long as the JVM, so an author's parse is never kept in it — no body's
 * AST outlives its evaluation, and the Engine's cache does not grow with the number of bodies.
 * [bodyHash] identifies the body in logs without carrying it.
 */
internal class JavaScriptCompiledScript(
    val body: String,
    val source: Source,
    val bodyLines: Int,
    val bodyHash: String,
) : CompiledScript
