package co.datapipelines.application.charts

import co.datapipelines.jsrendered.JsRenderedEngineFiles
import co.datapipelines.scripting.Availability
import co.datapipelines.scripting.EvaluationLimits
import co.datapipelines.scripting.JavaScriptEngine
import co.datapipelines.scripting.JavaScriptRuntime
import java.io.File
import java.time.Duration

/**
 * The js-rendered engine in the server's sandbox, for the chart guards (brief 985 D11): the engine's files in load
 * order, optionally the invariant checker, and a [tail] that reads `input` and returns a value, compiled as ONE body
 * through the scripting module's existing public path (`JavaScriptRuntime`, `JavaScriptEngine.compile`, `evaluate`).
 * No main source changes for it; lane F2 replaces this harness with the product's entry.
 *
 * The limits are the product's defaults for one evaluation (`datapipelines.script` in `WebProperties.ScriptProperties`:
 * `evaluateTimeoutSeconds` 10, `maxDepth` 100; heap, statements and byte caps are the runtime's own defaults): the
 * guards prove the engine runs in the sandbox as configured, never under raised bounds.
 *
 * A process without the isolate cannot run JavaScript: construction FAILS then (the guards never skip).
 */
internal class JsRenderedSandbox : AutoCloseable {
    private val runtime = JavaScriptRuntime()
    private val engine = JavaScriptEngine(runtime)

    init {
        val availability = runtime.availability()
        check(
            availability is Availability.Available,
        ) { "the JavaScript isolate is unavailable, the chart guards cannot run: $availability" }
    }

    /** Evaluates the engine (plus the checker when [withInvariants]) followed by [tail] on [input]. */
    fun evaluate(
        tail: String,
        input: Any?,
        withInvariants: Boolean = false,
    ): Any? {
        val checker = if (withInvariants) ChartGuardFiles.resource(GEOMETRY) + "\nvar G = JsRenderedInvariants(JsRendered);\n" else ""
        val script = engine.compile(JsRenderedEngineFiles.concatenation + checker + tail)
        return engine.evaluate(script, input, LIMITS)
    }

    override fun close() = runtime.close()

    companion object {
        /** `ScriptProperties.evaluateTimeoutSeconds` and `ScriptProperties.maxDepth`, the product's defaults. */
        val LIMITS = EvaluationLimits(wallClock = Duration.ofSeconds(10), maxDepth = 100)

        /** The invariant checker the Node suites run (`src/test/js/geometry.js`), from the engine module's test data. */
        const val GEOMETRY = "co/datapipelines/jsrendered/test/geometry.js"
    }
}

/** Where the chart guards read: the engine module's test data on the classpath, and the repository tree. */
internal object ChartGuardFiles {
    /** Node's emitted inputs and results (`jsRenderedEmit`): `<wire>/<name>/<w>x<h>.{input,output}.json`, `index.txt`, `on-fill.json`. */
    const val EMITTED = "co/datapipelines/jsrendered/emitted"

    /** The engine module's directory in the repository. */
    const val MODULE = "modules/js-rendered"

    fun resource(path: String): String {
        val stream =
            checkNotNull(ChartGuardFiles::class.java.classLoader.getResourceAsStream(path)) {
                "missing from the test classpath: $path (the js-rendered module's jsRenderedTestData)"
            }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The repository root: the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        requireNotNull(dir) { "repository root not found (no settings.gradle.kts on any ancestor)" }
    }

    fun repoFile(relative: String): File = File(root, relative)

    /** The chart fixtures of one type in the source tree, by name (without `.json`), sorted. */
    fun chartFixtures(wire: String): List<String> =
        repoFile("$MODULE/src/test/fixtures/$wire")
            .listFiles { f -> f.isFile && f.name.endsWith(".json") }
            .orEmpty()
            .map { it.name.removeSuffix(".json") }
            .sorted()

    /** The chart types that have a fixtures directory (every directory except `_layout`, which holds no chart document). */
    fun fixtureTypes(): List<String> =
        repoFile("$MODULE/src/test/fixtures")
            .listFiles { f -> f.isDirectory && !f.name.startsWith("_") }
            .orEmpty()
            .map { it.name }
            .sorted()
}
