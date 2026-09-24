package co.datapipelines.web.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * The `datapipelines.bootstrap.*` keys (configuration.md §3.18).
 *
 * `datasourcesFile` and `examplesFile` are **paths on the container's filesystem, and unset means
 * off** — there is no separate `enabled` flag to disagree with them. "Unset" covers an absent key
 * and the empty string, because the shipped `application.yml` gives both keys an empty env-var
 * default (`${VAR:}`), exactly as `datapipelines.auth.bootstrap-admin-email` does.
 *
 * `demoApiKey` (#224) is not a path but a credential value; its off switch is the BLANK value, and
 * its default is committed — see [demoApiKey].
 *
 * Neither file key is a URL. The app never fetches an artifact at runtime (sample-data design D5):
 * downloading and verifying artifacts is a deployment step, and by the time the app reads these
 * files they are already on disk.
 *
 * **Both file values are comma-separated LISTS of paths** (one file per sample-data family; the
 * demo profiles compose the list from the active families). A single value with no comma is the
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
    /**
     * `demo-api-key` (#224) — the full `dpk_<id>.<secret>` plaintext the demo workspace's public
     * `api_caller` key is minted from, shown on the site's demo-data page. The Kotlin default IS
     * the committed default: the offline static export constructs this class with no Spring, and
     * the page must render the same value the app ships. `application.yml` mirrors the literal as
     * the `${DATAPIPELINES_DEMO_API_KEY:…}` fallback, pinned to this constant by
     * `BootstrapConfigKeysSpecDriftTest`, and the compose pass-through mirrors it again —
     * `scripts/compose-env-audit.sh` holds that copy to the same line.
     *
     * **Blank (not unset) is the kill switch.** A committed default means an absent env var
     * resolves to the default, so "off" is spelled `DATAPIPELINES_DEMO_API_KEY=` — an explicitly
     * empty value seeds no key, publishes no demo endpoints and hides the page's section. A
     * changed value is the rotation: on the next boot the old key is revoked and the configured
     * one minted and bound. It is public by design; its whole reach is the bound demo endpoints.
     */
    val demoApiKey: String? = DEFAULT_DEMO_API_KEY,
) {
    /** Blank or absent means the demo API feature is off — the one switch, no separate flag. */
    fun demoApiKeyConfigured(): Boolean = !demoApiKey.isNullOrBlank()

    companion object {
        /**
         * The committed public demo key. The shape is auth.md's own key grammar (`dpk_` + 12
         * RFC 4648 base32 chars + `.` + 48 base32 chars) so the presented credential passes the
         * §7.1 shape gate like any other. There is no secret to keep: the key's reach is the
         * bound demo endpoints of a demo workspace, and rotation is a config change + restart.
         */
        const val DEFAULT_DEMO_API_KEY =
            "dpk_DEMOPUBLIC42.THISKEYISPUBLICBYDESIGNDEMOONLYREADONLY222222222"
    }

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
