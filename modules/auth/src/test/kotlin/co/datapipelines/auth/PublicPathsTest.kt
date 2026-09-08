package co.datapipelines.auth

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.junit.jupiter.api.Test

/**
 * The `permitAll` allowlist, guarded (096 §A — review finding F1/F5).
 *
 * Before this test the list was an inline `requestMatchers(...)` argument: nothing asserted
 * its contents, nothing asserted a reason existed for any entry, and `docs/auth.md` §8.3
 * documented 9 of its 22 patterns. Three claims, each of which was false or unprovable then:
 *
 *  1. **The set is frozen.** The patterns are written out here in full, so adding one is a
 *     visible diff in a TEST — the moment where "should this be public?" gets asked out
 *     loud — rather than one more line in a thirty-line argument list.
 *  2. **Every entry states why.** A pattern with no reason is a pattern nobody decided on;
 *     the ≥ 20-character floor is what stops "static" or "public" from passing as one.
 *  3. **The doc equals the code.** §8.3 is generated from [PublicPaths.ENTRIES], and parsed
 *     back here pattern-for-pattern, reason-for-reason and round-for-round, so the two
 *     cannot drift in either direction. Same shape (and same row-count guard) as
 *     [ScopeMatrixSpecDriftTest], which guards §7.6 the same way.
 *
 * The runtime half of F1 — "does any request mapping actually LAND on one of these
 * patterns?" — is `PublicRouteWalkerTest` in `modules/web`, where the mappings live.
 */
class PublicPathsTest {
    @Test
    fun `the allowlist is exactly these patterns, in this order`() {
        PublicPaths.ENTRIES.map { it.pattern } shouldContainExactly
            listOf(
                "/",
                "/site/**",
                "/mcp-server-for-sql-databases",
                "/mcp-server/*",
                "/add-mcp-server-to-claude-code",
                "/ai-data-pipeline",
                "/text-to-sql-agent",
                "/compare/*",
                "/federated-query",
                "/dp-lake",
                "/docs",
                "/docs/*",
                "/skill.md",
                "/skill/*",
                "/robots.txt",
                "/sitemap.xml",
                "/health",
                "/ready",
                "/info",
                "/login",
                "/login/**",
                "/oauth2/**",
                "/vendor/**",
                "/css/**",
                "/js/**",
                "/favicon.ico",
                "/error",
            )
    }

    @Test
    fun `no pattern is listed twice`() {
        PublicPaths.ENTRIES
            .map { it.pattern }
            .distinct()
            .size shouldBe PublicPaths.ENTRIES.size
    }

    @Test
    fun `every entry carries a reason a reviewer can act on`() {
        PublicPaths.ENTRIES.forEach { entry ->
            withClue(entry.pattern) {
                entry.reason.shouldNotBeBlank()
                // A reason shorter than this is a label, not a decision. `/favicon.ico`,
                // `/error` and the `webjars` glob shipped with NO reason at all until 096.
                (entry.reason.length >= MIN_REASON_LENGTH) shouldBe true
                entry.since.shouldNotBeBlank()
                // The §8.3 table is pipe-delimited Markdown; a pipe in a reason would split
                // a row and make the drift test parse a different string than it asserts.
                entry.reason.contains('|') shouldBe false
            }
        }
    }

    @Test
    fun `auth-md section 8-3 lists exactly the allowlist, reason for reason`() {
        val rows = parsePublicEndpointTable(RepoFiles.read(RepoFiles.AUTH_SPEC_PATH))

        // Row-count guard (the §7.6 drift test's shape): an over-permissive parser that
        // matched nothing would make every comparison below pass vacuously, and a row
        // deleted from BOTH sides at once would otherwise go unnoticed.
        rows.size shouldBe DOCUMENTED_ROWS
        rows.size shouldBe PublicPaths.ENTRIES.size
        rows shouldContainExactly PublicPaths.ENTRIES
    }

    /**
     * §8.3's table as [PublicPath] rows. Deliberately naive — a shape change makes it
     * return nothing and the row-count guard above fails loudly rather than silently
     * comparing two empty lists.
     */
    private fun parsePublicEndpointTable(doc: String): List<PublicPath> {
        val start = doc.indexOf(SECTION_HEADING)
        require(start >= 0) { "Could not find $SECTION_HEADING in ${RepoFiles.AUTH_SPEC_PATH}" }
        val end = doc.indexOf("### 8.4", start)
        require(end > start) { "Could not find the end of §8.3 in ${RepoFiles.AUTH_SPEC_PATH}" }

        return doc
            .substring(start, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .map { it.trim('|').split("|").map(String::trim) }
            .filter { it.size == TABLE_COLUMNS }
            .filterNot { it[0] == "Path pattern" || it[0].startsWith("---") }
            .map { cells -> PublicPath(cells[0].trim('`'), cells[1], cells[2]) }
            .toList()
    }

    private companion object {
        const val SECTION_HEADING = "### 8.3 Public endpoints"
        const val TABLE_COLUMNS = 3
        const val MIN_REASON_LENGTH = 20

        /**
         * 27 rows: the 033 site pair, the seven 073 intent-cluster pages, 089's `/dp-lake`,
         * the docs pair, 095's skill pair, the two crawler files, three probes, the
         * login/OIDC trio, four static-asset patterns and `/error`. 096 §B removed the
         * `webjars` glob (28 -> 27) when htmx was vendored under `static/vendor`.
         */
        const val DOCUMENTED_ROWS = 27
    }
}
