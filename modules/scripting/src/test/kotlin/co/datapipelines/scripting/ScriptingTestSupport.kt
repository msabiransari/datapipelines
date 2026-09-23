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

    /** A pinned clock so any body that reads `$now` is reproducible in tests. */
    val FIXED_NOW = java.time.Instant.parse("2026-09-23T12:00:00Z")

    /** The default suite budget — generous against a 512m test JVM. */
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
    ): EvaluationLimits = EvaluationLimits(wallClock, maxDepth, now = now)

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
