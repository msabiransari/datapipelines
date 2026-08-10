package co.datapipelines.auth

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The scope hierarchy `read ⊂ execute ⊂ author ⊂ admin` (auth.md §7.5). */
class ScopeTest {
    @Test
    fun `wire tokens are lowercase`() {
        Scope.READ.wire shouldBe "read"
        Scope.ADMIN.wire shouldBe "admin"
    }

    @Test
    fun `implies follows the hierarchy in one direction only`() {
        Scope.ADMIN.implies(Scope.READ).shouldBeTrue()
        Scope.AUTHOR.implies(Scope.EXECUTE).shouldBeTrue()
        Scope.EXECUTE.implies(Scope.EXECUTE).shouldBeTrue()
        Scope.READ.implies(Scope.EXECUTE).shouldBeFalse()
        Scope.EXECUTE.implies(Scope.AUTHOR).shouldBeFalse()
    }

    @Test
    fun `expand includes every lower scope`() {
        Scope.AUTHOR.expand() shouldContainExactlyInAnyOrder listOf(Scope.READ, Scope.EXECUTE, Scope.AUTHOR)
        Scope.ADMIN.expand() shouldContainExactlyInAnyOrder Scope.entries
        Scope.READ.expand() shouldContainExactlyInAnyOrder listOf(Scope.READ)
    }

    @Test
    fun `satisfies is hierarchical - a higher held scope covers a lower requirement`() {
        Scope.satisfies(setOf(Scope.AUTHOR), Scope.READ).shouldBeTrue()
        Scope.satisfies(setOf(Scope.READ), Scope.AUTHOR).shouldBeFalse()
        Scope.satisfies(emptySet(), Scope.READ).shouldBeFalse()
    }

    @Test
    fun `effective is the union of expansions`() {
        Scope.effective(setOf(Scope.EXECUTE)) shouldContainExactlyInAnyOrder listOf(Scope.READ, Scope.EXECUTE)
    }

    @Test
    fun `fromWire parses known tokens case-insensitively and rejects unknown`() {
        Scope.fromWire("admin") shouldBe Scope.ADMIN
        Scope.fromWire("READ") shouldBe Scope.READ
        assertThrows<IllegalArgumentException> { Scope.fromWire("superuser") }
    }
}
