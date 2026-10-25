package co.datapipelines.web.ui

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * #9 slice 2, A.1 — the Schedules page SHELL at the render (ui-screens.md §4.20): the rail item,
 * the explorer's skeletons, the create verb by role, and the page's contract with the record's §6 —
 * no htmx request attribute and no partial route; every schedule operation is the script's call to
 * rest-api §20. The verbs of the detail, the form and the run dialog have their own render tests
 * (`SchedulesDetailRenderTest`, `SchedulesFormRenderTest`, `SchedulesRunRenderTest`), the
 * per-screen ladders `RoleVisibilityRenderTest`'s sweep delegates to.
 *
 * The page's markup is `<template>` skeletons the scripts clone, so "the verb is not rendered" is
 * literal: a reader's page carries no create verb anywhere — not hidden, absent.
 */
class SchedulesPageRenderTest {
    @Test
    fun `an author's page offers New schedule - in the header and in the empty state`() {
        val html = SchedulesRender.page(SchedulesRender.AUTHOR)
        html.split("data-verb=\"schedule-create\"").size - 1 shouldBe 2
        html shouldContain "data-can-author=\"true\""
        SchedulesRender.skeleton(html, "sch-tpl-empty") shouldContain "A schedule runs a pipeline at the times you choose"
    }

    @Test
    fun `a viewer's page has no create verb, and its empty state says who creates schedules`() {
        val html = SchedulesRender.page(SchedulesRender.VIEWER)
        html shouldNotContain "data-verb=\"schedule-create\""
        html shouldContain "data-can-author=\"false\""
        SchedulesRender.skeleton(html, "sch-tpl-empty") shouldContain "Authors and workspace admins create schedules."
        // …and it is not an empty page: the explorer's skeletons are all there.
        listOf("sch-tpl-folder", "sch-tpl-leaf", "sch-tpl-result", "sch-tpl-empty", "sch-tpl-no-match", "sch-tpl-error")
            .forEach { id -> withClue(id) { html shouldContain "id=\"$id\"" } }
    }

    @Test
    fun `a promoter reads through the lens - its empty state says what the lens shows`() {
        val html = SchedulesRender.page(SchedulesRender.PROMOTER)
        html shouldNotContain "data-verb=\"schedule-create\""
        SchedulesRender.skeleton(html, "sch-tpl-empty") shouldContain "only schedules of released pipelines"
        html shouldContain "data-can-read-executions=\"false\""
    }

    @Test
    fun `the condition select sits beside the search - All conditions first, the deep link's word selected and stamped`() {
        val all = SchedulesRender.page()
        val select = all.substringAfter("<select id=\"schedule-filter-condition\"").substringBefore("</select>")
        select shouldContain "name=\"condition\""
        select shouldContain "aria-label=\"Filter by condition\""
        Regex("""<option value="([a-z]*)"""").findAll(select).map { it.groupValues[1] }.toList() shouldBe
            listOf("", "enabled", "paused", "blocked")
        Regex("""<option[^>]*selected[^>]*>([^<]*)<""").findAll(select).map { it.groupValues[1] }.toList() shouldBe
            listOf("All conditions")
        all shouldNotContain "data-schedule-condition"
        // The select is in the search row, after the box.
        val row = all.substringAfter("tplx-search-row").substringBefore("tplx-body")
        (row.indexOf("schedule-filter-q") in 0 until row.indexOf("schedule-filter-condition")) shouldBe true

        val blocked = SchedulesRender.page(condition = "blocked")
        val chosen = blocked.substringAfter("<select id=\"schedule-filter-condition\"").substringBefore("</select>")
        Regex("""<option[^>]*selected[^>]*>([^<]*)<""").findAll(chosen).map { it.groupValues[1] }.toList() shouldBe listOf("Blocked")
        Regex("""data-schedules-root[^>]*data-schedule-condition="blocked"""").containsMatchIn(blocked.replace("\n", " ")) shouldBe true
    }

    @Test
    fun `a filter with no rows has its own empty state - Clear filters resets the select and the search`() {
        val skeleton = SchedulesRender.skeleton(SchedulesRender.page(), "sch-tpl-no-match-filtered")
        skeleton shouldContain "data-empty=\"no-match-filtered\""
        skeleton shouldContain "data-sch-action=\"clear-filters\""
        skeleton shouldContain ">Clear filters<"
    }

    @Test
    fun `the page is a shell over REST - no htmx request attribute and no partial route anywhere`() {
        val main = SchedulesRender.page().substringAfter("<main").substringBefore("</main>")
        // The record's §6: every schedule operation is a §20 call from the page's script.
        listOf("hx-get", "hx-post", "hx-put", "hx-delete", "/partials/schedules").forEach { needle ->
            withClue(needle) { main shouldNotContain needle }
        }
        // The scripts it loads are in dependency order (htmx re-inserts them async=false on a
        // boosted visit, so this order is the execution order), the shell's own always present.
        val order =
            listOf(
                "js/csrf.js",
                "js/schedules/model.js",
                "js/schedules/api.js",
                "js/schedules/dom.js",
                "js/schedules/explorer.js",
                "js/schedules/detail.js",
                "js/schedules/run.js",
                "js/schedules/form.js",
                "js/schedules/page.js",
            )
        val present = order.filter { main.contains(it) }
        present shouldContainAll
            listOf(
                "js/csrf.js",
                "js/schedules/model.js",
                "js/schedules/api.js",
                "js/schedules/dom.js",
                "js/schedules/explorer.js",
                "js/schedules/page.js",
            )
        present.map { main.indexOf(it) }.let { at -> withClue("scripts out of order: $present") { at shouldBe at.sorted() } }
        present.last() shouldBe "js/schedules/page.js"
    }

    @Test
    fun `the rail draws Schedules in Operate right after Executions, for every role`() {
        val viewer = SchedulesRender.page(SchedulesRender.VIEWER)
        val promoter = SchedulesRender.page(SchedulesRender.PROMOTER)
        listOf(viewer, promoter).forEach { html ->
            html shouldContain "data-nav-section=\"/schedules\""
            html shouldContain "lucide-sprite.svg#calendar-clock"
        }
        val rail =
            viewer
                .substringAfter("id=\"app-nav-group-operate\">Operate<")
                .substringBefore("id=\"app-nav-group-organisation\">Organisation<")
        val executions = rail.indexOf("data-nav-section=\"/executions\"")
        val schedules = rail.indexOf("data-nav-section=\"/schedules\"")
        (executions >= 0 && schedules > executions && schedules < rail.indexOf("data-nav-section=\"/api-console\"")) shouldBe true
        // The active item on its own page.
        Regex("""class="app-nav-link active" data-nav-section="/schedules"""")
            .containsMatchIn(viewer.replace("\n", " ")) shouldBe true
    }

    @Test
    fun `no user text reaches the page through a Thymeleaf unescaped write`() {
        val dir = Paths.get(requireNotNull(javaClass.classLoader.getResource("templates/schedules")).toURI())
        val files = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".html") }.toList() }
        (files.size >= 2) shouldBe true
        files
            .flatMap { f -> Regex("""th:utext|\[\(\$\{""").findAll(Files.readString(f)).map { "${f.fileName}: ${it.value}" }.toList() }
            .shouldBeEmpty()
    }
}
