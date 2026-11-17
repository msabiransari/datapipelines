package co.datapipelines.scripting

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sandbox inside the entry for trusted product code (brief 986 A.1, record §7 and A13): every access the sandbox
 * denies is denied inside [JavaScriptEngine.call], each probe with a POSITIVE CONTROL — the same snippet in a test-only
 * Context that grants exactly that access, which must report ALLOWED (a probe whose control cannot succeed could not
 * fail, and is not counted). The six probes and their wording are lane 977's (`probe/sandbox/probes.json`).
 *
 * The control Context is built HERE, never by the product's runtime: [JavaScriptRuntime]'s one builder is the thing
 * under test, and the sandbox guard keeps counting its two builder calls.
 */
class JavaScriptProgramSandboxTest {
    private val engine = JavaScriptEngine(ScriptingTestSupport.javaScriptRuntime)

    @TempDir
    lateinit var dir: Path

    /** The snippet runs at the program's top level, then the program's function returns what it recorded. */
    private fun text(snippet: String): String = "$snippet\n;(function (json) { return String(globalThis.__probe); })\n"

    private fun inside(snippet: String): String =
        engine.call(JavaScriptProgram.of("probe.js", text(snippet)), "{}", ScriptingTestSupport.DEFAULT_LIMITS)

    private fun control(
        access: String,
        snippet: String,
    ): String {
        // Its own isolate Engine with no sandbox policy per control: an Engine fixes one host-access configuration.
        val controlEngine =
            Engine
                .newBuilder(JavaScriptRuntime.LANGUAGE)
                .spawnIsolate(true)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .build()
        val builder = Context.newBuilder(JavaScriptRuntime.LANGUAGE).engine(controlEngine)
        if (access in HOST_FENCED) builder.allowHostAccess(HostAccess.ALL).allowHostClassLookup { true }
        when (access) {
            "io" -> builder.allowIO(IOAccess.ALL)
            "process" -> builder.allowCreateProcess(true)
            "thread" -> builder.allowCreateThread(true)
            "env" -> builder.allowEnvironmentAccess(org.graalvm.polyglot.EnvironmentAccess.INHERIT)
            "polyglot" -> builder.allowPolyglotAccess(PolyglotAccess.ALL)
        }
        return try {
            builder.build().use { context -> context.eval(JavaScriptRuntime.LANGUAGE, text(snippet)).execute("{}").asString() }
        } finally {
            controlEngine.close(true)
        }
    }

    private fun probes(): List<Pair<String, String>> {
        val target = dir.resolve("io-target.mjs")
        Files.writeString(target, "export const value = 42;\n")
        return listOf(
            "host" to
                "try { var S = Java.type('java.lang.System'); " +
                "globalThis.__probe = 'ALLOWED java.version=' + S.getProperty('java.version'); } " +
                "catch (e) { globalThis.__probe = 'DENIED ' + e; }",
            "io" to
                "globalThis.__probe = 'PENDING (the import never settled)'; import('$target').then(function (m) { " +
                "globalThis.__probe = 'ALLOWED ' + m.value; }, function (e) { globalThis.__probe = 'DENIED ' + e; });",
            "process" to
                "try { var p = new (Java.type('java.lang.ProcessBuilder'))(['true']).start(); p.waitFor(); " +
                "globalThis.__probe = 'ALLOWED exit=' + p.exitValue(); } catch (e) { globalThis.__probe = 'DENIED ' + e; }",
            "thread" to
                "try { var t = new (Java.type('java.lang.Thread'))(); t.start(); t.join(); " +
                "globalThis.__probe = 'ALLOWED thread joined'; } " +
                "catch (e) { globalThis.__probe = 'DENIED ' + e; }",
            "env" to
                "try { var env = Java.type('java.lang.System').getenv(); globalThis.__probe = 'ALLOWED variables=' + env.size(); } " +
                "catch (e) { globalThis.__probe = 'DENIED ' + e; }",
            "polyglot" to
                "try { globalThis.__probe = 'ALLOWED ' + Polyglot.eval('js', '6 * 7'); } catch (e) { globalThis.__probe = 'DENIED ' + e; }",
        )
    }

    @Test
    fun `every access the sandbox denies is denied inside the entry, and each probe's control is allowed`() {
        val run = probes()
        val report =
            run.map { (access, snippet) ->
                Triple(access, inside(snippet), control(access, snippet))
            }
        report.forEach { (access, denied, allowed) ->
            println("PROBE access=$access inside=${denied.lineSequence().first()} | control=${allowed.lineSequence().first()}")
        }
        // The non-vacuity floor: six probes ran, six controls reached ALLOWED.
        report.size shouldBe PROBES
        report.forEach { (access, denied, allowed) ->
            withClue("$access: the control (the same snippet with the access granted)") { allowed shouldStartWith "ALLOWED" }
            withClue("$access: inside the entry") { denied shouldStartWith "DENIED" }
        }
    }

    @Test
    fun `a program's Source is counted (not internal)`() {
        JavaScriptProgram.of("probe.js", text("globalThis.__probe = 1;")).counted shouldBe true
    }

    private companion object {
        const val PROBES = 6

        /** The probes that reach their access through host interop: their control also grants host access. */
        val HOST_FENCED = setOf("host", "process", "thread", "env")
    }
}
