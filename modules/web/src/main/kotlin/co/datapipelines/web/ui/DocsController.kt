package co.datapipelines.web.ui

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView

/**
 * The in-product spec set (033): `GET /docs` (grouped index) and `GET /docs/{slug}`.
 * Session-authenticated only (reviewer's answer: docs are a human surface — no API-key
 * access; the key surface stays minimal after four key→session escalation findings).
 * Everything served here is memoized in [DocsCatalog] — the docs are immutable in the jar,
 * which is the point: they always describe the version they run on.
 */
@Controller
class DocsController(
    private val docs: DocsCatalog,
) {
    @GetMapping("/docs")
    fun index(model: Model): String {
        model.addAttribute("groups", docs.index())
        return "docs/index"
    }

    @GetMapping("/docs/{slug}")
    fun doc(
        @PathVariable slug: String,
    ): ModelAndView {
        val rendered = docs.render(slug.lowercase()) ?: return ModelAndView("error/404", HttpStatus.NOT_FOUND)
        return ModelAndView("docs/doc").apply {
            addObject("docTitle", rendered.entry.title)
            addObject(
                "docHtml",
                // 033/B1 — this reaches the page as a model attribute inserted with th:utext,
                // so a "${" inside it is DATA and Thymeleaf never evaluates it (13 of the
                // packaged docs legitimately contain ${VAR} placeholders in YAML/env examples).
                // Do NOT "fix" this into an inline expression ([[...]] / [(...)]): inlining
                // would parse the doc body as a template and evaluate those placeholders.
                rendered.html,
            )
        }
    }
}
