package co.datapipelines.web.api

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * The standing guard that [ApiErrorCatalog] stays a projection of the §13 tables
 * (pipeline-contract.md) — the single error-code catalog.
 *
 * Both directions are asserted: every documented code's HTTP column equals the catalog's status
 * for it, and the codes §13 marks "—" (never returned live) are exactly
 * [ApiErrorCatalog.NEVER_RETURNED_LIVE]. The §12 validation tables (which §13.1 delegates to)
 * carry no HTTP column; their rows are all 400 by §13.1's own sentence, with the one exception
 * `duplicate_name`, whose row documents 409 inline. Row-count guards fail the build when the doc
 * gains a row, so a new code must be wired here, not silently absorbed by a family default.
 */
class ApiErrorCatalogSpecDriftTest {
    @Test
    fun `every documented section-13 code maps to its documented HTTP status`() {
        val rows = parseStatusRows(TestRepoFiles.read(TestRepoFiles.CONTRACT_SPEC_PATH))
        rows.size shouldBe SECTION_13_ROW_COUNT

        rows.forEach { (code, http) ->
            if (http == null) {
                ApiErrorCatalog.NEVER_RETURNED_LIVE.contains(code) shouldBe true
            } else {
                ApiErrorCatalog.statusFor(code).value() shouldBe http
            }
        }
    }

    @Test
    fun `the never-returned-live set is exactly the documented dash rows`() {
        val rows = parseStatusRows(TestRepoFiles.read(TestRepoFiles.CONTRACT_SPEC_PATH))
        ApiErrorCatalog.NEVER_RETURNED_LIVE shouldBe rows.filter { it.second == null }.map { it.first }.toSet()
    }

    @Test
    fun `every section-12 validation code is 400, except duplicate_name which its row documents as 409`() {
        val codes = parseValidationCodes(TestRepoFiles.read(TestRepoFiles.CONTRACT_SPEC_PATH))
        codes.size shouldBe SECTION_12_CODE_COUNT

        codes.forEach { code ->
            val expected =
                if (code == PipelineErrorCodes.Validation.DUPLICATE_NAME) HttpStatus.CONFLICT else HttpStatus.BAD_REQUEST
            ApiErrorCatalog.statusFor(code) shouldBe expected
        }
    }

    /** §13.2–§13.12 rows: `| \`code\` | HTTP | … |` with a numeric or em-dash HTTP cell. */
    private fun parseStatusRows(spec: String): List<Pair<String, Int?>> =
        spec
            .lineSequence()
            .mapNotNull { STATUS_ROW_REGEX.find(it) }
            .map { match -> match.groupValues[1] to match.groupValues[2].trim().toIntOrNull() }
            .toList()

    /** §12 rows: `| \`pipeline.validation.code\` | rule |` — two columns, no HTTP cell. */
    private fun parseValidationCodes(spec: String): Set<String> =
        spec
            .lineSequence()
            .mapNotNull { VALIDATION_ROW_REGEX.find(it) }
            .map { it.groupValues[1] }
            .toSet()

    private companion object {
        val STATUS_ROW_REGEX = Regex("""^\|\s*`([a-z][a-z0-9_.]+)`\s*\|\s*([0-9]{3}|—)\s*\|""")
        val VALIDATION_ROW_REGEX = Regex("""^\| `(pipeline\.validation\.[a-z_]+)`\s*\|""")

        /**
         * §13.2–§13.12's row count. Both parallel lanes added rows and each bumped this
         * literal from the SAME base of 84 — 025b to 85 (§13.12 `workspace.session_required`),
         * 026 to 87 (§13.7's three local-auth rows) — so neither lane's number is right for
         * the merged document. Re-derived at merge from the merged spec, which is the only
         * way a hand-maintained count survives a parallel merge (P8).
         *
         * 88 → 89 in the post-merge 026 security fix: §13.7 gains `auth.session_required`,
         * landed in the SAME commit as its constant and catalog row (MISTAKES.md — a
         * catalogued code split from its constant leaves `main` red between the two).
         *
         * 89 → 97 with the versioning spec (versioning.md): the draft/release lifecycle,
         * preserved-version import and promotion add their rows to §13. Re-derived from the
         * merged document's own parse, not by adding up the rows a diff showed — this literal
         * is the one thing in the file that cannot be checked by the parse it guards.
         */
        const val SECTION_13_ROW_COUNT = 97

        /** §12's distinct validation codes on 2026-08-27 (workspaces readonly added §12.5's `pipeline.validation.datasource_readonly`). */
        const val SECTION_12_CODE_COUNT = 47
    }
}
