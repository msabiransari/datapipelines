package co.datapipelines.browser

import co.datapipelines.browser.InteractionCharts.bar
import com.google.gson.JsonObject
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * #1010 — a chart with a legend switch is a group for assistive technology, and its switches answer a press (owner
 * ruling 2026-11-16, option 2 of #1010; brief 1010 D1 to D3). Read from Chromium's own accessibility tree through a
 * DevTools session (`Accessibility.getFullAXTree`), on charts the harness mounts through the adapter's `create`
 * ([ChartInteractionSuite]):
 *  - B1: a 2-series bar is a `group` named by its title and described by its `desc`; inside it exactly one button per
 *    series, pressed, and no text outside the buttons;
 *  - B2: a click event WITHOUT coordinates dispatched on an entry's element switches its series (pressed false, the
 *    other still true, the picture still a group with D2's hiding after the redraw); once more on the entry's own text;
 *  - B3: a single-series bar draws no legend and stays an `image` with its name and description.
 * This is Chromium's tree, not a screen reader: no screen reader was run.
 */
class ChartAccessibilityBrowserTest : ChartInteractionSuite() {
    private val bar2 = bar(linkedMapOf("A" to listOf(30, 50, 20), "B" to listOf(60, 90, 40)))
    private val bar1 = bar(linkedMapOf("A" to listOf(30, 50, 20)), title = "Single")

    @Test
    fun `B1 - a 2-series bar is a group named and described as before, one pressed button per series, no other text`() {
        startTrace()
        val (context, page) = harnessPage("a11y-b1")
        val log = mutableListOf<String>()
        try {
            mount(page, bar2, FULL_W, FULL_H)["ok"] shouldBe true
            val tree = axTree(page)
            log += "B1: $tree"
            withClue("B1: the picture is a group named by the chart's title, described by its desc: $tree") {
                tree.role shouldBe "group"
                tree.name shouldBe bar2.name
                tree.description shouldStartWith "Bar chart; series: A, B;"
                tree.description shouldBe read(page)["desc"]
            }
            withClue("B1: exactly one button per series, each named by it and pressed: $tree") {
                tree.buttons shouldContainExactly listOf(Button("A", "true"), Button("B", "true"))
            }
            withClue("B1: no static text outside the buttons: $tree") { tree.loose.shouldBeEmpty() }
            withClue("B1: D2 holds in the DOM: ${d2(page)}") { d2(page)["wrong"] shouldBe 0 }
        } finally {
            context.close()
            report("a11y-b1.txt", log)
        }
    }

    @Test
    fun `B2 - a press without coordinates on an entry switches it, the picture stays a group; a press on its text too`() {
        startTrace()
        val (context, page) = harnessPage("a11y-b2")
        val log = mutableListOf<String>()
        try {
            mount(page, bar2, FULL_W, FULL_H)["ok"] shouldBe true
            withClue("B2: before the press") { axTree(page).buttons shouldContainExactly listOf(Button("A", "true"), Button("B", "true")) }
            pressEntry(page, "A", text = false)
            val off = axTree(page)
            log += "B2 press on A's entry: $off dom=${d2(page)}"
            withClue("B2: a press on A's entry switched A off, B untouched: $off") {
                off.buttons shouldContainExactly listOf(Button("A", "false"), Button("B", "true"))
                read(page)["hidden"] shouldBe listOf("A")
            }
            withClue("B2: after the redraw the picture is still a group with D2's hiding: $off ${d2(page)}") {
                off.role shouldBe "group"
                off.name shouldBe bar2.name
                off.loose.shouldBeEmpty()
                d2(page)["wrong"] shouldBe 0
            }
            pressEntry(page, "A", text = true)
            val on = axTree(page)
            log += "B2 press on A's text: $on dom=${d2(page)}"
            withClue("B2: a press on the text inside A's entry switched A back: $on") {
                on.buttons shouldContainExactly listOf(Button("A", "true"), Button("B", "true"))
                read(page)["hidden"] shouldBe emptyList<String>()
                on.role shouldBe "group"
                d2(page)["wrong"] shouldBe 0
            }
        } finally {
            context.close()
            report("a11y-b2.txt", log)
        }
    }

    @Test
    fun `B3 - a single-series bar draws no switch and stays an image with its name and description`() {
        startTrace()
        val (context, page) = harnessPage("a11y-b3")
        val log = mutableListOf<String>()
        try {
            mount(page, bar1, FULL_W, FULL_H)["ok"] shouldBe true
            val tree = axTree(page)
            log += "B3: $tree dom=${d2(page)}"
            withClue("B3: no legend entry, so an image named and described as before: $tree") {
                entries(read(page)).shouldBeEmpty()
                tree.role shouldBe "image"
                tree.name shouldBe bar1.name
                tree.description shouldStartWith "Bar chart; series: A;"
                tree.buttons.shouldBeEmpty()
                d2(page)["hidden"] shouldBe 0
            }
        } finally {
            context.close()
            report("a11y-b3.txt", log)
        }
    }

    /** A click event with no coordinates dispatched on series [title]'s legend entry (or, [text], on its text), then a frame. */
    private fun pressEntry(
        page: Page,
        title: String,
        text: Boolean,
    ) {
        val pressed =
            page.evaluate(
                "a => { var svg = window.__h.svg();" +
                    " var g = Array.from(svg.querySelectorAll('[data-legend]')).filter(function (e) { return e.getAttribute('aria-label') === a.title; })[0];" +
                    " if (!g) return false; var t = a.text ? g.querySelector('text') : g; if (!t) return false;" +
                    " t.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true })); return true; }",
                mapOf("title" to title, "text" to text),
            )
        withClue("the entry ${if (text) "text " else ""}of $title exists") { pressed shouldBe true }
        page.evaluate(FRAME)
    }

    /** D2 in the DOM: the picture's role, how many top-level children carry aria-hidden, and how many break D2's rule. */
    private fun d2(page: Page): Map<*, *> =
        page.evaluate(
            "() => { var svg = window.__h.svg(); var kids = Array.from(svg.children); var group = svg.getAttribute('role') === 'group';" +
                " var shown = function (c) { var t = c.localName; return t === 'title' || t === 'desc' || c.hasAttribute('data-legend'); };" +
                " var wrong = kids.filter(function (c) { return (c.getAttribute('aria-hidden') === 'true') !== (group && !shown(c)); }).length" +
                " + svg.querySelectorAll('[aria-hidden] [aria-hidden]').length;" +
                " return { role: svg.getAttribute('role'), children: kids.length, hidden: svg.querySelectorAll('[aria-hidden]').length, wrong: wrong }; }",
        ) as Map<*, *>

    /** One legend button as Chromium's tree shows it: its name and its pressed state. */
    data class Button(
        val name: String?,
        val pressed: String?,
    )

    /** The mounted picture's node in Chromium's tree, its buttons in order, and every text NOT inside a button. */
    data class AxPicture(
        val role: String?,
        val name: String?,
        val description: String?,
        val buttons: List<Button>,
        val loose: List<String?>,
    )

    /** The harness chart's node read from Chromium's accessibility tree (a DevTools session, not the page's own DOM). */
    private fun axTree(page: Page): AxPicture {
        val cdp = page.context().newCDPSession(page)
        try {
            cdp.send("DOM.enable")
            cdp.send("Accessibility.enable")
            val root = cdp.send("DOM.getDocument", JsonObject().apply { addProperty("depth", 0) })
            val rootId = root.getAsJsonObject("root").get("nodeId").asInt
            val found =
                cdp.send(
                    "DOM.querySelector",
                    JsonObject().apply {
                        addProperty("nodeId", rootId)
                        addProperty("selector", "div.dp-h990-host svg.dp-chart")
                    },
                )
            val node = cdp.send("DOM.describeNode", JsonObject().apply { addProperty("nodeId", found.get("nodeId").asInt) })
            val backend = node.getAsJsonObject("node").get("backendNodeId").asInt
            val nodes = cdp.send("Accessibility.getFullAXTree").getAsJsonArray("nodes").map { it.asJsonObject }
            val byId = nodes.associateBy { it.get("nodeId").asString }
            val svg = nodes.single { it.has("backendDOMNodeId") && it.get("backendDOMNodeId").asInt == backend }
            val buttons = mutableListOf<Button>()
            val loose = mutableListOf<String?>()

            fun walk(
                id: String,
                inButton: Boolean,
            ) {
                val n = byId[id] ?: return
                val ignored = n.has("ignored") && n.get("ignored").asBoolean
                val role = value(n, "role")
                if (!ignored && role == "button") {
                    val pressed = n.getAsJsonArray("properties")?.map { it.asJsonObject }?.firstOrNull { it.get("name").asString == "pressed" }
                    buttons += Button(value(n, "name"), pressed?.getAsJsonObject("value")?.get("value")?.asString)
                }
                if (!ignored && role == "StaticText" && !inButton) loose += value(n, "name")
                n.getAsJsonArray("childIds")?.forEach { walk(it.asString, inButton || (!ignored && role == "button")) }
            }
            svg.getAsJsonArray("childIds")?.forEach { walk(it.asString, false) }
            return AxPicture(value(svg, "role"), value(svg, "name"), value(svg, "description"), buttons.sortedBy { it.name }, loose)
        } finally {
            cdp.detach()
        }
    }

    private fun value(
        n: JsonObject,
        key: String,
    ): String? = n.getAsJsonObject(key)?.get("value")?.asString

    private companion object {
        const val FULL_W = 640
        const val FULL_H = 400
    }
}
