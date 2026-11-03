package co.datapipelines.web.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * The saved-body copy of the save-time scanner's undeclared-loading rule (#787, templates.md §6.3),
 * as operator visibility at boot.
 *
 * The FIX is the two code layers in `templates`: `ForbiddenConstructScanner` refuses the construct
 * at save, and `RegistryTemplateLoader` refuses any render-time load outside the declared import
 * closure. This check is neither — bodies saved BEFORE the save rule landed (imports from older
 * releases, restored promotion bundles) can still carry it, and the operator is told once at boot,
 * as a COUNT of stored bodies matching the raw-text pattern. This is a candidate inventory,
 * not the AST scanner's verdict: inert SQL text and script bodies can also match.
 *
 * The line is `event=templates.undeclared_loading_construct count=N` — WARN when N>0, INFO at
 * `count=0`. Count ONLY: no template id, no body text ever reaches the log (the same
 * never-echoes-author-content discipline as the loader's refusal message). A warn, not a refusal:
 * the two code layers already contain the construct at render, so the deployment is safe; the
 * operator decides whether to rewrite the named-by-nothing rows.
 *
 * The pattern lives here as its own constant because the scanner is `internal` to `templates`.
 * It matches the same spellings as the scanner's
 * `FORBIDDEN_SPECIAL_VARIABLE` (`\.\s*(get_optional_template|getOptionalTemplate)\b`), expressed
 * in Postgres ARE dialect (`\y` is ARE's word boundary; `\b` in ARE means backspace).
 */
class TemplateUndeclaredLoadingCheck(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger(TemplateUndeclaredLoadingCheck::class.java)

    /** Read-only, request-input-free: the only bind value is this class's own constant. */
    @PostConstruct
    fun check() {
        val count =
            jdbc.queryForObject(
                "SELECT count(*) FROM template_versions WHERE body ~ :pattern",
                MapSqlParameterSource("pattern", UNDECLARED_LOADING_CONSTRUCT_SQL_REGEX),
                Int::class.java,
            ) ?: 0
        if (count > 0) {
            log.warn("event=templates.undeclared_loading_construct count={}", count)
        } else {
            log.info("event=templates.undeclared_loading_construct count=0")
        }
    }

    companion object {
        /**
         * The scanner's `FORBIDDEN_SPECIAL_VARIABLE` as a Postgres ARE pattern — see the class
         * KDoc for why it is duplicated rather than imported, and for the `\b` → `\y` spelling.
         */
        const val UNDECLARED_LOADING_CONSTRUCT_SQL_REGEX =
            """\.\s*(get_optional_template|getOptionalTemplate)\y"""
    }
}

/** Wires the check beside the other boot checks (this file is its own registration — no edits to the existing wiring files). */
@Configuration
class TemplateUndeclaredLoadingConfiguration {
    @Bean
    fun templateUndeclaredLoadingCheck(jdbc: NamedParameterJdbcTemplate): TemplateUndeclaredLoadingCheck =
        TemplateUndeclaredLoadingCheck(jdbc)
}
