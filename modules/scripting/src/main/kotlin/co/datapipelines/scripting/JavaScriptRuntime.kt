package co.datapipelines.scripting

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.EnvironmentAccess
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.SandboxPolicy
import org.graalvm.polyglot.io.IOAccess
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneOffset

/**
 * Which Engines an evaluation runs on (transform-nodes design §4.4, owner ruling D1, 2026-10-20:
 * "one Engine per evaluation when the spike shows it affordable, else one per JVM").
 */
sealed interface EngineScope {
    /**
     * A fresh Engine — a fresh isolate — for every compile and every evaluation. NOT BUILT, though lane
     * 8a's A.0 spike passed it (JDK 21.0.12, GraalVM CE Isolated 25.4.4.1.1, linux-amd64: Engine +
     * Context + `1 + 1` + close both, p50 4.5 / 4.4 ms, p95 5.6 / 5.1 ms, RSS flat over 1 000 cycles):
     * that workload never compiled. An isolate in which the guest's code got hot enough for Truffle to
     * compile is NOT released when its Engine closes — `close()` or `close(true)`, with or without a
     * pause before it, and not after a GC. Measured in the breach stage on the same box: ~9 mappings,
     * ~3 MB anonymous and ~24 MB file-backed resident memory and ~33 GB of address space left behind per
     * evaluation; a fresh JVM running 1 000 evaluations of a 100-row map grew from 0.2 to 27 GB RSS
     * (3 GB of it anonymous) and 37 GB to 33 TB of virtual size, which exhausts the 128 TB user address
     * space in a few thousand evaluations. Not affordable, so the ruling's other arm is built.
     */
    data object PerEvaluation : EngineScope

    /**
     * One Engine — one isolate — for the runtime's life, a fresh Context per compile and per
     * evaluation, each Context its own realm (globals, prototypes, `Date`/`Math` patches): THE ARM
     * BUILT. Measured in the breach stage: 600 Contexts of the same hot body on one Engine moved the
     * mapping count 550 → 566 and anonymous RSS +35 MB then +8 MB (the code cache warming, then flat).
     *
     * Fault domain (A.0 step 6, measured): a Context that trips `sandbox.MaxHeapMemory` or the
     * isolate's own `engine.MaxIsolateMemory` dies alone — a sibling Context on the same Engine and a
     * fresh one built after it both evaluate `1 + 1` to 2 — so the Engine is kept, never rebuilt. The
     * isolate's heap is SHARED by the Contexts running at once: `isolateMemoryBytes` must cover the
     * pool's concurrent evaluations at `maxHeapBytes` each, or a heavy evaluation can exhaust the
     * isolate under a light one (sizing is 8b's, with the pool).
     */
    data object PerJvm : EngineScope
}

/** Whether the JavaScript isolate can start in this process. */
sealed interface Availability {
    /** The isolate starts. */
    data object Available : Availability

    /** It cannot; [reason] is the polyglot message (no isolate on the class path, a refused option…). */
    data class Unavailable(
        val reason: String,
    ) : Availability
}

/**
 * Owns the JavaScript isolate's Engines and Contexts (#8): the ONE place in the module either is
 * built (`JavaScriptSandboxGuardTest` scans `src/main` and finds the two builder calls here only),
 * so no code path can build one outside the policy.
 *
 * Every Engine is `SandboxPolicy.UNTRUSTED` with `spawnIsolate(true)`, `engine.MaxIsolateMemory` and
 * `engine.UntrustedCodeMitigation=software` (the sandbox refuses `none` and `hardware` under
 * UNTRUSTED), streams redirected. Every Context adds: `HostAccess.UNTRUSTED` with nothing exported,
 * no polyglot, IO, native, process, thread, environment or host-class access; every `sandbox.*`
 * limit the policy requires plus `MaxStatements` and `MaxStackFrames`; the UTC time zone and the
 * `en-US` locale (pinned: a body's output must not depend on the host's zone or locale); a pinned
 * ECMAScript version; and three globals switched off at the source — `load`/`loadWithNewGlobal`
 * (`js.load`), `Graal` (`js.graal-builtin`) and the built-in `console` (`js.console`, replaced by the
 * engine's own prelude). `print` stays on because the prelude's console writes through it and then
 * deletes it from the global object.
 *
 * The Engine ignores `polyglot.*` system properties (`useSystemProperties(false)`): the options above
 * are the whole set, and a `-Dpolyglot.engine.…` on the command line or a `System.setProperty` from any
 * library in the JVM cannot add one (measured in lane 8a: with the default, a planted
 * `polyglot.engine.Compilation` reached the Engine). The resource-cache location the image passes as
 * `-Dpolyglot.engine.userResourceCache` is read by polyglot's installer, not as an Engine option.
 *
 * The scope is [EngineScope.PerJvm], fixed here, not configuration: one Engine, built on first use
 * and closed by [close]. The isolate's native library is NOT in the jar: the launcher puts it on the
 * class path (the image's `LOADER_PATH`), and a process without it is [Availability.Unavailable],
 * which the app reads before registering the engine.
 */
class JavaScriptRuntime internal constructor(
    val options: JavaScriptRuntimeOptions,
    /** A test seam: sees every Engine this runtime builds (the one per runtime, under [EngineScope.PerJvm]). */
    private val engineObserver: (Engine) -> Unit,
) : AutoCloseable {
    constructor(options: JavaScriptRuntimeOptions = JavaScriptRuntimeOptions()) : this(options, {})

    /** The arm built — [EngineScope.PerJvm]. */
    val scope: EngineScope = EngineScope.PerJvm

    private val lock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var engine: Engine? = null

    private val probe: Availability by lazy { probeAvailability() }

    /**
     * Probes once, on first call, by building the Engine and a Context under the full policy and
     * evaluating `1 + 1`; later calls return the same verdict. [Availability.Unavailable] after [close].
     */
    fun availability(): Availability = if (closed) Availability.Unavailable(CLOSED) else probe

    /**
     * Runs [body] on a fresh Context of the runtime's Engine under [limits], closing the Context after
     * it. A Context that tripped a limit is dead and its `close` rethrows the trip — the error [body]
     * already saw — so closing never replaces [body]'s outcome.
     */
    internal fun <T> withContext(
        limits: SandboxLimits,
        out: OutputStream,
        err: OutputStream,
        body: (Context) -> T,
    ): T {
        val availability = availability()
        if (availability is Availability.Unavailable) {
            throw ScriptEvaluationException("the JavaScript engine is unavailable: ${availability.reason}")
        }
        return openContext(limits, out, err, body)
    }

    private fun <T> openContext(
        limits: SandboxLimits,
        out: OutputStream,
        err: OutputStream,
        body: (Context) -> T,
    ): T {
        val context = newContext(sharedEngine(), limits, out, err)
        try {
            return body(context)
        } finally {
            closeAfterUse { context.close() }
        }
    }

    /** The runtime's one Engine, built on first use; refused once the runtime is closed. */
    private fun sharedEngine(): Engine =
        engine ?: synchronized(lock) {
            if (closed) throw ScriptEvaluationException("the JavaScript engine is unavailable: $CLOSED")
            engine ?: newEngine().also {
                engineObserver(it)
                engine = it
            }
        }

    private fun newEngine(): Engine =
        Engine
            .newBuilder(LANGUAGE)
            .sandbox(SandboxPolicy.UNTRUSTED)
            .spawnIsolate(true)
            .useSystemProperties(false)
            .option("engine.MaxIsolateMemory", bytes(options.isolateMemoryBytes))
            .option("engine.UntrustedCodeMitigation", "software")
            .`in`(InputStream.nullInputStream())
            .out(OutputStream.nullOutputStream())
            .err(OutputStream.nullOutputStream())
            .build()

    private fun newContext(
        engine: Engine,
        limits: SandboxLimits,
        out: OutputStream,
        err: OutputStream,
    ): Context =
        Context
            .newBuilder(LANGUAGE)
            .engine(engine)
            .sandbox(SandboxPolicy.UNTRUSTED)
            .allowHostAccess(HostAccess.UNTRUSTED)
            .allowPolyglotAccess(PolyglotAccess.NONE)
            .allowIO(IOAccess.NONE)
            .allowNativeAccess(false)
            .allowCreateProcess(false)
            .allowCreateThread(false)
            .allowEnvironmentAccess(EnvironmentAccess.NONE)
            .allowHostClassLookup { false }
            .timeZone(ZoneOffset.UTC)
            .`in`(InputStream.nullInputStream())
            .out(out)
            .err(err)
            .option("sandbox.MaxCPUTime", "${limits.cpuMillis}ms")
            .option("sandbox.MaxHeapMemory", bytes(limits.heapBytes))
            .option("sandbox.MaxStatements", limits.statements.toString())
            .option("sandbox.MaxStatementsIncludeInternal", "false")
            .option("sandbox.MaxASTDepth", options.maxAstDepth.toString())
            .option("sandbox.MaxStackFrames", limits.stackFrames.toString())
            .option("sandbox.MaxThreads", "1")
            .option("sandbox.MaxOutputStreamSize", bytes(options.maxOutputBytes))
            .option("sandbox.MaxErrorStreamSize", bytes(options.maxOutputBytes))
            .option("js.ecmascript-version", options.ecmascriptVersion)
            .option("js.locale", LOCALE)
            .option("js.load", "false")
            .option("js.graal-builtin", "false")
            .option("js.console", "false")
            .build()

    @Suppress("TooGenericExceptionCaught") // the verdict covers ANY failure to start: a missing isolate,
    // a refused option, a native library that will not load — each is "unavailable", never a crash.
    private fun probeAvailability(): Availability =
        try {
            openContext(PROBE_LIMITS, OutputStream.nullOutputStream(), OutputStream.nullOutputStream()) { context ->
                check(context.eval(LANGUAGE, "1 + 1").asInt() == 2) { "the probe evaluated 1 + 1 to something else" }
            }
            Availability.Available
        } catch (err: Exception) {
            Availability.Unavailable(err.message ?: err.javaClass.name)
        } catch (err: LinkageError) {
            Availability.Unavailable(err.message ?: err.javaClass.name)
        }

    /**
     * Marks the runtime closed and closes its Engine, cancelling any evaluation still running on it;
     * every later evaluation is refused as unavailable.
     */
    override fun close() {
        synchronized(lock) {
            closed = true
            engine?.let { closeAfterUse { it.close(true) } }
            engine = null
        }
    }

    internal companion object {
        const val LANGUAGE = "js"

        private const val CLOSED = "the JavaScript runtime is closed"

        /** Pinned with the UTC zone: `Intl`, `toLocaleString` and friends answer the same everywhere. */
        const val LOCALE = "en-US"

        private const val PROBE_MILLIS = 5_000L
        private const val PROBE_BYTES = 32L * 1024L * 1024L
        private const val PROBE_STATEMENTS = 1_000L
        private const val PROBE_FRAMES = 100

        val PROBE_LIMITS = SandboxLimits(PROBE_MILLIS, PROBE_BYTES, PROBE_STATEMENTS, PROBE_FRAMES)

        /** Polyglot's size syntax (`<n>B`), exact to the byte. */
        fun bytes(value: Long): String = "${value}B"

        /**
         * Runs one close after use. A Context that tripped a limit rethrows the trip from `close()`
         * (measured in lane 8a); that error is the one the evaluation already reported, so it is
         * dropped here rather than replacing the evaluation's own outcome.
         */
        @Suppress("SwallowedException", "TooGenericExceptionCaught")
        private fun closeAfterUse(close: () -> Unit) {
            try {
                close()
            } catch (err: RuntimeException) {
                // Deliberately dropped: see the KDoc.
            }
        }
    }
}

/** The per-Context limits one call runs under; the rest come from [JavaScriptRuntimeOptions]. */
internal data class SandboxLimits(
    val cpuMillis: Long,
    val heapBytes: Long,
    val statements: Long,
    val stackFrames: Int,
)
