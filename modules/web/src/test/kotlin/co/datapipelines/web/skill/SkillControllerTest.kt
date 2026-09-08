package co.datapipelines.web.skill

import co.datapipelines.mcp.SkillDocs
import co.datapipelines.web.api.ApiExceptionHandler
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * `GET /skill.md` and `GET /skill/{reference}.md` (095 §C3) — the delivery for every client
 * that speaks no MCP.
 *
 * What the wire has to be right about: the media type (an agent saving the response writes a
 * `.md` file), the BYTES (the same ones the MCP resource and the repo hold — a second copy is
 * the failure mode this whole round exists to prevent), and the refusal shape (a mistyped
 * reference is the §4.2 envelope, not an HTML page and not a 200 with an empty body).
 */
class SkillControllerTest {
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(SkillController())
            .setControllerAdvice(ApiExceptionHandler())
            .build()

    @Test
    fun `the skill is served as markdown, byte-identical to the packaged copy`() {
        mvc
            .perform(get("/skill.md"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("text/markdown;charset=UTF-8"))
            .andExpect(content().string(SkillDocs.skill))
            // The front matter has to survive the round trip: it is what makes the response a
            // SKILL when an agent drops it into .agents/skills/, not just a page of prose.
            .andExpect(content().string(startsWith("---\nname: datapipelines\n")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
    }

    @Test
    fun `every reference the skill advertises is served at its own URL`() {
        SkillDocs.references.forEach { (name, body) ->
            mvc
                .perform(get("/skill/$name.md"))
                .andExpect(status().isOk)
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(content().string(body))
        }
    }

    @Test
    fun `an unknown reference is a 404 in the unified error envelope`() {
        mvc
            .perform(get("/skill/nope.md"))
            .andExpect(status().isNotFound)
            .andExpect(content().contentType("application/json"))
            .andExpect(jsonPath("$.error.code").value("pipeline.execution.not_found"))
            .andExpect(jsonPath("$.error.details.reason").value("skill_reference_not_found"))
            // The refusal names where the list of real references is, so the next request works.
            .andExpect(jsonPath("$.error.message").value(containsString("/skill.md")))
    }

    @Test
    fun `a reference name is a name, not a path`() {
        // %2F-encoded traversal is refused by the container before routing, but the handler
        // must not be the thing standing between a caller and the file system in the first
        // place: the lookup is a map, so anything shaped like a path simply is not a key.
        mvc.perform(get("/skill/..%2F..%2Fetc%2Fpasswd.md")).andExpect(status().is4xxClientError)
    }
}
