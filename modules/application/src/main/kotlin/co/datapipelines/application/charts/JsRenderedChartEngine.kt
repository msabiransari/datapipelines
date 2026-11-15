package co.datapipelines.application.charts

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JavaScriptEngine
import co.datapipelines.scripting.JavaScriptProgram
import co.datapipelines.scripting.ScriptEvaluationPool
import co.datapipelines.scripting.ScriptPoolExhaustedException
import co.datapipelines.scripting.ScriptResourceLimitException
import co.datapipelines.scripting.ScriptTimeoutException
import co.datapipelines.scripting.ScriptingException
import co.datapipelines.visualization.ChartEngine
import co.datapipelines.visualization.ChartEngineFailure
import co.datapipelines.visualization.ChartFrame
import co.datapipelines.visualization.ChartInput
import co.datapipelines.visualization.ChartOutput
import co.datapipelines.visualization.ChartPicture
import co.datapipelines.visualization.ChartTheme
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

/**
 * The [ChartEngine] over the js-rendered engine in the server's GraalJS sandbox (js-rendered charts record §5.3; brief
 * 986 D1, D2). The engine's files in load order plus [ENTRY] are ONE [JavaScriptProgram] — named [SOURCE_NAME],
 * cached, counted (not internal) — built once per JVM; each call goes through [JavaScriptEngine.call] (a fresh Context
 * per call, the runtime's limits, the input and result byte bounds, the watchdog) on the bounded script pool, the
 * bulkhead every production evaluation shares.
 *
 * The server measures text with the engine's width table ([MEASURE], margin 1.00: lane 985's D9) and draws with nothing
 * hidden. [limits] are the product's per-evaluation limits (`datapipelines.script`); the entry never raises one.
 */
class JsRenderedChartEngine(
    private val engine: JavaScriptEngine,
    private val pool: ScriptEvaluationPool,
    private val limits: EvaluationLimits,
) : ChartEngine {
    override fun render(
        input: ChartInput,
        frame: ChartFrame,
    ): ChartOutput = ChartOutput(JSON.readTree(call(request(input, frame, ChartTheme.LIGHT, write = null))))

    override fun picture(
        input: ChartInput,
        frame: ChartFrame,
        theme: ChartTheme,
    ): ChartPicture {
        val answer = JSON.readTree(call(request(input, frame, theme, write = STANDALONE)))
        return ChartPicture(answer.path("state").asText(), answer.get("svg")?.takeIf { it.isTextual }?.asText())
    }

    /**
     * The entry's answer for one render input given as JSON TEXT, unchanged: the canonical JSON of `JsRendered.render`
     * (the identity guard sends Node's own input text through the product's program, byte for byte).
     */
    internal fun renderText(inputText: String): String = call("{\"input\":$inputText,\"write\":null}")

    /**
     * The program's own registry, unchanged: `JsRendered.types()` as JSON text, `[{name, channels}]` (the registry guard
     * holds [co.datapipelines.visualization.ChartType] to what the product's program registers, brief 986 D10).
     */
    internal fun typesText(): String = call(TYPES_REQUEST)

    private fun request(
        input: ChartInput,
        frame: ChartFrame,
        theme: ChartTheme,
        write: String?,
    ): String =
        JSON.writeValueAsString(
            mapOf(
                "input" to
                    mapOf(
                        "type" to input.type.wire,
                        "document" to input.document,
                        "columns" to input.columns,
                        "types" to input.types.mapValues { it.value.wire },
                        "frame" to mapOf("w" to frame.width, "h" to frame.height),
                        "theme" to theme.wire,
                        "measure" to MEASURE,
                        "hidden" to emptyList<String>(),
                    ),
                "write" to write,
            ),
        )

    private fun call(json: String): String =
        try {
            pool.run(limits, LABEL) { engine.call(PROGRAM, json, limits) }
        } catch (limit: ScriptResourceLimitException) {
            throw ChartEngineFailure(limit.kind.name.lowercase(), limit.message ?: limit.kind.name, limit)
        } catch (timeout: ScriptTimeoutException) {
            throw ChartEngineFailure(TIMEOUT, timeout.message ?: TIMEOUT, timeout)
        } catch (full: ScriptPoolExhaustedException) {
            throw ChartEngineFailure(POOL, full.message ?: POOL, full)
        } catch (failed: ScriptingException) {
            throw ChartEngineFailure(null, failed.message ?: "the chart engine failed", failed)
        }

    companion object {
        /** The program's Source name (a guest stack trace names it). */
        const val SOURCE_NAME = "js-rendered.js"

        /** The pool's label for an abandoned chart evaluation. */
        private const val LABEL = "js-rendered"

        private const val TIMEOUT = "timeout"
        private const val POOL = "pool"
        private const val STANDALONE = "standalone"
        private const val TYPES_REQUEST = "{\"types\":true}"

        /** The server's width table and margin (record §4.3, O-4; lane 985 D9 measured margin 1.00). */
        val MEASURE: Map<String, Any> = mapOf("table" to "noto-sans-regular", "margin" to 1)

        /**
         * The one function the program evaluates to (record §5.3): a request `{input, write}` in, JSON text out —
         * `JsRendered.render(input)` as its canonical JSON when `write` is null, else `{state, svg}` with the picture
         * written by the engine's one escaping string writer in its `standalone` form (null for a state with no picture).
         * The request `{types: true}` answers the engine's registry, `JsRendered.types()`, and draws nothing.
         */
        val ENTRY: String =
            """
            (function (R) {
              "use strict";
              return function (json) {
                var request = JSON.parse(json);
                if (request.types === true) return JSON.stringify(R.types());
                var out = R.render(request.input);
                if (request.write === null) return JSON.stringify(out);
                if (request.write !== "standalone") throw new Error("entry: unknown write form");
                var drawn = out.state === "rendered" || out.state === "no-data";
                return JSON.stringify({ state: out.state, svg: drawn ? R.writeString(out.primitives, "standalone", request.input.theme) : null });
              };
            })(JsRendered)
            """.trimIndent()

        /** The engine's files in load order, then [ENTRY]: the program's whole text. */
        val TEXT: String by lazy { JsRenderedEngineFiles.concatenation + ENTRY + "\n" }

        /** Built once per JVM; the runtime's Engine caches its parse. */
        val PROGRAM: JavaScriptProgram by lazy { JavaScriptProgram.of(SOURCE_NAME, TEXT) }

        private val JSON: ObjectMapper = JsonMapper.builder().addModule(kotlinModule()).build()
    }
}
