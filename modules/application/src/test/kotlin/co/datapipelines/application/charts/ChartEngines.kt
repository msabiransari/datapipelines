package co.datapipelines.application.charts

import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JavaScriptEngine
import co.datapipelines.scripting.JavaScriptRuntime
import co.datapipelines.scripting.ScriptEvaluationPool
import java.time.Duration

/**
 * The product's chart engine for the tests of this package, built as `modules/web`'s configuration builds it: one
 * [JavaScriptRuntime] with its defaults, the script pool at its product sizes (`datapipelines.script`: 4 running, 64
 * queued, 30 s grace) and the per-evaluation limits (10 s, depth 100). One per test JVM; a JVM without the isolate
 * FAILS here, the chart tests never skip.
 */
internal object ChartEngines {
    val runtime: JavaScriptRuntime by lazy {
        JavaScriptRuntime().also { runtime ->
            val availability = runtime.availability()
            check(
                availability is Availability.Available,
            ) { "the JavaScript isolate is unavailable, the chart tests cannot run: $availability" }
        }
    }

    val engine: JsRenderedChartEngine by lazy {
        JsRenderedChartEngine(
            JavaScriptEngine(runtime),
            ScriptEvaluationPool(POOL_SIZE, POOL_QUEUE, Duration.ofSeconds(GRACE_SECONDS), ScriptEvaluationPool.SYSTEM),
            JsRenderedSandbox.LIMITS,
        )
    }

    private const val POOL_SIZE = 4
    private const val POOL_QUEUE = 64
    private const val GRACE_SECONDS = 30L
}
