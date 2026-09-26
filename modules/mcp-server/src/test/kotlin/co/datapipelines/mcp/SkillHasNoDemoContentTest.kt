package co.datapipelines.mcp

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test

/**
 * The manual teaches principles, quirks and procedure — never facts about a dataset (owner,
 * 2026-09-11: "Skills must be generic with no information about the data whatsoever"). Since
 * 242a the guard runs over the RENDERED set ([co.datapipelines.mcp.docs.DocSet]) — the bytes
 * the server actually serves, narrative and generated alike — instead of the repository
 * files; the handshake (`server-instructions.txt`) is still scanned here, the one delivered
 * surface no document reaches.
 *
 * Why it is a build failure and not a review note: a customer attaches their own datasources
 * and the agent reads the SAME manual. A sentence like "filter on `pickup_date`" is a fact
 * about the sample data wearing the voice of a rule — the agent applies it to a warehouse
 * where it is false, and the failure is silent.
 *
 * The token list is the sample data's vocabulary — table names, family names, the datasource
 * names `app.sh --demo` registers, the domain words the demo questions use. Add to it when a
 * new family ships; never remove a token to make a sentence pass.
 */
class SkillHasNoDemoContentTest {
    private val docSet by lazy { DocSetTestSupport.renderedDocSet() }

    private val forbidden =
        listOf(
            // the shipped demo's namespace, datasource names and tables, then its domain vocabulary
            // (whole words, case-insensitive). `census` alone is statistics vocabulary (sample vs
            // census) and stays allowed; the Bureau is caught by its family tokens and "us census".
            "nyc/",
            "nyc.",
            "nyc-",
            "sample-trips",
            "sample-lake",
            "sample-weather",
            "sample-reference",
            "sample-trade",
            "hvfhv",
            "hvfhs",
            "trips_daily",
            "trips_monthly",
            "zone_day",
            "taxi_zones",
            "rate_codes",
            "payment_types",
            "trade_monthly",
            "comtrade",
            "fx_daily",
            "fx_monthly",
            "taxi",
            "rideshare",
            "borough",
            "yellow-taxi",
            "ewr",
            "tlc",
            "noaa",
            "ghcn",
            "us census",
            "manhattan",
            "brooklyn",
            "queens",
            "jfk",
            "laguardia",
            "pickup_date",
            "pickup_at",
            "pu_location_id",
            "trip_count",
            "hvfhs_license_num",
            "--demo",
        )

    @Test
    fun `no rendered document names the sample data - the agent learns from the datasource, not from us`() {
        val docs = docSet.docs
        docs.size shouldBeGreaterThan 15 // non-vacuity: the scan saw the real set
        val hits =
            docs.flatMap { doc ->
                doc.markdown.lines().mapIndexedNotNull { index, line ->
                    val lower = line.lowercase()
                    val found = forbidden.filter { token -> matches(lower, token) }
                    if (found.isEmpty()) {
                        null
                    } else {
                        "${doc.name}.md:${index + 1}: ${found.joinToString()} — ${line.trim().take(90)}"
                    }
                }
            }
        hits.shouldBeEmpty()
    }

    @Test
    fun `the connect-time instructions name no sample data either`() {
        val instructions = SpecFiles.read(INSTRUCTIONS_PATH).lowercase()
        val hits = forbidden.filter { token -> matches(instructions, token) }
        hits.shouldBeEmpty()
    }

    /** Word-bounded for plain words; substring for path-shaped tokens (`nyc/`, `--demo`). */
    private fun matches(
        lower: String,
        token: String,
    ): Boolean {
        val plainWord = token.all { it.isLetterOrDigit() || it == '_' }
        return if (plainWord) {
            Regex("(?<![a-z0-9_])" + Regex.escape(token) + "(?![a-z0-9_])").containsMatchIn(lower)
        } else {
            lower.contains(token)
        }
    }

    private companion object {
        /** 144 — the connect-time briefing, the delivered instruction surface no document scan reaches. */
        const val INSTRUCTIONS_PATH = "modules/mcp-server/src/main/resources/mcp/server-instructions.txt"
    }
}
