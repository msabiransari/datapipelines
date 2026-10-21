package co.datapipelines.web.ui

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.ui.*` keys.
 *
 * `theme` is the deployment-wide default. A per-user `theme_preference` (when
 * explicitly set in users.theme_preference) overrides it for that user.
 *
 * `activelyDevelopedDays` (#598b) is the window of every lifecycle home's "actively developed"
 * count (Pipelines since #598b, the four object families since #599): an object whose draft was
 * last written within that many days. An integer of at least
 * one; `ConfigValidator` refuses any other value at startup (configuration.md §3.10).
 */
@ConfigurationProperties(prefix = "datapipelines.ui")
data class UiProperties(
    val theme: String = "dark",
    val activelyDevelopedDays: Int = DEFAULT_ACTIVELY_DEVELOPED_DAYS,
) {
    companion object {
        const val DEFAULT_ACTIVELY_DEVELOPED_DAYS = 14
    }
}
