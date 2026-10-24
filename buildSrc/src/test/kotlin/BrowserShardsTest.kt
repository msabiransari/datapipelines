import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The browser suite's CI partition (#669, [BrowserShards]): pure functions, no TestKit.
 * Not CI coverage — `./gradlew build` never runs buildSrc's tests (gate.sh runs them);
 * the CI-covered guard is `:tests:browser-tests:verifyBrowserShardPartition`.
 */
class BrowserShardsTest {

    @TempDir
    lateinit var root: File

    @Test
    fun `every name lands in exactly one shard and no shard is empty`() {
        val names = (1..126).map { "co.example.C%03dTest".format(it) }.shuffled()
        val shards = (1..3).map { BrowserShards.partition(names, it, 3) }
        assertEquals(names.sorted(), shards.flatten().sorted())
        assertEquals(names.size, shards.flatten().toSet().size)
        assertEquals(listOf(42, 42, 42), shards.map { it.size })
    }

    @Test
    fun `the partition is round-robin over the byte-order sort, independent of input order`() {
        val names = listOf("b.ZTest", "a.BTest", "a.ATest", "B.XTest", "a.aTest")
        // Byte order: upper case before lower case, no locale folding.
        assertEquals(listOf("B.XTest", "a.BTest", "b.ZTest"), BrowserShards.partition(names, 1, 2))
        assertEquals(listOf("a.ATest", "a.aTest"), BrowserShards.partition(names, 2, 2))
        assertEquals(BrowserShards.partition(names, 1, 2), BrowserShards.partition(names.reversed(), 1, 2))
    }

    @Test
    fun `parseShard accepts i of the constant and refuses every other shape by its rule`() {
        assertEquals(BrowserShards.Shard(2, 3), BrowserShards.parseShard("2/3", 3))
        assertEquals(BrowserShards.Shard(3, 3), BrowserShards.parseShard(" 3/3 ", 3))
        assertRefused("4/3", "outside 1..3")
        assertRefused("0/3", "outside 1..3")
        assertRefused("1/4", "BROWSER_SHARDS=3")
        for (bad in listOf("x/y", "1", "1/3/3", "", "1/")) assertRefused(bad, "is not <i>/<n>")
    }

    private fun assertRefused(value: String, rule: String) {
        val refusal = assertThrows<IllegalArgumentException> { BrowserShards.parseShard(value, 3) }
        val message = refusal.message.orEmpty()
        assertTrue(message.contains(rule), "'$value' → $message")
    }

    @Test
    fun `class names come from the tree with the package taken from the directory`() {
        write("co/x/BTest.kt", "package co.x\n\nclass BTest\n")
        write("co/x/y/ATest.kt", "package co.x.y\n\nclass ATest\n")
        write("co/x/Helper.kt", "package co.x\n\nclass Helper\n")
        assertEquals(listOf("co.x.BTest", "co.x.y.ATest"), BrowserShards.testClassNames(root))
        assertEquals(emptyList<String>(), BrowserShards.unshardableSources(root))
    }

    @Test
    fun `a test class outside a file of its own name and a misplaced package are refused by name`() {
        write("co/x/ZzzProbe.kt", "package co.x\n\nclass ZzzProbeTest {\n    class InnerTest\n}\n")
        write("co/x/OtherTest.kt", "package co.x\n\nclass OtherTest\n\ninternal open class SecondTest\n")
        write("co/x/MovedTest.kt", "package co.elsewhere\n\nclass MovedTest\n")
        assertEquals(
            listOf(
                "co/x/MovedTest.kt: package 'co.elsewhere' is not its directory 'co.x'",
                "co/x/OtherTest.kt: class SecondTest is not in a file of its own name",
                "co/x/ZzzProbe.kt: class ZzzProbeTest is not in a file of its own name",
            ),
            BrowserShards.unshardableSources(root),
        )
    }

    private fun write(path: String, text: String) {
        root.resolve(path).apply { parentFile.mkdirs() }.writeText(text)
    }
}
