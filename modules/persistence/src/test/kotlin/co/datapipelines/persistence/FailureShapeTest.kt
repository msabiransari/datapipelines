package co.datapipelines.persistence

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException

@Timeout(5)
class FailureShapeTest {
    @ParameterizedTest
    @ValueSource(strings = ["0", "A", "z", "aB3z9", "40001"])
    fun `valid ASCII states are returned unchanged`(state: String) {
        FailureShape.sqlState(SQLException("synthetic", state)) shouldBe state
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "ABCDEF", "40001\r\n", "4\r001", "4\n001", "4\t001", " 001", "4001 ", "40 01", "40-01", "é0001", "Ａ0001"])
    fun `malformed states are replaced with the fixed invalid token`(state: String) {
        FailureShape.sqlState(SQLException("synthetic", state)) shouldBe "invalid"
    }

    @Test
    fun `very long states are replaced rather than truncated`() {
        FailureShape.sqlState(SQLException("synthetic", "A".repeat(1_000_000))) shouldBe "invalid"
    }

    @Test
    fun `wrapped valid state is found`() {
        FailureShape.sqlState(RuntimeException("wrapper", SQLException("synthetic", "40001"))) shouldBe "40001"
    }

    @Test
    fun `null state permits a later valid state`() {
        val outer = SQLException("synthetic", null as String?, SQLException("synthetic", "aB3z9"))
        FailureShape.sqlState(outer) shouldBe "aB3z9"
    }

    @Test
    fun `invalid first state does not fall through to a later valid state`() {
        val outer = SQLException("synthetic", "bad\n", SQLException("synthetic", "40001"))
        FailureShape.sqlState(outer) shouldBe "invalid"
    }

    @Test
    fun `missing states keep the none token`() {
        FailureShape.sqlState(RuntimeException("synthetic")) shouldBe "none"
        FailureShape.sqlState(SQLException("synthetic", null as String?)) shouldBe "none"
    }

    @Test
    fun `cyclic no-state chain terminates`() {
        val first = SQLException("synthetic", null as String?)
        val second = RuntimeException("synthetic", first)
        first.initCause(second)
        FailureShape.sqlState(first) shouldBe "none"
    }

    @Test
    fun `state on the sixteenth cause is included but seventeenth is excluded`() {
        val state = SQLException("synthetic", "40001")
        val sixteenth = (1..15).fold<Int, Throwable>(state) { cause, _ -> RuntimeException("wrapper", cause) }
        FailureShape.sqlState(sixteenth) shouldBe "40001"
        FailureShape.sqlState(RuntimeException("wrapper", sixteenth)) shouldBe "none"
    }
}
