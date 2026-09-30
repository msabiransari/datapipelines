package co.datapipelines.executor

import co.datapipelines.typesystem.ColumnSchema
import co.datapipelines.typesystem.LogicalType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [ResultBytes] is the ONE row accounting behind two caps (the result store's and the dashboard collector's, #10 L2
 * §9.5): its length must equal the UTF-8 byte length of the row's JSON encoding for every code point width, and
 * [ResultBytes.rowBytes] must be exactly what `RedisResultStore` adds per row. Measured against the JDK's own encoder.
 */
class ResultBytesTest {
    @Test
    fun `utf8Length equals the JDK encoder across every width - ascii, two, three and four byte code points`() {
        listOf("", "abc", "é", "€", "日本語", "😀", "a😀b€é", "\"quoted\" \\ \n").forEach { text ->
            ResultBytes.utf8Length(text) shouldBe text.toByteArray(Charsets.UTF_8).size.toLong()
        }
    }

    /**
     * A LONE surrogate is not well-formed text: the JDK encoder writes `?` (1 byte) while the store counts the
     * 3 bytes a BMP code unit would take. The count is kept exactly as the store had it (this class was lifted
     * from it unchanged) and is the stricter of the two — it can only over-count against a cap.
     */
    @Test
    fun `a lone surrogate counts as a three-byte code unit - the store's accounting, kept`() {
        ResultBytes.utf8Length("\uD83D") shouldBe 3L
        ResultBytes.utf8Length("x\uD83Dy") shouldBe 5L
    }

    @Test
    fun `rowBytes is the utf8 length of the encoded row, the string the store pushes`() {
        val columns = listOf(ColumnSchema("name", LogicalType.STRING), ColumnSchema("n", LogicalType.INTEGER))
        val row = listOf<Any?>("héllo 😀", 42)

        val encoded = ResultBytes.encodeRow(row, columns)

        ResultBytes.rowBytes(row, columns) shouldBe encoded.toByteArray(Charsets.UTF_8).size.toLong()
        encoded shouldBe """["héllo 😀",42]"""
    }
}
