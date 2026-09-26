package co.datapipelines.web.skill

import co.datapipelines.mcp.docs.DocContext
import co.datapipelines.mcp.docs.DocErrorCatalog
import co.datapipelines.mcp.docs.DocRenderer
import co.datapipelines.mcp.docs.DocSet
import co.datapipelines.web.api.ApiExceptionHandler
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * `GET /skill.md` and `GET /skill/{name}.md` (095 §C3, the rendered set since 242a) — the
 * delivery for every client that speaks no MCP.
 *
 * What the wire has to be right about: the media type (an agent saving the response writes a
 * `.md` file), the BYTES (the same rendered documents the MCP resource and tools serve), the
 * one-release aliases (old names answer with the successor's bytes), and the refusal shape (a
 * mistyped name is the §4.2 envelope, not an HTML page and not a 200 with an empty body).
 */
class SkillControllerTest {
    /**
     * The rendered set from the REAL renderer over the REAL narrative resources; the tool
     * instances and the §13 error rows are shapes here — the routing and the bytes are the
     * subject, and `mcp-server`'s own guards hold the full set to its catalogs.
     */
    private val docSet: DocSet =
        DocRenderer(
            DocContext.DEFAULTS,
            object : DocErrorCatalog {
                override fun describe(code: String): DocErrorCatalog.ErrorDocRow =
                    DocErrorCatalog.ErrorDocRow(400, "13-error-code-catalog", "Refused ($code).")
            },
            emptyList(),
        ).render()

    private val mvc =
        MockMvcBuilders
            .standaloneSetup(SkillController(docSet))
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    @Test
    fun `the core is served as markdown, byte-identical to the served set`() {
        mvc
            .perform(get("/skill.md"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/markdown;charset=UTF-8"))
            .andExpect(content().string(docSet.core.markdown))
            .andExpect(content().string(containsString("# datapipelines")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
    }

    @Test
    fun `every document of the set is served at its flat name`() {
        for (doc in docSet.docs) {
            mvc
                .perform(get("/skill/${doc.name}.md"))
                .andExpect(status().isOk)
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().string(doc.markdown))
        }
    }

    @Test
    fun `the old names answer with their successors' bytes`() {
        // The one-release aliases (record §4, ruling O4) — `connecting` is what the previous
        // delivery's map taught, and the bytes must be the successor's, not a second copy.
        val viaOld =
            mvc
                .perform(get("/skill/connecting.md"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        viaOld shouldBe docSet.get("datasources-connecting").markdown
    }

    @Test
    fun `an unknown name is a 404 in the unified error envelope`() {
        mvc
            .perform(get("/skill/nope.md"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.error.code").value("pipeline.execution.not_found"))
            .andExpect(jsonPath("$.error.details.reason").value("skill_reference_not_found"))
            // The refusal names where the list of real documents is, so the next request works.
            .andExpect(jsonPath("$.error.message").value(containsString("/skill.md")))
    }

    @Test
    fun `a name is a name, not a path`() {
        // %2F-encoded traversal is refused by the container before routing, but the handler
        // must not be the thing standing between a caller and the file system in the first
        // place: the lookup is a map, so anything shaped like a path simply is not a key.
        mvc.perform(get("/skill/..%2F..%2Fetc%2Fpasswd.md")).andExpect(status().is4xxClientError)
    }

    private infix fun String.shouldBe(expected: String) {
        org.junit.jupiter.api.Assertions
            .assertEquals(expected, this)
    }
}
