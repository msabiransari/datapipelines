package co.datapipelines.web.ui

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The `datapipelines.ui.*` keys.
 *
 * `theme` is the deployment-wide default. A per-user `theme_preference` (when
 * explicitly set in users.theme_preference) overrides it for that user.
 */
@ConfigurationProperties(prefix = "datapipelines.ui")
data class UiProperties(
    val theme: String = "saas",
)
