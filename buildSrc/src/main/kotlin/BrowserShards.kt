import java.io.File

/**
 * The browser suite's CI partition (#669): CI runs `:tests:browser-tests:test` as
 * [BROWSER_SHARDS] parallel jobs, each passing `-Pdp.browser.shard=<i>/<n>`.
 *
 * The rule is sorted round-robin over the class list READ FROM THE TREE at
 * configuration time: every `*Test.kt` under the test source root, its FQN derived
 * from the path (package = directory), sorted by plain `String` order (no locale).
 * Shard i of n (1-based) holds the classes at sorted index ≡ i−1 (mod n). A
 * round-robin over one sorted list is complete and disjoint by construction, so no
 * weight table can drift from the tree (the owner, #669: "an equal-weight split is a
 * parity test away from drifting"). A new class lands in a shard with no edit here.
 *
 * The rule only holds while every test class sits in a file of its own name, in the
 * package its directory names: a class the filter never names would run unsharded and in
 * NO shard, and a `*Test.kt` whose package differs would be filtered for an FQN that does
 * not exist. [unshardableSources] finds both, and the browser module's
 * `verifyBrowserShardPartition` (wired into its `check`) refuses them by name.
 * DEVELOPMENT.md §9.4.
 */
object BrowserShards {
    /** The shard count. CI's `browser` matrix lists 1..this; a `-P` with another n is refused. */
    const val BROWSER_SHARDS = 3

    /** The project property carrying `<i>/<n>`; absent = the unfiltered suite. */
    const val PROPERTY = "dp.browser.shard"

    /** Non-vacuity floor for the partition guard: 153 classes on 2026-11-13 (was 100 over 126, 2026-10-22). */
    const val MIN_CLASSES = 140

    private const val TEST_FILE_SUFFIX = "Test.kt"

    private val PACKAGE = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)

    // A top-level (unindented) class declaration whose name ends in `Test`, any modifiers.
    private val TOP_LEVEL_TEST_CLASS =
        Regex(
            """^(?:(?:public|internal|private|open|abstract|final|data|sealed|annotation|inner)\s+)*class\s+(\w+Test)\b""",
            RegexOption.MULTILINE,
        )

    /** One shard: 1-based [index] of [count]. */
    data class Shard(
        val index: Int,
        val count: Int,
    )

    /**
     * Parses `<i>/<n>`, refusing anything but integers with n == [expectedCount] and
     * 1 ≤ i ≤ n — a CI matrix that drifts from [BROWSER_SHARDS] is red on its first leg,
     * never silently unbalanced.
     */
    fun parseShard(
        value: String,
        expectedCount: Int = BROWSER_SHARDS,
    ): Shard {
        val parts = value.trim().split("/")
        val index = parts.getOrNull(0)?.toIntOrNull()
        val count = parts.getOrNull(1)?.toIntOrNull()
        require(parts.size == 2 && index != null && count != null) {
            "-P$PROPERTY='$value' is not <i>/<n> with integers i and n (e.g. 1/$expectedCount)"
        }
        require(count == expectedCount) {
            "-P$PROPERTY='$value': n=$count but BrowserShards.BROWSER_SHARDS=$expectedCount " +
                "(buildSrc/src/main/kotlin/BrowserShards.kt) — the CI matrix and the constant must agree"
        }
        require(index in 1..count) { "-P$PROPERTY='$value': i=$index is outside 1..$count" }
        return Shard(index, count)
    }

    /** Shard [index] (1-based) of [count] over [names]: sorted, then every count-th from index−1. */
    fun partition(
        names: List<String>,
        index: Int,
        count: Int,
    ): List<String> {
        require(count >= 1) { "shard count must be ≥ 1, was $count" }
        require(index in 1..count) { "shard index $index is outside 1..$count" }
        return names.sorted().filterIndexed { i, _ -> i % count == index - 1 }
    }

    /** Every `*Test.kt` under [sourceRoot] as an FQN (package = the directory path), sorted. */
    fun testClassNames(sourceRoot: File): List<String> =
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(TEST_FILE_SUFFIX) }
            .map { file ->
                val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath.removeSuffix(".kt")
                relative.replace('/', '.')
            }.toList()
            .sorted()

    /**
     * Every source the partition would mis-shard, as `<relative path>: <why>`: a top-level
     * `class …Test` in a file not named after it (it would run unsharded and in no shard),
     * and a `*Test.kt` whose `package` is not its directory (its derived FQN would not exist).
     */
    fun unshardableSources(sourceRoot: File): List<String> =
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(sourceRoot).invariantSeparatorsPath
                val text = file.readText()
                val misnamed =
                    TOP_LEVEL_TEST_CLASS
                        .findAll(text)
                        .map { it.groupValues[1] }
                        .filter { it != file.nameWithoutExtension }
                        .map { "$path: class $it is not in a file of its own name" }
                val directoryPackage = path.substringBeforeLast('/', "").replace('/', '.')
                val declared =
                    PACKAGE
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                val misplaced =
                    if (file.name.endsWith(TEST_FILE_SUFFIX) && declared != directoryPackage) {
                        sequenceOf("$path: package '$declared' is not its directory '$directoryPackage'")
                    } else {
                        emptySequence()
                    }
                misnamed + misplaced
            }.toList()
            .sorted()
}
