package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 141 §B/§C — the pipeline editor's settings sidebar is the user's width, in a real browser.
 *
 * The owner, 2026-09-15: *"On the pipeline and pipeline editor pages we have a left pane —
 * the list of pipelines, and the description/parameters respectively. I want that pane to be
 * resizable, just like the bottom pane."* The explorers' tree already resized (104 §A — the
 * owner did not notice, because the grip was transparent at rest); the two editors' side
 * panes were fixed tracks in their grids. This suite is the pipeline-editor half of binding
 * them with the splitter module that already exists, copied on
 * [ExplorerPaneGeometryBrowserTest]'s shape: the drag moves the grid track by exactly the
 * pointer's travel, the remembered width is the width of the FIRST paint (the key is read
 * by a parser-blocking script, so there is no settle to wait for), and the ≤1024px collapse
 * beats memory — a remembered 600px must NOT reopen the sidebar at 390px, which is the
 * cascade-order falsification (the media rule sits after the property-sized column).
 */
class PipelineEditorSidebarResizeBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("side-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("sidews-" + generatedPassword("w").take(8).lowercase())
    }

    /** The [PipelineEditorDockResizeBrowserTest] fixture: four independent CALCULATOR nodes. */
    private fun seedPipeline(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const calc = (id) => ({
                id,
                type: 'CALCULATOR',
                kind: 'fiscal_quarter',
                context_key: 'q_' + id,
                inputs: { date: '${'$'}current_date', fiscal_start: '${'$'}org_fiscal_start_date' },
              });
              const res = await fetch('/api/v1/pipelines', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                body: JSON.stringify({ name, display_name: name, nodes: ['a', 'b', 'c', 'd'].map(calc) }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    private fun seedTemplate(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const res = await fetch('/api/v1/templates', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                body: JSON.stringify({ id: name, type: 'sql', dialect: 'POSTGRES',
                                       display_name: name, description: '141 sidebar fixture', body: 'SELECT 1' }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    private lateinit var editorUrl: String

    private fun openEditorFor(name: String) {
        page.navigate("$baseUrl/pipelines?q=$name")
        page.locator("button.tpl-result, button.tpl-leaf").first().click()
        page.locator("a:has-text('Open in editor')").first().click()
        page.waitForURL("**/pipelines/*/editor")
        page.locator(".pe-card").first().waitFor()
        editorUrl = page.url()
    }

    /**
     * The facts, read in ONE evaluate so they describe the same frame. The track width is the
     * RESOLVED first grid column — the pane the user sees — not the property that produced it.
     */
    private val probe =
        """
        () => {
          const sidebar = document.querySelector('.pe-sidebar');
          const body = document.querySelector('.pe-body');
          const handle = document.querySelector('[data-splitter="editor-sidebar"]');
          if (!sidebar || !body) return null;
          return {
            trackW: parseFloat(getComputedStyle(body).gridTemplateColumns.split(' ')[0]),
            stageW: document.querySelector('.pe-stage').getBoundingClientRect().width,
            role: handle ? handle.getAttribute('role') : null,
            orientation: handle ? handle.getAttribute('aria-orientation') : null,
            label: handle ? handle.getAttribute('aria-label') : null,
            hint: handle ? handle.getAttribute('title') : null,
            ariaHint: handle ? handle.getAttribute('aria-description') : null,
            valueNow: handle ? Number(handle.getAttribute('aria-valuenow')) : null,
            valueMin: handle ? Number(handle.getAttribute('aria-valuemin')) : null,
            valueMax: handle ? Number(handle.getAttribute('aria-valuemax')) : null,
            handleDisplay: handle ? getComputedStyle(handle).display : null,
            grip: handle ? getComputedStyle(handle, '::after').backgroundColor : null,
            prop: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--pe-sidebar-w')),
            stored: window.localStorage.getItem('dp.pane.editor-sidebar'),
          };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun read(): Map<String, Any?> = page.evaluate(probe) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    /** A real pointer drag on the handle: down, move in steps (so pointermove fires), up. */
    private fun dragHandle(byX: Double) {
        val box = page.locator("[data-splitter='editor-sidebar']").boundingBox()
        val x = box.x + box.width / 2
        val y = box.y + 200
        page.mouse().move(x, y)
        page.mouse().down()
        page.mouse().move(x + byX, y, Mouse.MoveOptions().setSteps(12))
        page.mouse().up()
        page.waitForTimeout(150.0)
    }

    private fun withClue(
        clue: String,
        block: () -> Unit,
    ) = try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(clue, e)
    }

    private fun ready(): String {
        loginReadyUser()
        val name = "side/resize/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        openEditorFor(name)
        return name
    }

    @Test
    fun `dragging the handle widens the sidebar by the pointer's travel, and the stage gives it up`() {
        startTrace()
        ready()

        val before = read()
        before["role"] shouldBe "separator"
        before["orientation"] shouldBe "vertical"
        before["label"] shouldBe "Resize settings sidebar"
        before["hint"] shouldBe "Drag to resize · double-click to reset"
        before["ariaHint"] shouldBe "Drag to resize · double-click to reset"
        withClue("the default is not the stylesheet's 280px: $before") {
            before.d("trackW") shouldBeGreaterThanOrEqual DEFAULT_W - 1
            before.d("trackW") shouldBeLessThanOrEqual DEFAULT_W + 1
        }

        dragHandle(200.0)
        val after = read()
        withClue("the sidebar did not grow by the drag: $before -> $after") {
            (after.d("trackW") - before.d("trackW")) shouldBeGreaterThanOrEqual 198.0
            (after.d("trackW") - before.d("trackW")) shouldBeLessThanOrEqual 202.0
        }
        withClue("the stage did not give up what the sidebar took: $before -> $after") {
            (before.d("stageW") - after.d("stageW")) shouldBeGreaterThanOrEqual 198.0
            (before.d("stageW") - after.d("stageW")) shouldBeLessThanOrEqual 202.0
        }
        withClue("aria-valuenow does not describe the pane: $after") {
            (after.d("valueNow") - after.d("trackW")) shouldBeLessThanOrEqual 2.0
            (after.d("trackW") - after.d("valueNow")) shouldBeLessThanOrEqual 2.0
        }
        after["stored"] shouldBe after.d("prop").toInt().toString()
    }

    @Test
    fun `the handle resizes from the keyboard and parks on the bounds`() {
        startTrace()
        ready()
        val handle = page.locator("[data-splitter='editor-sidebar']")
        handle.focus()
        val before = read()

        handle.press("ArrowRight")
        page.waitForTimeout(80.0)
        val stepped = read()
        withClue("ArrowRight did not step 16px: $before -> $stepped") {
            (stepped.d("trackW") - before.d("trackW")) shouldBeGreaterThanOrEqual 15.0
            (stepped.d("trackW") - before.d("trackW")) shouldBeLessThanOrEqual 17.0
        }
        handle.press("Shift+ArrowRight")
        page.waitForTimeout(80.0)
        val big = read()
        withClue("Shift+ArrowRight did not step 64px: $stepped -> $big") {
            (big.d("trackW") - stepped.d("trackW")) shouldBeGreaterThanOrEqual 63.0
            (big.d("trackW") - stepped.d("trackW")) shouldBeLessThanOrEqual 65.0
        }
        big.d("valueMin") shouldBe SIDE_MIN
        big.d("valueMax") shouldBe 1440 * MAX_SHARE

        handle.press("End")
        page.waitForTimeout(80.0)
        withClue("End did not park on the 50vw ceiling: ${read()}") {
            read().d("trackW") shouldBeGreaterThanOrEqual 1440 * MAX_SHARE - 1
            read().d("trackW") shouldBeLessThanOrEqual 1440 * MAX_SHARE + 1
        }
        handle.press("Home")
        page.waitForTimeout(80.0)
        withClue("Home did not park on the 220px floor: ${read()}") {
            read().d("trackW") shouldBeLessThanOrEqual SIDE_MIN + 1
        }
    }

    @Test
    fun `a dragged width survives a reload as the width of the FIRST paint, and a double-click forgets it`() {
        startTrace()
        ready()
        dragHandle(200.0)
        val dragged = read()

        // The key is read by the parser-blocking splitter.js ABOVE the markup, so the pane's
        // first painted frame IS the remembered width — there is no settle to wait for, and a
        // measurement taken the moment the sidebar attaches must already be final.
        page.navigate(editorUrl)
        page.locator(".pe-sidebar").waitFor()
        val firstPaint = read()
        withClue("the first paint is not at the remembered width: $dragged -> $firstPaint") {
            (firstPaint.d("trackW") - dragged.d("trackW")) shouldBeLessThanOrEqual 1.0
            (dragged.d("trackW") - firstPaint.d("trackW")) shouldBeLessThanOrEqual 1.0
        }
        page.waitForLoadState(LoadState.NETWORKIDLE)
        val settled = read()
        withClue("the width moved after the first paint — that is a layout shift: $firstPaint -> $settled") {
            (settled.d("trackW") - firstPaint.d("trackW")) shouldBeLessThanOrEqual 1.0
            (firstPaint.d("trackW") - settled.d("trackW")) shouldBeLessThanOrEqual 1.0
        }

        page.locator("[data-splitter='editor-sidebar']").dblclick()
        page.waitForTimeout(150.0)
        val reset = read()
        withClue("double-click did not restore the stylesheet default: $reset") {
            reset.d("trackW") shouldBeGreaterThanOrEqual DEFAULT_W - 1
            reset.d("trackW") shouldBeLessThanOrEqual DEFAULT_W + 1
        }
        reset["stored"] shouldBe null
    }

    @Test
    fun `a remembered width beyond the ceiling is pulled back in`() {
        startTrace()
        loginReadyUser()
        val name = "side/ceiling/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        // 1200px remembered at a 1440px window: the 50vw ceiling is 720. The stylesheet's
        // clamp() is what pulls it back — the restore writes the property BEFORE any bound
        // in splitter.js could run, so JS alone could never make this true pre-paint.
        page.addInitScript("window.localStorage.setItem('dp.pane.editor-sidebar', '1200');")
        openEditorFor(name)
        val m = read()
        withClue("the remembered 1200px was not clamped to the 720px ceiling: $m") {
            m.d("trackW") shouldBeGreaterThanOrEqual 1440 * MAX_SHARE - 1
            m.d("trackW") shouldBeLessThanOrEqual 1440 * MAX_SHARE + 1
        }
    }

    @Test
    fun `a 390px viewport with a remembered 600px shows the collapsed layout`() {
        startTrace()
        loginReadyUser()
        val name = "side/narrow/" + generatedPassword("p").take(8).lowercase()
        seedPipeline(name) shouldBe 201
        page.setViewportSize(1440, 900)
        // The editor URL comes from the desktop flow (at 390px the explorer's tree is a
        // drawer and its leaf is off-canvas); the narrow window is the SECOND open of the
        // editor, which is the real scenario — a width remembered on the desktop meets a
        // phone. The remembered 600px is seeded before that navigation.
        openEditorFor(name)
        page.addInitScript("window.localStorage.setItem('dp.pane.editor-sidebar', '600');")
        page.setViewportSize(390, 844)
        page.navigate(editorUrl)
        page.locator(".pe-sidebar").waitFor()
        val m = read()
        // THE falsification for the cascade order: the ≤1024px collapse (`0 1fr`) sits AFTER
        // the property-sized column in the stylesheet. If it ever sits before it, a remembered
        // 600px reopens the sidebar at phone widths — this arm is red that same run.
        withClue("a remembered 600px reopened the collapsed sidebar at 390px: $m") {
            m.d("trackW") shouldBeLessThanOrEqual 1.0
        }
        withClue("the handle is visible where there is no boundary to sit on: $m") {
            m["handleDisplay"] shouldBe "none"
        }
    }

    @Test
    fun `every pane handle's grip is visible at rest, light and dark`() {
        startTrace()
        ready()
        seedTemplate("test/side_grip_probe") shouldBe 201

        // All four handles: the explorers' tree (104), this sidebar, the dock (104), and the
        // template editor's rail (141). The assertion is one fact about each — the grip's
        // computed background at REST is not transparent — because a resize nobody can see is
        // a resize nobody uses: the owner had the tree handle for a week without finding it.
        val handles =
            listOf(
                "/templates" to listOf("explorer-tree"),
                editorUrl.removePrefix(baseUrl) to listOf("editor-sidebar", "editor-dock"),
                "/templates/editor?name=test%2Fside_grip_probe" to listOf("template-editor-side"),
            )
        for (theme in listOf("light", "dark")) {
            for ((route, keys) in handles) {
                page.navigate("$baseUrl$route")
                page.waitForLoadState(LoadState.NETWORKIDLE)
                ensureTheme(theme)
                for (key in keys) {
                    val grip =
                        page.evaluate(
                            "(k) => getComputedStyle(document.querySelector('[data-splitter=\"' + k + '\"]'), '::after').backgroundColor",
                            key,
                        )
                    withClue("$key's grip is transparent at rest ($theme) on $route — the 141 §C defect") {
                        listOf("rgba(0, 0, 0, 0)", "transparent").contains(grip.toString()) shouldBe false
                    }
                    gripShot(key, "rest", theme)
                    page.hover("[data-splitter='$key']")
                    page.waitForTimeout(200.0)
                    gripShot(key, "hover", theme)
                }
            }
        }
    }

    /** A shot of the handle with enough of both panes around it to judge the grip by eye. */
    private fun gripShot(
        key: String,
        state: String,
        theme: String,
    ) {
        val box = page.locator("[data-splitter='$key']").boundingBox()
        val vertical = box.height > box.width
        val w = if (vertical) 160.0 else 280.0
        val h = if (vertical) 280.0 else 160.0
        val x = (box.x + box.width / 2 - w / 2).coerceAtLeast(0.0)
        val y = (box.y + box.height / 2 - h / 2).coerceAtLeast(0.0)
        page.screenshot(
            Page
                .ScreenshotOptions()
                .setPath(shotDir().resolve("141-$key-$state-$theme.png"))
                .setClip(x, y, w, h),
        )
    }

    private fun shotDir(): Path = Paths.get("build", "reports", "141-screenshots").also { it.toFile().mkdirs() }

    private companion object {
        /** `pipeline-editor.css`'s `--pe-sidebar-width` default and `--pe-sidebar-min-w` floor. */
        const val DEFAULT_W = 280.0
        const val SIDE_MIN = 220.0

        /** The 50vw ceiling, as a share so the assertion derives from the viewport. */
        const val MAX_SHARE = 0.5
    }
}
