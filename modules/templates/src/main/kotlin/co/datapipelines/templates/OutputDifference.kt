package co.datapipelines.templates

import co.datapipelines.scripting.CanonicalJson

/** How a test case's expected output is compared with the evaluated one (record §5.5). */
internal object OutputDifference {
    /**
     * The first structural difference between two JSON-shaped values below [path], as the path and
     * the two differing values, or null when they agree. Numbers are compared by value.
     */
    @Suppress("ReturnCount") // recursion returns the first difference found; every other path falls through to null
    fun first(
        path: String,
        expected: Any?,
        actual: Any?,
    ): Triple<String, Any?, Any?>? {
        if (expected is Map<*, *> && actual is Map<*, *>) {
            (expected.keys + actual.keys).distinct().forEach { key ->
                val diff = first("$path.$key", expected[key], actual[key])
                if (diff != null) return diff
            }
            return null
        }
        if (expected is List<*> && actual is List<*>) {
            val size = maxOf(expected.size, actual.size)
            for (index in 0 until size) {
                val diff =
                    first(
                        "$path[$index]",
                        expected.getOrNull(index),
                        actual.getOrNull(index),
                    )
                if (diff != null) return diff
            }
            return null
        }
        return if (CanonicalJson.equal(expected, actual)) null else Triple(path, expected, actual)
    }
}
