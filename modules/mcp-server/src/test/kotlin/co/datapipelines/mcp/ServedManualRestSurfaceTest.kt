package co.datapipelines.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * REST is not the agent's surface (#164, the owner's ruling of 2026-10-02): an agent uses MCP for
 * everything, and the ONE REST call it makes by design is the visualization test screenshot upload,
 * credentialed by the single-use token `visualizations_test_submit` returns — never the API key.
 *
 * The served manual is where an agent would learn otherwise: a `curl` recipe with a key in a header,
 * a `Bearer` line, an `/api/v1/` path taught as a road. This guard reads the RENDERED set (narrative
 * and generated alike, the bytes the server serves — the [SkillHasNoDemoContentTest] posture) and
 * refuses any line that carries such a literal unless [ALLOWLIST] names it with the reason it is
 * there. A fifth recipe is red, naming the document and the line; an entry whose literal vanished is
 * red too (the presence floor), so the guard cannot pass on an empty or rewritten manual.
 */
class ServedManualRestSurfaceTest {
    private val docSet by lazy { DocSetTestSupport.renderedDocSet() }

    @Test
    fun `every REST or credential literal in the served manual is allowlisted with its reason`() {
        val docs = docSet.docs
        docs.size shouldBeGreaterThan MIN_DOCUMENTS // non-vacuity: the scan saw the real set

        val unexpected =
            docs.flatMap { doc ->
                val entries = ALLOWLIST.filter { it.document == doc.name }
                doc.markdown.lines().mapIndexedNotNull { index, line ->
                    val residue = entries.fold(line) { rest, entry -> rest.replace(entry.literal, "") }
                    val found = SURFACE.findAll(residue).map { it.value }.toList()
                    if (found.isEmpty()) {
                        null
                    } else {
                        "${doc.name}.md:${index + 1}: ${found.joinToString()} — ${line.trim().take(MAX_QUOTE)}"
                    }
                }
            }

        withClue("a REST or credential literal outside the allowlist — an agent must be taught MCP, never a REST recipe") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `every allowlist entry is still present in its document - the guard cannot pass on an emptied manual`() {
        val byName = docSet.docs.associateBy { it.name }
        val missing =
            ALLOWLIST.filter { entry ->
                byName[entry.document]?.markdown?.contains(entry.literal) != true
            }
        withClue("an allowlisted literal vanished from its document: delete the entry or restore the sentence") {
            missing.map { "${it.document}.md: ${it.literal}" }.shouldBeEmpty()
        }
    }

    @Test
    fun `the allowlist states a reason for every entry and none repeats`() {
        ALLOWLIST.filter { it.why.isBlank() }.shouldBeEmpty()
        ALLOWLIST.map { it.document to it.literal }.let { it.size shouldBe it.toSet().size }
    }

    @Test
    fun `the pattern catches a key literal in any header spelling and leaves prose alone`() {
        val caught =
            listOf(
                "DP-API-Key: dpk_abc.def",
                "x-api-key: abc",
                "X-Api-Key=abc",
                "api_key=abc",
                "apikey: abc",
                "Authorization: bearer abc",
                "curl -s https://host/x",
                "CURL https://host/x",
                "dpk_DEMOPUBLIC42.SECRET",
                "GET /api/v1/executions/x",
                "POST /api/<anything>",
                "DP-Upload-Token: abc",
            )
        val ignored =
            listOf(
                "create the API key on the Keys page",
                "the /api-keys page lists them",
                "an API key is shown once",
                "the endpoint serves /api/finance/v1/revenue",
                "(`auth.api_key.invalid`)",
                "`api_key.create`, not yours",
            )
        assertEach(caught) { SURFACE.containsMatchIn(it) shouldBe true }
        assertEach(ignored) { SURFACE.containsMatchIn(it) shouldBe false }
    }

    private fun assertEach(
        samples: List<String>,
        check: (String) -> Unit,
    ) = samples.forEach { sample -> withClue("sample: $sample") { check(sample) } }

    /** One allowlisted literal: the document that carries it, the text itself, and why an agent may read it. */
    private data class Allowed(
        val document: String,
        val literal: String,
        val why: String,
    )

    private companion object {
        const val MIN_DOCUMENTS = 15
        const val MAX_QUOTE = 90

        /**
         * What teaches a REST call or a credential, case-insensitively: `curl`, an `Authorization: Bearer` line, a key
         * literal (`dpk_…`), an API-key header in any spelling (hyphenated or underscored, with or without a prefix —
         * never the prose words "API key", nor a dotted identifier such as `auth.api_key.invalid` or `api_key.create`),
         * the upload capability's header, and the REST path roots.
         */
        val SURFACE =
            Regex(
                "curl|bearer|dpk_|" +
                    "(?<![a-z0-9])(?<![a-z]\\.)(?:[a-z]+-)*api[-_]?key(?![a-z0-9-])(?!\\.[a-z])|" +
                    "dp-upload-token|/api/v1/|/api/<",
                RegexOption.IGNORE_CASE,
            )

        val ALLOWLIST =
            listOf(
                Allowed(
                    "datasources",
                    "DP-API-Key",
                    "how the agent's OWN client authenticates to /mcp — the MCP key's header, the one surface that key has",
                ),
                Allowed(
                    "datasources",
                    "Authorization: Bearer",
                    "the same key's second accepted header form on /mcp; the sentence says it works on /mcp and nowhere else",
                ),
                Allowed(
                    "datasources",
                    "dpk_<id>.<secret>",
                    "the key's placeholder shape, no value — the format the agent's client is configured with",
                ),
                Allowed(
                    "endpoints",
                    "curl -s http://localhost:8080/api/finance/v1/revenue/EMEA -H \"DP-API-Key: dpk_...\"",
                    "the APPLICATION's call with its own endpoint key, labelled as such in the sentence above it — never the agent's",
                ),
                Allowed(
                    "core-error-codes",
                    "## Auth api_key",
                    "the generated error-code catalogue's group heading — the name of the auth.api_key family, not a recipe",
                ),
                Allowed(
                    "core-error-codes",
                    "§13#auth-api_key",
                    "the same group's anchor in the catalogue's source line — an error-code family name, not a recipe",
                ),
                Allowed(
                    "endpoints",
                    "/api/<category>/<version>/<path…>",
                    "the PUBLISHED endpoint's URL shape — what an application is given, explained so the agent can describe it",
                ),
                Allowed(
                    "endpoints-tools",
                    "/api/<category>/<version>/<path>",
                    "generated from the endpoints_create description: the URL the published endpoint is served at",
                ),
                Allowed(
                    "lake-tools",
                    "POST /api/v1/datasources/{name}/tables/import",
                    "generated from a shipped tool description: names the REST route the MCP tool mirrors; the tool is the surface",
                ),
                Allowed(
                    "lake-tools",
                    "POST /api/v1/datasources/{name}/tables:",
                    "generated from a shipped tool description: names the REST route the MCP tool mirrors; the tool is the surface",
                ),
                Allowed(
                    "lake-tools",
                    "DELETE /api/v1/datasources/{name}/tables/{namespace}/{table}",
                    "generated from a shipped tool description: names the REST route the MCP tool mirrors; the tool is the surface",
                ),
                Allowed(
                    "dashboards",
                    "/api/v1/visualizations|dashboards/import",
                    "names the import route as a workspace-admin verb the agent does not call, not a recipe",
                ),
                Allowed(
                    "dashboards",
                    "DP-Upload-Token",
                    "the header NAME of the single-use upload capability: the ONE REST call an agent makes, audited, never a key",
                ),
            )
    }
}
