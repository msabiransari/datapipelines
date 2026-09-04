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
 *
 * **Both values are comma-separated LISTS of paths** (one file per sample-data family; the demo
 * profiles compose the list from the active families). A single value with no comma is the
 * one-file shape every pre-family deployment shipped — the list semantics are backward
 * compatible by construction. Empty entries (a leading comma when a family is off, or an
 * entry of whitespace) are dropped, so an env var built by shell conditional expansion never
 * turns the feature on by accident.
 */
@ConfigurationProperties(prefix = "datapipelines.bootstrap")
data class BootstrapProperties(
    /** YAML file(s) of datasource definitions to register create-if-absent (datasources.md §8A). */
    val datasourcesFile: String? = null,
    /** JSON file(s) of example templates + pipelines to seed into personal workspaces (D9). */
    val examplesFile: String? = null,
) {
    /** Every configured datasources file, in declared order; empty list when the feature is off. */
    fun datasourcesPaths(): List<Path> = toPaths(datasourcesFile)

    /** Every configured examples file, in declared order; empty list when the feature is off. */
    fun examplesPaths(): List<Path> = toPaths(examplesFile)

    private fun toPaths(raw: String?): List<Path> =
        raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map { Path.of(it) }
            .orEmpty()
}
