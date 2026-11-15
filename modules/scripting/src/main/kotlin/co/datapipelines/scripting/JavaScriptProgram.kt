package co.datapipelines.scripting

import org.graalvm.polyglot.Source

/**
 * TRUSTED product code for [JavaScriptEngine.call] (js-rendered charts record §5.1, §5.3; brief 986 D1): one named
 * Source, built ONCE and cached in the runtime's shared Engine, whose text evaluates to ONE function that takes JSON
 * text and returns JSON text. The product's own code, never an author's body: a stored script goes through
 * [JavaScriptEngine.compile] and [JavaScriptEngine.evaluate].
 *
 * The Source is NOT internal, unlike the prelude: its statements are counted against `sandbox.MaxStatements`, so a
 * template that loops ends on the statement limit like an author's script (record §5.3, "The Source"). Caching makes
 * the parse once per Engine; every call still runs in a fresh Context under the same builder and limits as an
 * evaluation (the sandbox guard keeps counting two builder calls).
 *
 * @property name the Source's name, as it appears in a guest stack trace; never the author source's name.
 */
class JavaScriptProgram private constructor(
    val name: String,
    internal val source: Source,
) {
    /**
     * Whether the Source's statements are counted: true, because the Source is NOT internal (the record's counted
     * Source). Read by the guards that pin it (the scripting module's own, and the chart engine's in application).
     */
    val counted: Boolean get() = !source.isInternal

    companion object {
        /**
         * Builds the program [text] under [name]. The text must evaluate, as its completion value, to a function of one
         * string argument returning a string; [JavaScriptEngine.call] refuses anything else when it runs.
         */
        fun of(
            name: String,
            text: String,
        ): JavaScriptProgram {
            require(name.isNotBlank()) { "a program needs a name" }
            require(name != JavaScriptEngine.AUTHOR_SOURCE_NAME) { "the name ${JavaScriptEngine.AUTHOR_SOURCE_NAME} is the author source's" }
            val source =
                Source
                    .newBuilder(JavaScriptRuntime.LANGUAGE, text, name)
                    .cached(true)
                    .internal(false)
                    .buildLiteral()
            return JavaScriptProgram(name, source)
        }
    }
}
