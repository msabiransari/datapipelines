package co.datapipelines.browser

import com.microsoft.playwright.Mouse
import com.microsoft.playwright.options.LoadState
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 141 §B — the template editor's context rail is the user's width, in a real browser.
 *
 * The pipeline editor's twin ([PipelineEditorSidebarResizeBrowserTest]) owns the shared
 * mechanics (the drag travel, the first-paint restore, the grip visibility); this suite owns
 * what is different here: the pane is `.te-body`'s first track (a `--app-detail-width` rail,
 * not a 280px sidebar), the key is its OWN (`dp.pane.template-editor-side` — the width does
 * not follow the user from the pipeline editor, the content is different), and there is NO
 * collapse breakpoint: below 768px the page shows the 110 §C phone band with the editor
 * rendered underneath, and at 390px the clamp's 220px floor wins over the 50vw ceiling. That
 * last arm is asserted as today's contract so a future collapse is a decision, not an accident.
 */
class TemplateEditorSideResizeBrowserTest : BrowserSuite() {
    private fun loginReadyUser() {
        val user =
            seedLocalUser(
                uniqueEmail("teside-" + generatedPassword("u").take(8)),
                generatedPassword("pw"),
                mustChange = false,
            )
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("tesidews-" + generatedPassword("w").take(8).lowercase())
    }

    private fun seedTemplate(name: String): Int =
        page.evaluate(
            """async (name) => {
              const csrf = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
              const res = await fetch('/api/v1/templates', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json', 'DP-CSRF-Token': csrf ? decodeURIComponent(csrf[1]) : '' },
                body: JSON.stringify({ id: name, type: 'sql', dialect: 'POSTGRES',
                                       display_name: name, description: '141 rail fixture', body: 'SELECT 1' }),
              });
              return res.status;
            }""",
            name,
        ) as Int

    private lateinit var editorUrl: String

    private fun ready(): String {
        loginReadyUser()
        val name = "test/side_rail_" + generatedPassword("t").take(8).lowercase()
        seedTemplate(name) shouldBe 201
        page.setViewportSize(1440, 900)
        editorUrl = "$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8")
        page.navigate(editorUrl)
        page.waitForSelector(".te-rail")
        page.waitForLoadState(LoadState.NETWORKIDLE)
        return name
    }

    /** The facts, read in ONE evaluate so they describe the same frame. */
    private val probe =
        """
        () => {
          const rail = document.querySelector('.te-rail');
          const body = document.querySelector('.te-body');
          const handle = document.querySelector('[data-splitter="template-editor-side"]');
          if (!rail || !body) return null;
          return {
            trackW: parseFloat(getComputedStyle(body).gridTemplateColumns.split(' ')[0]),
            sourceW: document.querySelector('.te-source').getBoundingClientRect().width,
            role: handle ? handle.getAttribute('role') : null,
            orientation: handle ? handle.getAttribute('aria-orientation') : null,
            label: handle ? handle.getAttribute('aria-label') : null,
            hint: handle ? handle.getAttribute('title') : null,
            ariaHint: handle ? handle.getAttribute('aria-description') : null,
            valueNow: handle ? Number(handle.getAttribute('aria-valuenow')) : null,
            valueMin: handle ? Number(handle.getAttribute('aria-valuemin')) : null,
            valueMax: handle ? Number(handle.getAttribute('aria-valuemax')) : null,
            handleDisplay: handle ? getComputedStyle(handle).display : null,
            prop: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--te-side-w')),
            stored: window.localStorage.getItem('dp.pane.template-editor-side'),
          };
        }
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun read(): Map<String, Any?> = page.evaluate(probe) as Map<String, Any?>

    private fun Map<String, Any?>.d(k: String) = (this[k] as Number).toDouble()

    /** A real pointer drag on the handle: down, move in steps (so pointermove fires), up. */
    private fun dragHandle(byX: Double) {
        val box = page.locator("[data-splitter='template-editor-side']").boundingBox()
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

    @Test
    fun `dragging the handle widens the rail by the pointer's travel, and the source column gives it up`() {
        startTrace()
        ready()

        val before = read()
        before["role"] shouldBe "separator"
        before["orientation"] shouldBe "vertical"
        before["label"] shouldBe "Resize context rail"
        before["hint"] shouldBe "Drag to resize · double-click to reset"
        before["ariaHint"] shouldBe "Drag to resize · double-click to reset"
        withClue("the default is not the stylesheet's --app-detail-width (320px): $before") {
            before.d("trackW") shouldBeGreaterThanOrEqual DEFAULT_W - 1
            before.d("trackW") shouldBeLessThanOrEqual DEFAULT_W + 1
        }

        dragHandle(200.0)
        val after = read()
        withClue("the rail did not grow by the drag: $before -> $after") {
            (after.d("trackW") - before.d("trackW")) shouldBeGreaterThanOrEqual 198.0
            (after.d("trackW") - before.d("trackW")) shouldBeLessThanOrEqual 202.0
        }
        withClue("the source column did not give up what the rail took: $before -> $after") {
            (before.d("sourceW") - after.d("sourceW")) shouldBeGreaterThanOrEqual 198.0
            (before.d("sourceW") - after.d("sourceW")) shouldBeLessThanOrEqual 202.0
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
        val handle = page.locator("[data-splitter='template-editor-side']")
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

        // The key is read by the parser-blocking splitter.js ABOVE the markup, so the first
        // painted frame IS the remembered width — measured the moment the rail attaches.
        page.navigate(editorUrl)
        page.locator(".te-rail").waitFor()
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

        page.locator("[data-splitter='template-editor-side']").dblclick()
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
        val name = "test/side_ceiling_" + generatedPassword("t").take(8).lowercase()
        seedTemplate(name) shouldBe 201
        page.setViewportSize(1440, 900)
        // 1200px remembered at a 1440px window: the 50vw ceiling is 720, and the stylesheet's
        // clamp() pulls it back — the restore writes the property before any JS bound could run.
        page.addInitScript("window.localStorage.setItem('dp.pane.template-editor-side', '1200');")
        page.navigate("$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        page.waitForSelector(".te-rail")
        val m = read()
        withClue("the remembered 1200px was not clamped to the 720px ceiling: $m") {
            m.d("trackW") shouldBeGreaterThanOrEqual 1440 * MAX_SHARE - 1
            m.d("trackW") shouldBeLessThanOrEqual 1440 * MAX_SHARE + 1
        }
    }

    @Test
    fun `a 390px viewport keeps the rail at the clamp floor — there is no collapse today`() {
        startTrace()
        loginReadyUser()
        val name = "test/side_narrow_" + generatedPassword("t").take(8).lowercase()
        seedTemplate(name) shouldBe 201
        page.setViewportSize(390, 844)
        page.addInitScript("window.localStorage.setItem('dp.pane.template-editor-side', '600');")
        page.navigate("$baseUrl/templates/editor?name=" + java.net.URLEncoder.encode(name, "UTF-8"))
        page.waitForSelector(".te-rail")
        val m = read()
        // TODAY'S contract, asserted so a future collapse is a decision: `.te-body` has no
        // breakpoint (unlike `.pe-body`'s ≤1024px `0 1fr`), so the clamp floor (220px) wins
        // over the 50vw ceiling (195px at 390) and the handle stays bound.
        withClue("at 390px the rail is not at the 220px clamp floor: $m") {
            m.d("trackW") shouldBeGreaterThanOrEqual SIDE_MIN - 1
            m.d("trackW") shouldBeLessThanOrEqual SIDE_MIN + 1
        }
        withClue("the handle was hidden even though .te-body never collapses: $m") {
            (m["handleDisplay"] == "none") shouldBe false
        }
    }

    private companion object {
        /** `template-editor.css`'s default (the `--app-detail-width` token) and its clamp floor. */
        const val DEFAULT_W = 320.0
        const val SIDE_MIN = 220.0

        /** The 50vw ceiling, as a share so the assertion derives from the viewport. */
        const val MAX_SHARE = 0.5
    }
}
