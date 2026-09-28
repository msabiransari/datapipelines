package co.datapipelines.typesystem

import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A declaration checked and then judged is compiled ONCE (#298 item 4).
 *
 * The binder asks "would save refuse this stored declaration?" before it judges a value against
 * it, and before #298 both halves compiled the declaration — its `pattern` through
 * [PatternGuard.compile] twice per constrained parameter per execute. [ParameterValueValidator.compile]
 * hands back the checked declaration; judging it compiles nothing more. Counted on the real
 * [PatternGuard] (a spy, the calls pass through), never assumed.
 */
class ParameterValueValidatorCompileOnceTest {
    private val validator = ParameterValueValidator()

    private val declaration =
        ParameterDeclaration(
            type = LogicalType.STRING,
            default = TextNode("abc"),
            constraints = ParameterConstraints(pattern = "[a-z]+"),
        )

    @BeforeEach
    fun spy() = mockkObject(PatternGuard)

    @AfterEach
    fun unspy() = unmockkObject(PatternGuard)

    @Test
    fun `check, judge a value and resolve the default - one compile`() {
        val compiled = validator.compile(declaration)
        compiled.problems.shouldBeEmpty()
        validator.validate(compiled, TextNode("abc")).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        validator.validate(compiled, TextNode("ABC")).shouldBeInstanceOf<ParameterValueOutcome.Refused>()
        validator.resolveDefault(compiled).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        verify(exactly = 1) { PatternGuard.compile(any()) }
    }

    @Test
    fun `the one-shot forms still compile exactly once each`() {
        validator.validate(declaration, TextNode("abc")).shouldBeInstanceOf<ParameterValueOutcome.Accepted>()
        validator.checkDeclaration(declaration).shouldBeEmpty()
        verify(exactly = 2) { PatternGuard.compile(any()) }
    }

    @Test
    fun `a compiled declaration save would refuse carries its problems and refuses to judge`() {
        val broken = validator.compile(declaration.copy(constraints = ParameterConstraints(minLength = 5, maxLength = 1)))
        broken.problems.size shouldBe 1
        runCatching { validator.validate(broken, TextNode("abc")) }.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
    }
}
