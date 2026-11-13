package co.datapipelines.mcp

import co.datapipelines.auth.AuthErrorCodes
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The keys-v2 prose contract of **mcp-server.md itself** (#706) — the spec-drift pattern
 * `McpToolSurfaceSpecDriftTest` applies to the §6.2 tool blocks, applied to the §4.1, §6.2
 * preamble and §9.3 sentences that say what an MCP key may do and what a refusal means.
 *
 * Keys v2 (#233) made the MCP key its OWN identity, judged by the role chosen at creation;
 * the retired reading — the key does what its member can do NOW, capped at author (PK4),
 * re-read from the membership on every request, refusing `auth.key_issuer_role_lost` with
 * "the fix is a role" — kept standing in this spec's own prose after v1.50 removed it from
 * the code, contradicting the correct keys-v2 bullets a few lines above it. The public
 * client-setup page sends a refused reader to §9.3, so the stale repair instruction was not
 * a documentation nicety: it pointed every stranded agent at a fix that changes nothing for
 * a key (a member-role change) while the one that works — create a replacement key under the
 * subset rule, revoke the old — went unsaid. This test pins the corrected prose so the
 * retired vocabulary cannot creep back into the live text: history rows (Appendix A) keep
 * their old claims on purpose, so the guarded text is everything BEFORE the change log.
 *
 * The refusal code is asserted against the SHIPPED constant (`AuthErrorCodes.ROLE_REQUIRED`,
 * parsed back out of the prose's own backticked token), not a string literal in this file —
 * renaming the constant turns this red even when the doc was reworded to match yesterday's
 * wire word. `ScopeMatrix` answers that constant for every key principal carrying a key role
 * (keys v2); the context-judged arm is the only `auth.key_issuer_role_lost` left, and it is
 * not an MCP answer.
 *
 * What this test does NOT check: the served manual (the packaged skill resources — grepped
 * clean at dispatch) and `docs/pipeline-contract.md` §13's row prose, whose catalog parse
 * (`PipelineErrorCodesSpecDriftTest`) pins the code names but not the sentences around them.
 */
class McpKeysV2ProseDriftTest {
    private val spec = SpecFiles.read(SpecFiles.MCP_SPEC_PATH)

    /** The live contract: everything before the change log — Appendix A's history keeps the retired claims. */
    private val live: String = spec.substringBefore(CHANGE_LOG_HEADING)

    @Test
    fun `the live prose never teaches the retired cap, freshness rule or issuer refusal`() {
        val violations =
            RETIRED_PHRASES.flatMap { phrase ->
                Regex(Regex.escape(phrase), RegexOption.IGNORE_CASE)
                    .findAll(live)
                    .map { "line ${live.lineNumberAt(it.range.first)}: \"$phrase\"" }
                    .toList()
            }
        withClue("mcp-server.md's live text still teaches a retired keys-v2 claim (#706)") {
            violations.shouldBeEmpty()
        }
    }

    @Test
    fun `§4_1 names the shipped role-refusal code`() {
        withClue("§4.1 must state the code an MCP key's own-role refusal carries — the shipped constant") {
            section("### 4.1 Auth model", "### 4.2 Unauthorized behavior") shouldContain AuthErrorCodes.ROLE_REQUIRED
            section("### 4.1 Auth model", "### 4.2 Unauthorized behavior") shouldContain "creator's deactivation"
        }
    }

    @Test
    fun `§9_3 names the shipped role-refusal code and the working repair`() {
        val nine3 = section("### 9.3 Transport errors", "## 10. Logging")
        withClue("§9.3 must name the shipped refusal code") {
            nine3 shouldContain AuthErrorCodes.ROLE_REQUIRED
        }
        withClue("§9.3 must state the repair that works: a replacement key under the subset rule") {
            nine3 shouldContain "subset rule"
            nine3 shouldContain "MCP creator"
        }
    }

    @Test
    fun `the refusal code in the prose IS the constant the matrix answers with`() {
        val four1 = section("### 4.1 Auth model", "### 4.2 Unauthorized behavior")
        val nine3 = section("### 9.3 Transport errors", "## 10. Logging")
        val namedIn41 = Regex("A refusal is `([a-z._]+)`").find(four1)?.groupValues?.get(1)
        val namedIn93 = Regex("`isError` and `([a-z._]+)`").find(nine3)?.groupValues?.get(1)
        withClue("§4.1's refusal code must be exactly AuthErrorCodes.ROLE_REQUIRED (ScopeMatrix's answer for a key principal)") {
            namedIn41 shouldBe AuthErrorCodes.ROLE_REQUIRED
        }
        withClue("§9.3's refusal code must be exactly AuthErrorCodes.ROLE_REQUIRED") {
            namedIn93 shouldBe AuthErrorCodes.ROLE_REQUIRED
        }
    }

    private fun section(
        from: String,
        to: String,
    ): String = spec.substring(spec.indexOf(from), spec.indexOf(to))

    private fun String.lineNumberAt(index: Int): Int = this.take(index).count { it == '\n' } + 1

    private companion object {
        /** From this heading on the file is history: Appendix A's rows keep the retired claims on purpose. */
        private const val CHANGE_LOG_HEADING = "## Appendix A: Change Log"

        /**
         * The retired keys-v2 vocabulary (#706), matched case-insensitively over the whole live
         * text: the PK4 cap, the membership-freshness rule (C2) and the demotion refusal's own
         * teaching sentence. A hit anywhere before Appendix A is a live claim the code retired.
         */
        private val RETIRED_PHRASES =
            listOf(
                "capped at author",
                "member can do now",
                "pk4 cap",
                "membership freshness",
                "freshness rule",
            )
    }
}
