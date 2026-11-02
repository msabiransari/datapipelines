package co.datapipelines.browser

import co.datapipelines.browser.ScheduleFixtures.createSchedule
import co.datapipelines.browser.ScheduleFixtures.releasedPipeline
import co.datapipelines.browser.ScheduleFixtures.send
import com.microsoft.playwright.Page
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * #584 — Schedules in real Chromium: `/overview/schedules`, the resource browser closed.
 * The explorer stays at `/schedules` (View all). A fresh workspace reads zeros and an author's New schedule; the page counts it
 * and its Up next row opens the explorer's detail for that schedule. #639: on the v2 frame a
 * panel's info button opens its dialog section, the task row leads to the explorer, and a viewer
 * sees no New schedule, only who creates schedules. #641: the Unblock task row opens the explorer
 * filtered to blocked — a flat list, the select reading Blocked, the address bar carrying it — and
 * All brings the tree back with the parameter gone. #725: each Last run bar opens the explorer
 * filtered to its bucket (`?outcome=`), and the bar's count is the filtered list's row count — walked
 * over all five buckets for a viewer and a promoter (#764). Succeeded and Did not run use real
 * Run now actions; Failed and In progress use seeded state words. All outcomes restores the tree.
 */
class OverviewSchedulesBrowserTest : SchedulesBrowserSuite() {
    private fun openOverview() {
        page.navigate("$baseUrl/dashboard")
        page.click(".app-nav-link[data-nav-section='/schedules']")
        page.waitForURL("**/overview/schedules")
        page.waitForSelector(".app-overview[data-overview='schedules']")
    }

    private fun count(key: String): String = page.locator("[data-count='$key'] .app-overview-count-v").innerText().trim()

    private fun bar(
        chart: String,
        key: String,
    ): String = page.locator("[data-chart='$chart'] [data-bar='$key'] .app-overview-bar-v").innerText().trim()

    @Test
    fun `the Schedules item lands on the home - a fresh workspace reads zeros and offers New schedule`() {
        startTrace()
        ready("schov")

        openOverview()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/schedules"
        page.locator("#app-crumbs .app-crumb-page").innerText().trim() shouldBe "Schedules"
        page.locator("h1").innerText().trim() shouldBe "Schedules"
        page.locator("#app-browser").isHidden shouldBe true
        page.locator("#browser-open").isHidden shouldBe true

        listOf("schedules", "enabled", "paused", "blocked").map(::count) shouldBe listOf("0", "0", "0", "0")
        bar("last-run", "never-ran") shouldBe "0"
        page.locator("[data-overview-recent]").innerText().let { recent ->
            (recent.contains("No enabled schedule is waiting to run.") && recent.contains("No schedules yet.")) shouldBe true
        }
        page.locator("[data-overview-prompt], [data-overview-copy]").count() shouldBe 0

        // #639: the condition panel's info button opens its own dialog section; Escape closes
        // it and focus returns to the button.
        page.click("[data-overview-panel='condition'] [data-open='condition']")
        page.waitForFunction("() => document.querySelector('dialog[data-overview-dialog]').open")
        page.locator("[data-overview-dialog-title]").innerText().trim() shouldBe "How conditions are counted"
        page.locator("[data-dialog-section='condition']").isVisible shouldBe true
        page.locator("[data-dialog-section='howitworks']").isVisible shouldBe false
        page.keyboard().press("Escape")
        page.waitForFunction("() => !document.querySelector('dialog[data-overview-dialog]').open")
        page.evaluate("() => document.activeElement?.getAttribute('data-open')") shouldBe "condition"

        // New schedule opens the explorer, where the form is.
        page.click("[data-overview-new-schedule]")
        page.waitForURL("**/schedules")
        page.locator("#schedule-list:not([aria-busy])").waitFor()
        page.locator(".app-nav-link.active").getAttribute("data-nav-section") shouldBe "/schedules"
    }

    @Test
    fun `a schedule is counted, and its Up next row opens its detail`() {
        startTrace()
        val root = ready("schovn")
        val pipeline = "$root/jobs/rows"
        releasedPipeline(page, pipeline)
        val id = createSchedule(page, "$root/nightly/rows", pipeline)

        openOverview()
        listOf("schedules", "enabled", "paused", "blocked").map(::count) shouldBe listOf("1", "1", "0", "0")
        bar("condition", "enabled") shouldBe "1"
        bar("last-run", "never-ran") shouldBe "1"
        page.locator("[data-chart='last-run'] [data-bar='never-ran'] rect").getAttribute("width") shouldBe "100.0"
        HomeOpsShots.take(page, "schedules", "condition")

        // The first link of the sections, not the card: Continue your work opens on its task row (#639).
        page.locator("[data-overview-recent] [data-task='next'] a").getAttribute("href") shouldBe "/schedules"
        val upNext = page.locator("[data-overview-recent] .app-overview-recent li a").first()
        upNext.innerText().trim() shouldBe "$root/nightly/rows"
        upNext.getAttribute("href") shouldBe "/schedules?id=$id"
        upNext.click()
        page.waitForURL("**/schedules?id=$id")
        detail().waitFor()
    }

    @Test
    fun `the Unblock row opens the explorer filtered to blocked, and All brings the tree back`() {
        startTrace()
        val root = ready("schovb")
        releasedPipeline(page, "$root/jobs/rows")
        val closing = releasedPipeline(page, "$root/jobs/close")
        createSchedule(page, "$root/nightly/rows", "$root/jobs/rows")
        val blocked = createSchedule(page, "$root/monthly/close", "$root/jobs/close")
        // Blocked the way a person meets it: its pipeline's release discarded, then a Run now that cannot start.
        send(page, "POST", "/api/v1/pipelines/$closing/versions/1/discard").status shouldBe 200
        openSchedules("?id=$blocked")
        detail().waitFor()
        page.locator("[data-verb='schedule-run']").click()
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()

        openOverview()
        count("blocked") shouldBe "1"
        page.click("[data-overview-recent] [data-task='unblock'] a")
        page.waitForURL("**/schedules?condition=blocked")
        page.locator("#schedule-list:not([aria-busy]) .tpl-results").waitFor()
        page.locator("#schedule-filter-condition").inputValue() shouldBe "blocked"
        page.locator("#schedule-tree-pane [data-leaf-name]").all().map { it.getAttribute("data-leaf-name") } shouldBe
            listOf("$root/monthly/close")
        page.locator("#schedule-list details.tpl-folder").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules?condition=blocked"

        page.selectOption("#schedule-filter-condition", "")
        page.locator("#schedule-list:not([aria-busy]) [role='tree'] details.tpl-folder[data-folder='$root']").waitFor()
        page.locator("#schedule-list .tpl-results").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules"
    }

    @Test
    fun `each Last run bar opens the explorer filtered to that last run - its count is the list's rows - and All brings the tree back`() {
        startTrace()
        val root = "schovo" + suffix()
        val outcomes = prepareOutcomes(root)
        openMember(root, "viewer")
        walkOutcomes(root, outcomes)
    }

    @Test
    fun `a promoter walks all five Last run bars without schedules already held by the target`() {
        startTrace()
        val root = "schovp" + suffix()
        val outcomes = prepareOutcomes(root)
        openMember(root, "promoter")
        walkOutcomes(root, outcomes.mapValues { (bucket, names) -> if (bucket in setOf("failed", "in-progress")) emptyList() else names })
    }

    private fun prepareOutcomes(root: String): Map<String, List<String>> {
        val admin = seedLocalUser(uniqueEmail("$root-admin"), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace(root)
        releasedPipeline(page, "$root/jobs/rows")
        val closing = releasedPipeline(page, "$root/jobs/close")
        releasedPipeline(page, ScheduleFixtures.HIDDEN_PIPELINE)
        val ok = createSchedule(page, "$root/nightly/rows", "$root/jobs/rows")
        val stopped = createSchedule(page, "$root/monthly/close", "$root/jobs/close")
        val failed = createSchedule(page, "$root/failed", ScheduleFixtures.HIDDEN_PIPELINE)
        val queued = createSchedule(page, "$root/in-progress", ScheduleFixtures.HIDDEN_PIPELINE)
        val never = createSchedule(page, "$root/never-ran", "$root/jobs/rows")
        ScheduleFixtures.seedLastRun(failed, "failed")
        ScheduleFixtures.seedLastRun(queued, "queued")
        mapOf(failed to "failed", queued to "queued").forEach { (id, state) ->
            withClue("fixture $state") {
                openSchedules("?id=$id")
                detail().waitFor()
                page.locator("#schedule-detail .sch-runrow[data-state='$state']").waitFor()
            }
        }
        openSchedules("?id=$never")
        detail().waitFor()
        page.locator("#schedule-detail [data-slot='runs-empty']:not([hidden])").waitFor()
        page.locator("#schedule-detail .sch-runrow").count() shouldBe 0
        // A real succeeded run: Run now, followed to its end (SchedulesRunsBrowserTest's wait).
        openSchedules("?id=$ok")
        detail().waitFor()
        page.waitForResponse({ it.url().endsWith("/api/v1/schedules/$ok/run") && it.status() == 202 }) {
            page.locator("[data-verb='schedule-run']").click()
        }
        page.waitForFunction(
            "() => (document.querySelector('#schedule-detail .sch-runrow') || {}).getAttribute && " +
                "document.querySelector('#schedule-detail .sch-runrow').getAttribute('data-state') === 'succeeded'",
            null,
            Page.WaitForFunctionOptions().setTimeout(60_000.0),
        )
        // A real run that did not start: its release discarded, then a Run now the worker refuses.
        send(page, "POST", "/api/v1/pipelines/$closing/versions/1/discard").status shouldBe 200
        openSchedules("?id=$stopped")
        detail().waitFor()
        page.locator("[data-verb='schedule-run']").click()
        page.locator("#schedule-detail [data-slot='blocked']:not([hidden])").waitFor()
        page.locator("#schedule-detail .sch-runrow[data-state='not_started']").waitFor()
        // Restore the pointer so the promoter admits this schedule; its last run remains not_started.
        send(page, "POST", "/api/v1/pipelines/$closing/versions/1/restore").status shouldBe 200
        return linkedMapOf(
            "succeeded" to listOf("$root/nightly/rows"),
            "failed" to listOf("$root/failed"),
            "in-progress" to listOf("$root/in-progress"),
            "did-not-run" to listOf("$root/monthly/close"),
            "never-ran" to listOf("$root/never-ran"),
        )
    }

    private fun openMember(
        root: String,
        role: String,
    ) {
        val member = seedLocalUser(uniqueEmail("$root-$role"), generatedPassword("pw"), mustChange = false, isAdmin = false, role = role)
        ScheduleFixtures.seedMembership(root, member.email, role)
        page.context().clearCookies()
        login(member.email, member.oneTimePassword)
        page.waitForURL("**/dashboard")
        enterWorkspace(page, root)
    }

    private fun walkOutcomes(
        root: String,
        outcomes: Map<String, List<String>>,
    ) {
        val hidden = listOf("$root/failed", "$root/in-progress").filter { it !in outcomes.values.flatten() }
        openOverview()
        listOf("succeeded", "failed", "in-progress", "did-not-run", "never-ran").map { bar("last-run", it) } shouldBe
            outcomes.values.map { it.size.toString() }
        outcomes.forEach { (outcome, names) ->
            withClue(outcome) {
                openOverview()
                hidden.forEach { page.locator("body").innerText().contains(it) shouldBe false }
                val counted = bar("last-run", outcome).toInt()
                page.click("[data-chart='last-run'] [data-bar='$outcome'] .app-overview-bar-name a")
                page.waitForURL("**/schedules?outcome=*")
                page.url().substringAfter(baseUrl) shouldBe "/schedules?outcome=$outcome"
                page.locator("#schedule-list:not([aria-busy])").waitFor()
                page.locator("#schedule-list [data-empty='no-match-filtered']").count() shouldBe if (names.isEmpty()) 1 else 0
                page.locator("#schedule-filter-outcome").inputValue() shouldBe outcome
                page.locator("#schedule-filter-condition").inputValue() shouldBe ""
                val rows = page.locator("#schedule-tree-pane [data-leaf-name]").all().map { it.getAttribute("data-leaf-name") }
                rows shouldBe names
                rows.size shouldBe counted
                hidden.forEach { page.locator("body").innerText().contains(it) shouldBe false }
                page.locator("#schedule-list details.tpl-folder").count() shouldBe 0
            }
        }

        page.selectOption("#schedule-filter-outcome", "")
        page.locator("#schedule-list:not([aria-busy]) [role='tree'] details.tpl-folder[data-folder='$root']").waitFor()
        page.locator("#schedule-list .tpl-results").count() shouldBe 0
        page.url().substringAfter(baseUrl) shouldBe "/schedules"
        page.locator("details[data-folder='$root'] > summary").click()
        page.locator("details[data-folder='$root'] > .tpl-level:not([aria-busy])").waitFor()
        leaf("$root/never-ran").isVisible shouldBe true
        hidden.forEach { leaf(it).count() shouldBe 0 }
        hidden.forEach { page.locator("body").innerText().contains(it) shouldBe false }
    }

    @Test
    fun `a viewer lands on the home with no New schedule and reads who creates schedules`() {
        startTrace()
        val viewer =
            seedLocalUser(
                uniqueEmail("schov-viewer-" + suffix()),
                generatedPassword("pw"),
                mustChange = false,
                isAdmin = false,
                role = "viewer",
            )
        login(viewer.email, viewer.oneTimePassword)
        page.waitForURL("**/dashboard")
        openOverview()
        // Absent, not hidden: the canAuthor guard renders no link at all.
        page.locator("[data-overview-new-schedule]").count() shouldBe 0
        page.locator("[data-overview-create-note]").innerText() shouldContain "an author or a workspace admin"
        page.locator("[data-overview-recent] [data-task='next'] a").getAttribute("href") shouldBe "/schedules"
    }

    private companion object {
        private val targetKey = UUID.randomUUID().toString()
        private val target = ScheduleFixtures.promotionTarget(targetKey)

        @DynamicPropertySource
        @JvmStatic
        fun promotionProperties(registry: DynamicPropertyRegistry) {
            registry.add("datapipelines.deployment.promotion.target.base-url") { "http://127.0.0.1:${target.address.port}" }
            registry.add("datapipelines.deployment.promotion.target.server-key") { targetKey }
        }

        @AfterAll
        @JvmStatic
        fun stopTarget() {
            target.stop(0)
        }
    }
}
