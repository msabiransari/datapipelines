package co.datapipelines.mcp

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The skill teaches principles, quirks and procedure — never facts about a dataset (owner,
 * 2026-09-11: "Skills must be generic with no information about the data whatsoever").
 *
 * Why it is a build failure and not a review note: a customer attaches their own datasources
 * and the agent reads the SAME skill. A sentence like "filter on `pickup_date`" or "exclude
 * EWR" is a fact about the sample data wearing the voice of a rule — the agent applies it to a
 * warehouse where it is false, and the failure is silent. The audit of `notes/pipeline-3.txt`
 * found the playbook written FROM the demo; this test keeps it out.
 *
 * The scan covers the hand-written skill files AND the rendered `references/tools.md`, because
 * tool DESCRIPTIONS are agent-facing text too (they reach the agent through `tools/list`
 * before any document does) and `tools.md` is rendered from them.
 *
 * The token list is the sample data's vocabulary — table names, family names, the datasource
 * names `app.sh --demo` registers, the domain words the demo questions use. Add to it when a
 * new family ships; never remove a token to make a sentence pass.
 */
class SkillHasNoDemoContentTest {
    private val skillDir = File(SpecFiles.root, SpecFiles.SKILL_DIR)

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
    fun `no skill file names the sample data - the agent learns from the datasource, not from us`() {
        val files = skillFiles()
        files.size shouldBeGreaterThan 5 // non-vacuity: the scan saw the real skill
        val hits =
            files.flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val lower = line.lowercase()
                    val found = forbidden.filter { token -> matches(lower, token) }
                    if (found.isEmpty()) {
                        null
                    } else {
                        val where = "${file.relativeTo(skillDir)}:${index + 1}"
                        "$where: ${found.joinToString()} — ${line.trim().take(90)}"
                    }
                }
            }
        hits.shouldBeEmpty()
    }

    private fun skillFiles(): List<File> =
        skillDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .toList()

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
}
