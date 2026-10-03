package co.datapipelines.browser

import com.microsoft.playwright.options.SelectOption
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * #374 - the workspace's LIVE parameter form in a real browser: the parameter-set evaluate rules
 * the dashboard runtime's `initParameters` entry renders (workspace spec section 6.2, the five
 * non-stream rules) over the real `POST /api/v1/parameter-sets/{id}/evaluate`:
 *
 *  1. the first render evaluates `{}` and shows every default with its origin hint;
 *  2. a parent change re-resolves its dependents and RESETS a dropped selection, visibly;
 *  3. a hidden parameter's row is rendered but not shown, and its `note` is accounted for;
 *  4. a selector that FAILS shows the server's own message on its row, verbatim;
 *  5. rapid changes settle on the LAST choice - a superseded response never repaints.
 *
 * Plus the history-cache rule (#358): leaving through a boosted link and coming back evaluates
 * once, never twice, and draws one form.
 */
class ParameterSetFormBrowserTest : ParameterSetBrowserSuite() {
    private val selectorBody =
        """
        SELECT v AS value, v AS display_value, (v LIKE '%1') AS is_default
          FROM (SELECT CAST(:kind AS TEXT) || '1' AS v UNION ALL SELECT CAST(:kind AS TEXT) || '2') t
         WHERE 1 / (CASE WHEN CAST(:kind AS TEXT) = 'boom' THEN 0 ELSE 1 END) = 1
         ORDER BY v
        """.trimIndent()

    /** The live set: kind (constants) -> item (a template selector that fails for `boom`), a hidden note, a typed input. */
    private fun seedLiveSet(root: String): String {
        val datasource = registerSourceDatasource()
        createTemplate("$root/parameters/item_options.sql", selectorBody)
        val item =
            """{"name":"item","label":"Item","type":"STRING","kind":"SELECT","cardinality":"SINGLE","required":true,""" +
                """"depends_on":["kind"],"source":{"template":{"id":"$root/parameters/item_options.sql","version":1},""" +
                """"datasource":"$datasource"},"presentation":{"control":"dropdown"}}"""
        val note =
            """{"name":"note","label":"Note","type":"STRING","kind":"INPUT","cardinality":"SINGLE","required":false,""" +
                """"depends_on":["kind"],"hidden_expression":{"op":"eq","left":{"ref":"kind"},"right":{"literal":"boom"}}}"""
        val count =
            """{"name":"n","label":"Count","type":"INTEGER","kind":"INPUT","cardinality":"SINGLE","required":false,"default_value":5}"""
        val (id, hash) =
            createSet(setBody("$root/parameters/live", "[${constants("kind", listOf("a", "ok", "boom"))},$item,$note,$count]"))
        release(id, hash, withTemplates = true)
        return id
    }

    private fun open(id: String) {
        page.navigate("$baseUrl/parameter-sets/$id")
        awaitSelected("item", "a1")
    }

    /** Waits until the parameter's select shows [label] (an option is never "visible" to Playwright - the selection is the signal). */
    private fun awaitSelected(
        parameter: String,
        label: String,
    ) {
        page.waitForFunction(
            "([p, l]) => { const s = document.querySelector(\"#ps-form-host [data-dp-parameter='\" + p + \"'] select\"); " +
                "return !!(s && s.selectedOptions[0] && s.selectedOptions[0].text === l); }",
            listOf(parameter, label),
        )
    }

    private fun choose(
        parameter: String,
        label: String,
    ) {
        page.selectOption("#ps-form-host [data-dp-parameter='$parameter'] select", SelectOption().setLabel(label))
    }

    private fun selectedLabel(parameter: String): String =
        page.locator("#ps-form-host [data-dp-parameter='$parameter'] select").evaluate("s => s.selectedOptions[0].text") as String

    /** #383 — the page's evaluate is the OBSERVED route; the capture means exactly that path, never a prefix match. */
    private fun captureEvaluatePosts(into: MutableList<String>) {
        page.onRequest { request ->
            if (request.method() == "POST" && request.url().endsWith("/evaluations")) into += request.postData() ?: ""
        }
    }

    @Test
    fun `rule 1 - the first render evaluates an empty selection and shows every default with its origin`() {
        startTrace()
        val id = seedLiveSet(ready("psf1"))
        val posted = mutableListOf<String>()
        captureEvaluatePosts(posted)

        open(id)

        posted.size shouldBe 1
        posted.single().replace(" ", "") shouldContain """"selections":{}"""
        for (parameter in listOf("kind", "item", "n")) {
            val hint = page.locator("#ps-form-host [data-dp-parameter='$parameter'] .dp-dashboard-parameter-origin")
            hint.getAttribute("data-dp-origin") shouldBe "default"
        }
        selectedLabel("kind") shouldBe "A"
        selectedLabel("item") shouldBe "a1"
    }

    @Test
    fun `rules 2 to 4 - a dropped selection resets visibly, a hidden row hides, a failed selector shows the server's message`() {
        startTrace()
        val id = seedLiveSet(ready("psf2"))
        open(id)
        choose("item", "a2")
        awaitSelected("item", "a2")

        // Rule 2: the parent changes to a value whose options do not contain a2 - the dependent resets.
        choose("kind", "OK")
        awaitSelected("item", "ok1")
        page.locator("#ps-form-host [data-dp-parameter='item'] .dp-dashboard-parameter-reset").isVisible() shouldBe true
        page.locator("#ps-form-host [data-dp-parameter='note']").isVisible() shouldBe true

        // Rules 3 and 4: `boom` hides the note and makes the item's selector fail.
        choose("kind", "BOOM")
        val error = "#ps-form-host [data-dp-parameter='item'] .dp-dashboard-parameter-error"
        page.waitForSelector("$error:not(:empty)")
        page.locator("#ps-form-host [data-dp-parameter='note']").isVisible() shouldBe false
        page.locator("#ps-form-host [data-dp-parameter='note']").count() shouldBe 1
        val shown = page.locator(error).innerText()
        val (status, body) =
            api("POST", "/api/v1/parameter-sets/$id/evaluate", """{"version":1,"selections":{"kind":"boom"}}""")
        status shouldBe 200
        val message = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(body).map { it.groupValues[1] }.toList()
        (message.isNotEmpty() && message.any { shown.contains(it.replace("\\\"", "\"")) }) shouldBe true
    }

    @Test
    fun `rule 5 - rapid changes settle on the last choice and a superseded response never repaints`() {
        startTrace()
        val id = seedLiveSet(ready("psf5"))
        // The `boom` answer arrives LATE (1.2 s) - after the newest selection's answer - so only the supersede rule keeps it off the form.
        page.addInitScript(
            """(() => {
              const real = window.fetch;
              window.fetch = function (input, init) {
                const answer = real.apply(this, arguments);
                const body = init && init.body ? String(init.body) : '';
                if (String(input).indexOf('/evaluations') >= 0 && body.indexOf('"boom"') >= 0) {
                  return answer.then((r) => new Promise((resolve) => setTimeout(() => resolve(r), 1200)));
                }
                return answer;
              };
            })()""",
        )
        open(id)
        // Four changes in ONE task: no await between them, so every earlier attempt is superseded.
        page.evaluate(
            """() => {
              const select = document.querySelector("#ps-form-host [data-dp-parameter='kind'] select");
              for (const label of ['OK', 'BOOM', 'A', 'OK']) {
                const option = [...select.options].find((o) => o.text === label);
                select.value = option.value;
                select.dispatchEvent(new Event('change', { bubbles: true }));
              }
            }""",
        )
        awaitSelected("item", "ok1")
        // Give a late superseded `boom` response every chance to land and repaint.
        page.waitForTimeout(2200.0)
        selectedLabel("kind") shouldBe "OK"
        selectedLabel("item") shouldBe "ok1"
        page.locator("#ps-form-host [data-dp-parameter='item'] .dp-dashboard-parameter-error").count() shouldBe 0
        page.locator("#ps-form-host [data-dp-parameter='note']").isVisible() shouldBe true
    }

    @Test
    fun `a history restore evaluates once and draws one form - no double execution`() {
        startTrace()
        val id = seedLiveSet(ready("psfh"))
        open(id)
        drainCspViolations()

        val posted = mutableListOf<String>()
        page.onRequest { request ->
            if (request.method() == "POST" && request.url().endsWith("/evaluations")) posted += request.url()
        }
        // Leave through a boosted link (htmx snapshots the page), then restore it from the history cache.
        page.click("a.app-nav-link[data-nav-section='/templates']")
        page.waitForURL("**/templates**")
        page.goBack()
        awaitSelected("item", "a1")
        page.waitForTimeout(800.0)

        posted.size shouldBe 1
        page.locator("#ps-form-host [data-dp-parameter='kind']").count() shouldBe 1
        page.locator(".ps-root").count() shouldBe 1
        drainCspViolations().filter { !it.contains("'sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU='") }.shouldBeEmpty()
    }
}
