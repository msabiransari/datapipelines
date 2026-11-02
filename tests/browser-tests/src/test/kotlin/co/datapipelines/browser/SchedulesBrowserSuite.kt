package co.datapipelines.browser

/**
 * #9 slice 2 — the Schedules page's browser suites (explorer, detail, form, runs) share this: the
 * real application and its dispatcher, driven through the page — assertions check what a person
 * sees after the action. Fixtures generally use the page's REST calls to rest-api §20; #764's
 * overview walks also seed isolated membership and latest-run rows.
 *
 * Each test prepares a fresh super admin and its OWN workspace; role walks then sign in a fresh
 * viewer or promoter in that workspace. The per-workspace cap and tree contents stay isolated. Every
 * schedule fires once a year ([ScheduleFixtures.YEARLY]), so nothing is due while a suite runs: the
 * run is either requested with Run now, which the context's dispatcher executes like any other,
 * or explicitly seeded as an outcome fixture without a dispatcher task.
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
