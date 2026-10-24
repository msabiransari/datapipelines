package co.datapipelines.scripting

import co.datapipelines.scripting.ScriptResourceLimitException.Kind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The resource-exhausted message table of [JavaScriptErrors], over the messages the isolate
 * actually produced in lane 8a (copied from the measurements, not composed). The sandbox caps a
 * test can provoke on demand are pinned end to end in [JavaScriptEngineBoundsTest]; this table also
 * holds the one it cannot: `Array allocation too large.`, seen once in the breach suite.
 */
class JavaScriptErrorsTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("measured")
    fun `a measured resource message maps to its kind`(
        message: String,
        kind: Kind?,
    ) {
        JavaScriptErrors.limitKind(message) shouldBe kind
    }

    companion object {
        @JvmStatic
        fun measured(): List<Arguments> =
            listOf(
                Arguments.of("Maximum heap memory limit of 268435456 bytes exceeded. Current memory at least 347898792 bytes.", Kind.HEAP),
                Arguments.of("Array allocation too large.", Kind.HEAP),
                Arguments.of("Maximum statement limit of 100000000 exceeded. Statements executed 100000001.", Kind.STATEMENTS),
                Arguments.of("Maximum frame limit of 104 exceeded. Frames on stack: 105.", Kind.DEPTH),
                Arguments.of("Maximum output stream size of 1048576 exceeded. Bytes written 1056768.", Kind.OUTPUT),
                Arguments.of("Resource exhausted: Stack overflow", Kind.DEPTH),
                // Not a limit the table knows: the engine reports it as an evaluation failure, unlabelled.
                Arguments.of("Resource exhausted: something new", null),
            )
    }
}
