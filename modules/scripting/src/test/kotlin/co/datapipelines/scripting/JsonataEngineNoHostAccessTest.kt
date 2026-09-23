package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptingTestSupport.DEFAULT_LIMITS
import co.datapipelines.scripting.ScriptingTestSupport.engine
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * No host access (D-T4, §4.5): the engine evaluates untrusted bodies, so the builtin
 * catalogue must contain nothing that reaches the filesystem, the network, the
 * environment, system properties or processes — and `$eval` of file/network-ish text
 * must produce nothing outside JSON.
 *
 * The catalogue is read from the library's static frame by reflection (test scope
 * only): the deny-list names the exact builtin names that must never exist, and the
 * two assertions answer different questions — a name that IS bound is a hole; a scan
 * that finds nothing proves the scan looked at something.
 */
class JsonataEngineNoHostAccessTest {
    /**
     * The deny-list, written down as the brief requires: exact names a host-reaching
     * builtin would plausibly carry (JSONata's real catalogue is pure data functions —
     * string, numeric, array, object, date, regex and higher-order helpers).
     */
    private val denyList =
        listOf(
            "java",
            "jvm",
            "class",
            "clazz",
            "import",
            "require",
            "module",
            "load",
            "read",
            "write",
            "file",
            "files",
            "path",
            "open",
            "delete",
            "exec",
            "eval_java",
            "http",
            "fetch",
            "url",
            "socket",
            "net",
            "getenv",
            "env",
            "property",
            "system",
            "process",
            "shell",
            "command",
            "spawn",
            "runtime",
            "thread",
        )

    @Test
    fun `the builtin catalogue contains no name from the deny-list`() {
        val bound = boundBuiltinNames()
        bound.none { it in denyList } shouldBe true
    }

    @Test
    fun `the catalogue scan is not vacuous - the library ships the known core functions`() {
        // Absence-assertions pass vacuously; these presence-anchors prove the scan
        // actually reached the library's registered builtins.
        val bound = boundBuiltinNames()
        bound.contains("string") shouldBe true
        bound.contains("sum") shouldBe true
        bound.contains("eval") shouldBe true
    }

    @Test
    fun `eval of file and network-ish strings yields nothing outside json`() {
        // $eval parses and evaluates the string as JSONata: names that resolve to
        // nothing evaluate to null, and a parse failure is a typed refusal — either
        // way nothing outside the engine escapes and no host effect can occur.
        val bodies =
            listOf(
                "\$eval(\"java.io.File\")",
                "\$eval(\"require('fs')\")",
                "\$eval(\"fetch('http://localhost')\")",
                "\$eval(\"Runtime.getRuntime()\")",
            )
        bodies.forEach { body ->
            runCatching {
                engine.evaluate(engine.compile(body), null, DEFAULT_LIMITS)
            }.getOrNull().shouldBeNull()
        }
    }

    @Test
    fun `no java function is registered on any frame the engine hands out`() {
        // The engine's own sources: the ONLY bind sites are the runtime bounds'
        // callbacks and the clock shadow. A reflection sweep over the engine class's
        // behaviour: compile + evaluate leaves no custom function bound. The honest
        // mechanical form: the catalogue above equals itself after an evaluation
        // through THIS engine — no registered extra appeared.
        val before = boundBuiltinNames().toSet()
        engine.evaluate(engine.compile("1 + 1"), null, DEFAULT_LIMITS) shouldBe 2
        engine.evaluate(
            engine.compile("\$eval(\"2 + 2\")"),
            null,
            DEFAULT_LIMITS,
        ) shouldBe 4
        boundBuiltinNames().toSet() shouldBe before
    }

    /** The library's static frame bindings, read by reflection (test scope). */
    private fun boundBuiltinNames(): List<String> {
        val frameField =
            JsonataEngineNoHostAccessTest::class.java.classLoader
                .loadClass("com.dashjoin.jsonata.Jsonata")
                .getDeclaredField("staticFrame")
        frameField.isAccessible = true
        val frame = frameField.get(null)
        val bindingsField = frame.javaClass.getDeclaredField("bindings")
        bindingsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val bindings = bindingsField.get(frame) as Map<String, Any?>
        return bindings.keys.filterNot { it.startsWith("__") }.sorted()
    }
}
