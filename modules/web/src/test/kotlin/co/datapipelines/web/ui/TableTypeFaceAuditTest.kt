package co.datapipelines.web.ui

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * 082 §D — which FACE a table cell sets in, swept rather than eyeballed.
 *
 * `ui-screens.md` §3.3 decides it once: **dates and timestamps are ALWAYS proportional,
 * never inside a `.num`/mono cell**, and **mono is for identifiers only** — machine
 * names, ids, template refs, SQL, keys and prefixes, `context_key → value`. §3.3 then
 * spells the decision out per table. Prose said it for three rounds while the node-stats
 * table rendered its node id in the body face and its Context pair right-aligned under
 * `.num`, and the datasources table set a JDBC URL and a username in Inter.
 *
 * Two halves, because they fail differently:
 *
 * 1. The **date rule** is universal, so it is swept mechanically over every app template.
 *    A new screen that mono-ifies a timestamp fails here without anyone adding a line.
 * 2. The **identifier rule** cannot be derived from the markup — only a human knows that
 *    `ds.jdbcUrl` is a machine string and `ds.name` is a display name. So §3.3's decision
 *    table is transcribed as data, and each entry is checked against the template.
 *
 * Scope mirrors `TypeScaleAuditTest`: the app's templates, with layouts/, the marketing
 * site and the two public docs views excluded (they own their own type rules).
 */
class TableTypeFaceAuditTest {
    private val resolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    private val templates: Map<String, String> =
        resolver
            .getResources("classpath*:templates/**/*.html")
            .toList()
            .filter { it.filename != null }
            .filterNot { resource ->
                val path = resource.uri.path
                path.contains("/templates/layouts/") ||
                    path.contains("/templates/site/") ||
                    resource.filename == "index-public.html" ||
                    resource.filename == "doc-public.html"
            }.associate { it.uri.path.substringAfter("/templates/") to it.inputStream.readBytes().decodeToString() }

    @Test
    fun `no rendered date or timestamp sets in a mono face`() {
        val dateTags =
            templates.flatMap { (name, source) ->
                tagsRendering(source, "#temporals.format").map { name to it }
            }

        // Non-vacuity: the sweep must actually find the app's date cells. Zero matches
        // would pass this test while auditing nothing at all.
        dateTags.size shouldBeGreaterThanOrEqual 10

        val violations =
            dateTags
                .filter { (_, tag) -> classesOf(tag).any { it in MONO_CLASSES } }
                .map { (name, tag) -> "$name renders a timestamp in a mono cell: $tag" }
        violations shouldBe emptyList()
    }

    @Test
    fun `every identifier column ui-screens 3_3 names sets in mono`() {
        val violations =
            IDENTIFIER_CELLS.mapNotNull { (template, expression, why) ->
                val source = templates[template] ?: return@mapNotNull "$template is not on the classpath"
                val tags = tagsRendering(source, expression)
                when {
                    tags.isEmpty() -> {
                        "$template no longer renders $expression — the audit entry is stale"
                    }

                    tags.none { tag -> classesOf(tag).any { it in MONO_CLASSES } } -> {
                        "$template renders $expression in the body face; §3.3 says mono ($why): ${tags.first()}"
                    }

                    else -> {
                        null
                    }
                }
            }
        violations shouldBe emptyList()
    }

    @Test
    fun `the Context column is an identifier column, not a numeric one`() {
        // It carried `.num` — which is right-aligned, one size down and mono. Mono was the
        // only correct third of that; `key → value` is neither a number nor right-aligned.
        val source = templates.getValue("partials/execution-node-stats.html")
        source.contains("""<th class="num">Context</th>""") shouldBe false
        source.contains("<th>Context</th>") shouldBe true
    }

    /**
     * Every tag whose own attributes render `expression` — the element that carries the
     * class the browser applies to that text. Walks back to the tag's `<` and forward to
     * its `>`, which is enough for Thymeleaf's one-attribute-per-value markup.
     */
    private fun tagsRendering(
        source: String,
        expression: String,
    ): List<String> =
        generateSequence(source.indexOf(expression)) { from ->
            source.indexOf(expression, from + 1).takeIf { it >= 0 }
        }.filter { it >= 0 }
            .mapNotNull { at ->
                val open = source.lastIndexOf('<', at)
                val close = source.indexOf('>', at)
                if (open < 0 || close < 0) null else source.substring(open, close + 1)
            }.toList()

    private fun classesOf(tag: String): List<String> =
        CLASS_ATTR
            .find(tag)
            ?.groupValues
            ?.get(1)
            ?.split(' ')
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private companion object {
        val CLASS_ATTR = Regex("""\bclass="([^"]*)"""")

        /** Every app class whose declaration sets `font-family: var(--font-mono)`. */
        val MONO_CLASSES =
            setOf(
                "u-mono",
                "ds-mono",
                "app-path",
                "app-code",
                "app-chip-mono",
                "app-method",
                "app-errblock",
                // `.app-main .ds-table td.num` is mono AND right-aligned — the numeric
                // column treatment, which a date must never take (§3.3.2).
                "num",
            )

        /** ui-screens.md §3.3's "Mono columns", transcribed: template, expression, why. */
        val IDENTIFIER_CELLS =
            listOf(
                Triple("partials/execution-node-stats.html", "n.get('node_id')", "§4.9 node id"),
                Triple("partials/execution-node-stats.html", "n.has('context_key')", "§4.9 context key → value"),
                Triple("partials/datasources.html", "ds.jdbcUrl", "§4.5 JDBC URL"),
                Triple("partials/datasources.html", "ds.username", "§4.5 username"),
                Triple("workspaces/index.html", "membership.workspaceName}\">team", "§4.13 workspace name"),
                Triple("promotion/index.html", "candidate.name}\">daily_revenue", "§4.17 pipeline path"),
                Triple("partials/executions.html", "p.name}\">nyc/mobility/x", "§4.8 pipeline machine path"),
                Triple("partials/recent-executions.html", "p.name}\">nyc/mobility/x", "dashboard machine path"),
                Triple("api/console.html", "e.url}\">/api/x/nyc", "§4.18 endpoint path"),
                Triple("api/console.html", "k.prefix", "§4.18 key prefix"),
            )
    }
}
