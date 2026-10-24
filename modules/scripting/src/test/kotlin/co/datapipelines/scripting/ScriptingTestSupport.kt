package co.datapipelines.scripting

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import java.time.Duration

/**
 * Shared fixtures for the scripting suites. One engine instance per JVM (the engine is
 * stateless) and small limit factories, so every suite reads the same shape.
 */
object ScriptingTestSupport {
    /** The one engine under test; every suite shares it. */
    val engine: ScriptEngine = JsonataEngine()

    /** The JavaScript runtime the JS suites share: one Engine, a fresh Context (its own realm) per evaluation. */
    val javaScriptRuntime: JavaScriptRuntime by lazy { JavaScriptRuntime() }

    /** The JavaScript engine under test (#8). */
    val javaScript: ScriptEngine by lazy { JavaScriptEngine(javaScriptRuntime) }

    /** A pinned clock so any body that reads `$now` is reproducible in tests. */
    val FIXED_NOW = java.time.Instant.parse("2026-09-23T12:00:00Z")

    /** The default suite budget — generous for these allocation-light suites at the conventions' test heap. */
    val DEFAULT_LIMITS =
        EvaluationLimits(
            wallClock = Duration.ofSeconds(10),
            maxDepth = 50,
            now = FIXED_NOW,
        )

    fun limits(
        wallClock: Duration = Duration.ofSeconds(10),
        maxDepth: Int = 50,
        now: java.time.Instant? = FIXED_NOW,
        maxHeapBytes: Long? = null,
        maxStatements: Long? = null,
        maxInputBytes: Long? = null,
        maxResultBytes: Long? = null,
        console: ConsoleSink? = null,
    ): EvaluationLimits =
        EvaluationLimits(
            wallClock = wallClock,
            maxDepth = maxDepth,
            maxHeapBytes = maxHeapBytes,
            maxStatements = maxStatements,
            now = now,
            maxInputBytes = maxInputBytes,
            maxResultBytes = maxResultBytes,
            console = console,
        )

    /** Compiles and evaluates [body] on the JavaScript engine. */
    fun js(
        body: String,
        input: Any? = null,
        limits: EvaluationLimits = DEFAULT_LIMITS,
    ): Any? = javaScript.evaluate(javaScript.compile(body), input, limits)

    /** The refusal [block] threw, as [T] — a different outcome (a value, another type) fails the test. */
    inline fun <reified T : Throwable> refusal(block: () -> Any?): T {
        val outcome = runCatching(block)
        val thrown =
            outcome.exceptionOrNull() ?: throw AssertionError("expected ${T::class.simpleName}, got the value ${outcome.getOrNull()}")
        return thrown as? T
            ?: throw AssertionError("expected ${T::class.simpleName}, got ${thrown.javaClass.name}: ${thrown.message}", thrown)
    }

    fun column(
        name: String,
        type: LogicalType,
        precision: Int? = null,
        scale: Int? = null,
        nullable: Boolean? = null,
    ): ColumnSchema = ColumnSchema(name, type, precision, scale, nullable)

    /** Evaluates [body] against [input] and asserts the outcome is a Pass value. */
    fun evaluateOrNull(
        body: String,
        input: Any?,
        limits: EvaluationLimits = DEFAULT_LIMITS,
    ): Any? {
        val script = engine.compile(body)
        return engine.evaluate(script, input, limits)
    }
}
