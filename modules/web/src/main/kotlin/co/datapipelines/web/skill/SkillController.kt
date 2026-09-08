package co.datapipelines.web.skill

import co.datapipelines.mcp.SkillDocs
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.web.api.ApiErrors
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * `GET /skill.md` and `GET /skill/{reference}.md` — the agent skill over plain HTTP (095 §C3).
 *
 * This is the delivery for every client that is not Claude Code and speaks no MCP: a Cursor,
 * Codex or Copilot user runs one `curl` into `.agents/skills/datapipelines/` and their agent
 * has the manual for THIS deployment — the version it actually runs, not whatever a README
 * described when it was written. The bytes are [SkillDocs]', the same ones
 * `datapipelines://docs/skill` serves and the same ones the repository holds, so the three
 * surfaces cannot answer differently.
 *
 * ## Why it is public
 *
 * It is the manual. It contains no secrets, reads no principal, resolves no workspace and
 * touches no datastore — it returns a constant packaged in the jar, and the identical text is
 * public in the AGPL repository on GitHub. Requiring a key would only mean an agent cannot
 * learn how to use its key correctly until after it has one, which is the wrong order. The
 * `permitAll` entries carry the same reasoning in `SecurityConfig`.
 *
 * ## Why `@Controller` and not `@RestController`
 *
 * `RequiredScopeCoverageTest` and its Konsist twin require every `@RestController` in this
 * module to declare a §7.6 operation, and this route deliberately has none: it is outside the
 * scope-governed prefixes (`/api`, `/partials`, `/mcp`) and public by design. The house
 * pattern for exactly that is `@Controller` + `@ResponseBody` — `SitemapController` serves
 * `/sitemap.xml` the same way.
 *
 * ## Why not `co.datapipelines.web.ui`
 *
 * `UiExceptionHandler` is scoped to that package at `HIGHEST_PRECEDENCE`, so a failure raised
 * there renders an HTML error page. A `curl` of a mistyped reference must come back as the
 * §4.2 JSON envelope, which is what `ApiExceptionHandler` gives every other package.
 *
 * ## The 404's `user_message` says "pipeline", knowingly
 *
 * `pipeline.execution.not_found` is the nearest catalogued code (the same one
 * `ApiExceptionHandler.onNoResource` reuses for an unknown address), and a
 * `DatapipelinesException` cannot carry its own user message — the catalog supplies one per
 * code. Raising a `ResponseStatusException` instead would buy the right prose and lose
 * `details.reason`, which is the field that separates "no such reference" from "no such
 * route" and the only one an agent can branch on. The precise `reason` won. If §13 ever
 * gains a docs-side not-found code, this is the one throw site to change.
 */
@Controller
class SkillController {
    @GetMapping("/skill.md", produces = [MARKDOWN])
    @ResponseBody
    fun skill(response: HttpServletResponse): String {
        cache(response)
        return SkillDocs.skill
    }

    @GetMapping("/skill/{reference}.md", produces = [MARKDOWN])
    @ResponseBody
    fun reference(
        @PathVariable reference: String,
        response: HttpServletResponse,
    ): String {
        val body =
            SkillDocs.reference(reference)
                ?: throw DatapipelinesException(
                    code = PipelineErrorCodes.Execution.NOT_FOUND,
                    message = "No skill reference '$reference'. Read /skill.md — its reference map lists them all.",
                    details = mapOf(ApiErrors.REASON to "skill_reference_not_found"),
                )
        cache(response)
        return body
    }

    /**
     * The same shared-cache window every public route carries: the content is immutable for
     * the lifetime of a deployed jar, so a re-fetch is only interesting after a deploy.
     */
    private fun cache(response: HttpServletResponse) {
        response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            CacheControl.maxAge(MAX_AGE_MINUTES, TimeUnit.MINUTES).cachePublic().headerValue,
        )
    }

    private companion object {
        /** Markdown, explicitly charset-qualified: the skill is full of em dashes and `→`. */
        const val MARKDOWN = "text/markdown;charset=UTF-8"

        const val MAX_AGE_MINUTES = 15L
    }
}
