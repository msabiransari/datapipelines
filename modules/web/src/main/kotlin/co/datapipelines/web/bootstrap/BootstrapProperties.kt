package co.datapipelines.web.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * The `datapipelines.bootstrap.*` keys (configuration.md §3.18).
 *
 * Both are **paths on the container's filesystem, and unset means off** — there is no separate
 * `enabled` flag to disagree with them. "Unset" covers an absent key and the empty string, because
 * the shipped `application.yml` gives both keys an empty env-var default (`${VAR:}`), exactly as
 * `datapipelines.auth.bootstrap-admin-email` does.
 *
 * Neither key is a URL. The app never fetches an artifact at runtime (sample-data design D5):
 * downloading and verifying artifacts is a deployment step, and by the time the app reads these
 * files they are already on disk.
 */
@ConfigurationProperties(prefix = "datapipelines.bootstrap")
data class BootstrapProperties(
    /** YAML file of datasource definitions to register create-if-absent (datasources.md §8A). */
    val datasourcesFile: String? = null,
    /** JSON file of example templates + pipelines to seed into personal workspaces (D9). */
    val examplesFile: String? = null,
) {
    /** [datasourcesFile] as a path, or null when the feature is off. */
    fun datasourcesPath(): Path? = toPath(datasourcesFile)

    /** [examplesFile] as a path, or null when the feature is off. */
    fun examplesPath(): Path? = toPath(examplesFile)

    private fun toPath(raw: String?): Path? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of)
}
