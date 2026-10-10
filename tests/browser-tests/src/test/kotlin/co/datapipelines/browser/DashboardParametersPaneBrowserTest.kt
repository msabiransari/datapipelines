package co.datapipelines.browser

import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #498 and #499 - the parameters pane's error row and option rows in a real browser, measured.
 *
 * #498: a failing parameter's row speaks the design system's field error - the danger ink on the
 * sentence, the catalogued code after it as a muted mono chip, the row's design-system control
 * wearing the vendored error border while in error - in both themes, on the board's filters panel
 * and on the parameter-set page's live form (one adapter renders both, dashboards.css loads on
 * every product page).
 *
 * #499: a checkbox or radio group is a two-column grid - every option's box and its label share
 * one centred row, the label right of its box, all boxes on one left edge, and the fieldset's
 * height is the caption plus one line per option (the bound the old stacked layout breaks).
 *
 * The colours are measured against the CSS variables RESOLVED IN PAGE: a hidden probe element
 * assigns the var() to a property and the engine resolves both sides, so the assertions fail when
 * a token is renamed or the rule stops applying - not when a literal drifts. On the unstyled code
 * the error ink resolves to the body text colour and the border to the control's default, and the
 * groups report the flex layout - red on exactly what this lane changes.
 */
class DashboardParametersPaneBrowserTest : ParameterSetBrowserSuite() {
    private val limitRow = "#dp-board-filters [data-dp-parameter=\"limit\"]"
    private val panel = "#dp-board-filters"

    /** Reads the error row's computed voice and the tokens resolved beside it. Passes the row selector. */
    private val probeErrorVoice =
        """async ([rowSel]) => {
          const row = document.querySelector(rowSel);
          const err = row.querySelector('.dp-dashboard-parameter-error');
          const input = row.querySelector('.ds-input');
          const code = err.querySelector('code');
          const probe = document.createElement('span');
          probe.style.display = 'none';
          document.body.appendChild(probe);
          const inkOf = (v) => { probe.style.color = v; return getComputedStyle(probe).color; };
          probe.style.fontSize = 'var(--text-xs)';
          const textXs = getComputedStyle(probe).fontSize;
          probe.style.borderColor = 'var(--border-danger)';
          const borderDanger = getComputedStyle(probe).borderTopColor;
          probe.remove();
          return {
            errColor: getComputedStyle(err).color,
            codeColor: code ? getComputedStyle(code).color : null,
            inputBorder: input ? getComputedStyle(input).borderTopColor : null,
            fontSize: getComputedStyle(err).fontSize,
            accentDanger: inkOf('var(--accent-danger)'),
            textMuted: inkOf('var(--text-muted)'),
            textPrimary: inkOf('var(--text-primary)'),
            textXs: textXs,
            borderDanger: borderDanger,
            role: err.getAttribute('role'),
            dataCode: err.getAttribute('data-dp-code'),
            hasCodeChip: !!code,
            firstChildIsSentence: !!(err.firstChild && err.firstChild.nodeType === 3 && err.firstChild.textContent.trim().length > 0),
            text: err.textContent,
          };
        }"""

    /** Measures both group fieldsets' geometry: pair centring, the boxes' shared left edge, the height bound. */
    private val probeOptionRows =
        """async ([groups]) => {
          const result = {};
          for (const name of groups) {
            const fs = document.querySelector('#dp-board-filters [data-dp-parameter="' + name + '"]');
            const kids = Array.from(fs.children);
            const boxes = kids.filter((el) => el.tagName === 'INPUT');
            const labels = kids.filter((el) => el.tagName === 'LABEL');
            let maxDelta = 0;
            const lefts = new Set();
            let labelRightOfBox = true;
            for (let i = 0; i < boxes.length; i++) {
              const b = boxes[i].getBoundingClientRect();
              const l = labels[i].getBoundingClientRect();
              const delta = Math.abs((b.top + b.height / 2) - (l.top + l.height / 2));
              if (delta > maxDelta) maxDelta = delta;
              if (l.left <= b.right) labelRightOfBox = false;
              lefts.add(Math.round(b.left * 10) / 10);
            }
            const legend = fs.querySelector('legend').getBoundingClientRect();
            const line = labels[0].getBoundingClientRect().height;
            const rowGap = parseFloat(getComputedStyle(fs).rowGap);
            result[name] = {
              display: getComputedStyle(fs).display,
              rows: boxes.length,
              maxDelta: maxDelta,
              distinctLefts: lefts.size,
              labelRightOfBox: labelRightOfBox,
              fsH: fs.getBoundingClientRect().height,
              minH: legend.height + boxes.length * line + boxes.length * rowGap,
            };
          }
          return result;
        }"""

    /** Playwright hands maps back untyped; the unchecked casts live in these two reads only. */
    @Suppress("UNCHECKED_CAST")
    private fun evalVoice(selector: String): Map<String, Any?> =
        page.evaluate(probeErrorVoice, listOf(selector)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun evalGroups(names: List<String>): Map<String, Map<String, Any?>> =
        page.evaluate(probeOptionRows, names) as Map<String, Map<String, Any?>>

    /** The voice assertions the board and the parameter-set page share, per theme. */
    private fun assertErrorVoice(measured: Map<String, Any?>) {        (measured["role"] as String) shouldBe "alert"
        (measured["errColor"] as String) shouldBe (measured["accentDanger"] as String)
        (measured["errColor"] as String) shouldNotBe (measured["textPrimary"] as String)
        (measured["fontSize"] as String) shouldBe (measured["textXs"] as String)
        (measured["codeColor"] as String) shouldBe (measured["textMuted"] as String)
        (measured["inputBorder"] as String) shouldBe (measured["borderDanger"] as String)
        (measured["hasCodeChip"] as Boolean) shouldBe true
        (measured["firstChildIsSentence"] as Boolean) shouldBe true
        val text = measured["text"] as String
        val code = measured["dataCode"] as String
        code.isNotEmpty() shouldBe true
        text shouldContain code
    }

    private fun openBoard(board: String) {
        page.navigate("$baseUrl/dashboards/$board")
        page.waitForFunction("() => window.__dpPage && (window.__dpPage.ready || window.__dpPage.code)")
        val code = page.evaluate("() => window.__dpPage.code") as String?
        if (code != null) {
            val error = page.evaluate("() => String(window.__dpPage.error)") as String
            throw AssertionError("the board page failed to boot: $code — $error")
        }
    }

    @Test
    fun `the board's error row speaks the design system - tokens measured, light and dark`() {
        startTrace()
        val root = ready("dpp1")
        val board = seedAcceptanceBoard(root)
        for (mode in listOf("light", "dark")) {
            openBoard(board)
            ensureTheme(mode)
            openBoard(board)
            page.fill("$limitRow input", "500")
            page.keyboard().press("Tab")
            page.waitForSelector("$limitRow .dp-dashboard-parameter-error[role=alert]")
            assertErrorVoice(evalVoice(limitRow))
        }
    }

    @Test
    fun `every checkbox and radio option shares one row - box and label centred, the boxes on one left edge`() {
        startTrace()
        val root = ready("dpp2")
        val board = seedAcceptanceBoard(root)
        openBoard(board)
        val groups = evalGroups(listOf("markets", "granularity"))
        for ((name, m) in groups) {
            (m["display"] as String) shouldBe "grid"
            ((m["rows"] as Number).toInt() > 0) shouldBe true
            ((m["maxDelta"] as Number).toDouble() < 2.0) shouldBe true
            (m["distinctLefts"] as Number).toInt() shouldBe 1
            (m["labelRightOfBox"] as Boolean) shouldBe true
            ((m["fsH"] as Number).toDouble() <= (m["minH"] as Number).toDouble() + 2.0) shouldBe true
            println("498-rows $name rows=" + m["rows"] + " maxDelta=" + m["maxDelta"] + " fsH=" + m["fsH"] + " minH=" + m["minH"])
        }
    }

    @Test
    fun `the parameter-set page's boom error speaks the same voice - light and dark`() {
        startTrace()
        val id = seedBoomSet(ready("dpp3"))
        val itemRow = "#ps-form-host [data-dp-parameter='item']"
        for (mode in listOf("light", "dark")) {
            page.navigate("$baseUrl/parameter-sets/$id")
            page.waitForSelector("$itemRow select")
            ensureTheme(mode)
            page.navigate("$baseUrl/parameter-sets/$id")
            page.waitForSelector("$itemRow select")
            page.selectOption("#ps-form-host [data-dp-parameter='kind'] select", SelectOption().setLabel("BOOM"))
            page.waitForSelector("$itemRow .dp-dashboard-parameter-error:not(:empty)")
            assertErrorVoice(evalVoice(itemRow))
        }
    }

    /**
     * The boom set mirrors ParameterSetFormBrowserTest's live fixture down to the selector SQL: kind
     * (constants) parents item (a template selector that divides by zero for `boom`), so the failing
     * selector's server message lands on item's row.
     */
    private fun seedBoomSet(root: String): String {
        val datasource = registerSourceDatasource()
        createTemplate(
            "$root/parameters/item_options.sql",
            """
            SELECT v AS value, v AS display_value, (v LIKE '%1') AS is_default
              FROM (SELECT CAST(:kind AS TEXT) || '1' AS v UNION ALL SELECT CAST(:kind AS TEXT) || '2') t
             WHERE 1 / (CASE WHEN CAST(:kind AS TEXT) = 'boom' THEN 0 ELSE 1 END) = 1
             ORDER BY v
            """.trimIndent(),
        )
        val item =
            """{"name":"item","label":"Item","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["kind"],"source":{"template":{"id":"$root/parameters/item_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val (setId, hash) =
            createSet(setBody("$root/parameters/boom", "[${constants("kind", listOf("a", "ok", "boom"))},$item]"))
        release(setId, hash, withTemplates = true)
        return setId
    }
}
