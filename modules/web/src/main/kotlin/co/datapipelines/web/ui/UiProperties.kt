package co.datapipelines.web.ui

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.ui.*` keys.
 *
 * `theme` is the deployment-wide default. A per-user `theme_preference` (when
 * explicitly set in users.theme_preference) overrides it for that user.
 *
 * `activelyDevelopedDays` (#598b) is the window of the Pipelines home's "actively developed"
 * count: a pipeline whose draft was last written within that many days. An integer of at least
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
