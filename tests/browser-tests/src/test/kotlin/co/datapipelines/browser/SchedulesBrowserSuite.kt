package co.datapipelines.browser

/**
 * #9 slice 2 — the Schedules page's browser suites (explorer, detail, form, runs) share this: the
 * real application and its dispatcher, driven through the page — every assertion is what a person
 * sees after the action, and every write went through the page's own REST calls to rest-api §20.
 *
 * Each test signs in a fresh super admin and creates its OWN workspace, so the schedules it sees
 * are its own (the per-workspace cap and the tree's contents cannot leak between tests). Every
 * schedule fires once a year ([ScheduleFixtures.YEARLY]), so nothing is due while a suite runs: the
 * only runs are the ones a test asks for with Run now, which the context's dispatcher executes like
 * any other.
 */
abstract class SchedulesBrowserSuite : BrowserSuite() {
    protected fun suffix(): String = generatedPassword("s").takeLast(8).lowercase()

    /** A super admin in a workspace of their own; returns the root folder this test's names live under. */
    protected fun ready(slug: String): String {
        val user = seedLocalUser(uniqueEmail("$slug-" + suffix()), generatedPassword("pw"), mustChange = false)
        login(user.email, user.oneTimePassword)
        page.waitForURL("**/dashboard")
        createWorkspace("$slug" + suffix())
        return slug + suffix()
    }

    protected fun openSchedules(query: String = "") {
        page.navigate("$baseUrl/schedules$query")
        page.locator("#schedule-list:not([aria-busy])").waitFor()
    }

    protected fun leaf(name: String) = page.locator("#schedule-tree-pane [data-leaf-name='$name']")

    protected fun detail() = page.locator("#schedule-detail [data-schedule-detail]")
}
