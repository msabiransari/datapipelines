package co.datapipelines.browser

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.URI

/** #619 — measured against the approved companion login design, record §13. */
class LoginPageBrowserTest : BrowserSuite() {
    @Test
    fun `desktop has the record's two columns and measured type and controls`() {
        page.setViewportSize(1512, 982)
        openLogin()
        page.evaluate("() => document.fonts.ready")
        for (theme in listOf("dark", "light")) {
            page.evaluate(SWAP_THEME, theme)
            @Suppress("UNCHECKED_CAST")
            val readings = page.evaluate(DESKTOP) as Map<String, Any?>
            println("619|desktop|$readings")
            readings["columns"] shouldBe true
            (readings["form"] as Number).toDouble() shouldBe (360.0 plusOrMinus 1.0)
            readings["h1"] shouldBe "30px"
            readings["welcome"] shouldBe "13px"
            readings["labels"] shouldBe listOf("13px", "13px")
            @Suppress("UNCHECKED_CAST")
            val controls = readings["controls"] as List<Number>
            (controls.size >= 3 && controls.all { it.toDouble() >= 44.0 }) shouldBe true
            readings["brand"] shouldBe "16px"
            ((readings["height"] as Number).toDouble() <= 983.0) shouldBe true
        }
    }

    @Test
    fun `phone prioritizes the form with large text entry`() {
        page.setViewportSize(390, 844)
        openLogin()
        for (theme in listOf("dark", "light")) {
            page.evaluate(SWAP_THEME, theme)
            val readings = page.evaluate(PHONE)
            println("619|phone|$readings")
            readings shouldBe true
        }
    }

    @Test
    fun `both themes paint the page and story from their surface tokens`() {
        page.setViewportSize(1512, 982)
        openLogin()
        for (theme in listOf("dark", "light")) {
            page.evaluate(SWAP_THEME, theme)
            val readings = page.evaluate(SURFACES)
            println("619|theme=$theme|$readings")
            readings shouldBe true
        }
    }

    @Test
    fun `no sideways scroll from small phone to wide desktop`() {
        for (width in listOf(320, 390, 768, 1512, 1920)) {
            page.setViewportSize(width, 982)
            openLogin()
            val readings = page.evaluate("() => [document.documentElement.scrollWidth, document.documentElement.clientWidth]")
            println("619|width=$width|$readings")
            page.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth") shouldBe true
        }
    }

    @Test
    fun `bundled Noto Sans loads without remote requests or console errors`() {
        val requests = mutableListOf<String>()
        val errors = mutableListOf<String>()
        page.onRequest { requests.add(it.url()) }
        page.onPageError { errors.add(it) }
        page.onConsoleMessage { if (it.type() == "error") errors.add(it.text()) }
        openLogin()
        page.evaluate("() => document.fonts.ready")
        val family = page.locator("h1").evaluate("el => getComputedStyle(el).fontFamily") as String
        val loaded = page.evaluate("() => document.fonts.check('13px \"Noto Sans\"')")
        println("619|fonts|family=$family loaded=$loaded requests=${requests.size} errors=$errors")
        family.startsWith("\"Noto Sans\"") shouldBe true
        loaded shouldBe true
        requests.isNotEmpty() shouldBe true
        requests.all { URI(it).host == "localhost" } shouldBe true
        errors shouldBe emptyList()
    }

    @Test
    fun `show and hide password is reversible and accessible`() {
        openLogin()
        val toggle = page.locator("#login-password-toggle")
        toggle.click()
        page.locator("#login-password").getAttribute("type") shouldBe "text"
        toggle.getAttribute("aria-pressed") shouldBe "true"
        toggle.innerText().trim() shouldBe "Hide"
        toggle.click()
        page.locator("#login-password").getAttribute("type") shouldBe "password"
        toggle.getAttribute("aria-pressed") shouldBe "false"
        toggle.innerText().trim() shouldBe "Show"
        println("619|toggle|password -> text -> password; Show -> Hide -> Show")
    }

    @Test
    fun `inline validation blocks empty input and valid input reaches the server`() {
        openLogin()
        page.locator("form button[type=submit]").click()
        page.locator("#login-email-error").isVisible shouldBe true
        page.url() shouldBe "$baseUrl/login"
        page.fill("#login-email", uniqueEmail("companion-wrong"))
        page.fill("#login-password", "definitely-wrong")
        page.locator("#login-password").press("Enter")
        page.waitForURL("**/login?error=credentials")
        page.content() shouldContain "Invalid email or password"
        println("619|validation|empty blocked; native Enter POST returned credentials banner")
    }

    @Test
    fun `without enhancement the native form remains usable`() {
        page.route("**/js/login.js") {
            it.fulfill(
                com.microsoft.playwright.Route
                    .FulfillOptions()
                    .setBody("")
                    .setContentType("application/javascript"),
            )
        }
        openLogin()
        page.locator("#login-password-toggle").isVisible shouldBe false
        page.locator("#login-password").getAttribute("type") shouldBe "password"
        page.locator("#login-email").getAttribute("autocomplete") shouldBe "username"
        page.locator("form").evaluate("el => el.noValidate") shouldBe false
        page.locator("form button[type=submit]").click()
        page.url() shouldBe "$baseUrl/login"
        println("619|no-script|password hidden; native required validation; POST form retained")
    }

    private fun openLogin() {
        page.onConsoleMessage { if (it.text().startsWith("619|")) println(it.text()) }
        page.navigate("$baseUrl/login")
    }

    private companion object {
        val DESKTOP =
            """
            () => {
              const story = document.querySelector('.app-login-story');
              const form = document.querySelector('.app-login-form');
              const h1 = document.querySelector('h1');
              const controls = [...document.querySelectorAll('.app-login-form .ds-button, .app-login-input-wrap')];
              const sizes = { form: form?.getBoundingClientRect().width, h1: getComputedStyle(h1).fontSize,
                welcome: form && getComputedStyle(form.querySelector('.app-login-welcome')).fontSize,
                labels: [...document.querySelectorAll('label')].map(el => getComputedStyle(el).fontSize),
                controls: controls.map(el => el.getBoundingClientRect().height),
                brand: getComputedStyle(document.querySelector('.app-auth-brand')).fontSize,
                height: document.documentElement.scrollHeight, viewport: innerHeight };
              console.log('619|desktop-readings|' + JSON.stringify(sizes));
              sizes.columns = !!story && !!form && getComputedStyle(story).display !== 'none' &&
                getComputedStyle(form).display !== 'none' && story.getBoundingClientRect().right <= form.getBoundingClientRect().left;
              return sizes;
            }
            """.trimIndent()

        val PHONE =
            """
            () => {
              const story = document.querySelector('.app-login-story');
              const form = document.querySelector('.app-login-form');
              const docs = document.querySelector('.app-auth-docs');
              const sizes = { width: form?.getBoundingClientRect().width,
                input: getComputedStyle(document.getElementById('login-email')).fontSize,
                story: story && getComputedStyle(story).display, docs: docs && getComputedStyle(docs).display,
                scroll: document.documentElement.scrollWidth, viewport: document.documentElement.clientWidth };
              console.log('619|phone-readings|' + JSON.stringify(sizes));
              return sizes.story === 'none' && Math.abs(sizes.width - 342) <= 1 && sizes.input === '16px' &&
                sizes.docs === 'none' && sizes.scroll <= sizes.viewport;
            }
            """.trimIndent()

        val SWAP_THEME =
            """
            (theme) => new Promise((resolve, reject) => {
              const old = document.getElementById('theme-link');
              const link = document.createElement('link');
              link.rel = 'stylesheet';
              link.onload = () => { old.remove(); link.id = 'theme-link'; resolve(true); };
              link.onerror = () => reject(new Error('theme ' + theme + ' did not load'));
              link.href = '/vendor/design-system/themes/' + theme + '.css';
              old.insertAdjacentElement('afterend', link);
            })
            """.trimIndent()

        val SURFACES =
            """
            () => {
              const root = getComputedStyle(document.documentElement);
              const probe = document.createElement('div'); document.body.append(probe);
              const colour = name => { probe.style.backgroundColor = root.getPropertyValue(name); return getComputedStyle(probe).backgroundColor; };
              const expectedPage = colour('--surface-page'), expectedStory = colour('--surface-default');
              const story = document.querySelector('.app-login-story');
              const actualPage = getComputedStyle(document.body).backgroundColor;
              const actualStory = story && getComputedStyle(story).backgroundColor;
              probe.remove();
              console.log('619|surfaces|' + JSON.stringify({expectedPage, expectedStory, actualPage, actualStory}));
              return actualPage === expectedPage && actualStory === expectedStory;
            }
            """.trimIndent()
    }
}
