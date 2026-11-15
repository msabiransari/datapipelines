package co.datapipelines.jsrendered

/**
 * The js-rendered engine's files, as a JVM caller loads them (design record 2026-11-14 §4.3, §5.1; brief 985 D1, D3).
 *
 * The engine is classic JavaScript on one namespace, `JsRendered`: the files listed in `LOAD-ORDER.txt`, one name per
 * line, run in that order. This object reads them from the classpath and concatenates them, each followed by one
 * newline, which is the same text the Node suite runs (`src/test/js/engine.mjs`), so the server's sandbox and Node
 * draw one picture (I6). It holds nothing else: no entry point, no options, no evaluation (F2 adds the entry).
 */
object JsRenderedEngineFiles {
    /** The classpath directory of the engine files. */
    const val ROOT: String = "co/datapipelines/jsrendered/engine"

    /** The file that fixes the order. */
    const val LOAD_ORDER: String = "LOAD-ORDER.txt"

    /** The engine's file names in load order, relative to [ROOT] (a type's template under `types/`). */
    val names: List<String> by lazy {
        read(LOAD_ORDER).lines().map(String::trim).filter(String::isNotEmpty)
    }

    /** Every engine file's text, keyed by its name, in load order. */
    val sources: Map<String, String> by lazy { names.associateWith(::read) }

    /** The text to compile: every file in load order, each followed by a newline. */
    val concatenation: String by lazy { names.joinToString(separator = "") { sources.getValue(it) + "\n" } }

    private fun read(name: String): String {
        val path = "$ROOT/$name"
        val stream =
            checkNotNull(JsRenderedEngineFiles::class.java.classLoader.getResourceAsStream(path)) {
                "js-rendered engine file missing from the classpath: $path"
            }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
