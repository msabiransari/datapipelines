package co.datapipelines.config

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.io.File

/**
 * The `datapipelines.deployment.promotion.*` keys (configuration.md §3.19, versioning §10.6;
 * 178 added `inventory-cache-ttl-seconds` for the promoter lens), pinned in the three places
 * they have to agree: the doc (the authority), `application.yml` (the shipped defaults) and
 * `PromotionProperties` (the class they bind to — read as SOURCE, since `app`'s test classpath
 * does not see `auth`, exactly as [OrgConfigKeysSpecDriftTest] reads `ConfigValidator`).
 *
 * Same shape and the same two targets as [OrgConfigKeysSpecDriftTest]: the doc-vs-code drift
 * `scripts/docs-audit.sh` cannot see, and the YAML reparenting hazard — 178 inserted its key
 * INSIDE the existing `promotion:` block, so the keys that would break are the block's own
 * neighbours (`target.base-url`, `target.server-key`) and the block that FOLLOWS it
 * (`workspaces`), all asserted here to still bind at their old depth.
 */
class PromotionConfigKeysSpecDriftTest {
    private val shipped: Map<String, Any?> by lazy { load("modules/app/src/main/resources/application.yml") }
    private val source: String by lazy { repoFile(SOURCE_PATH).readText() }

    @Test
    fun `configuration_md, application_yml and PromotionProperties name exactly the same promotion keys`() {
        val documented = keysIn(repoFile("docs/configuration.md").readText())
        val inYaml = shipped.keys.filter { it.startsWith("$PREFIX.") }.sorted()
        val bound = boundKeys()

        documented.shouldNotBeEmpty()
        documented shouldContainExactly EXPECTED
        inYaml shouldContainExactly EXPECTED
        bound shouldContainExactly EXPECTED
    }

    @Test
    fun `the shipped defaults are the documented ones, and the TTL default is the class's`() {
        shipped["$PREFIX.server-key"] shouldBe "\${DATAPIPELINES_DEPLOYMENT_PROMOTION_SERVER_KEY:}"
        shipped["$PREFIX.target.base-url"] shouldBe "\${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_URL:}"
        shipped["$PREFIX.target.server-key"] shouldBe "\${DATAPIPELINES_DEPLOYMENT_PROMOTION_TARGET_KEY:}"
        val classDefault =
            checkNotNull(
                CLASS_DEFAULT.find(source),
            ) { "no DEFAULT_INVENTORY_CACHE_TTL_SECONDS in $SOURCE_PATH" }.groupValues[1]
        shipped["$PREFIX.inventory-cache-ttl-seconds"] shouldBe
            "\${DATAPIPELINES_DEPLOYMENT_PROMOTION_INVENTORY_CACHE_TTL_SECONDS:$classDefault}"
        classDefault shouldBe "60"
    }

    @Test
    fun `the neighbouring keys still bind - the 178 insert re-parented nothing`() {
        // The sibling above the promotion block, and the block that FOLLOWS `deployment:` —
        // the ones a mis-indented insert would have swallowed.
        shipped["datapipelines.deployment.authoring-enabled"] shouldBe "\${DATAPIPELINES_DEPLOYMENT_AUTHORING_ENABLED:true}"
        shipped["datapipelines.workspaces.member-datasources-enabled"] shouldBe
            "\${DATAPIPELINES_WORKSPACES_MEMBER_DATASOURCES_ENABLED:true}"
        shipped["datapipelines.env"] shouldBe "\${DATAPIPELINES_ENV:local}"
    }

    /**
     * The keys `PromotionProperties` binds, spelled the way the yaml spells them: each
     * constructor property in kebab-case, the nested `Target`'s under `target.`. Read off the
     * class's source so a property added without a doc row (or a doc row with no property)
     * fails here by name.
     */
    private fun boundKeys(): List<String> {
        val top = constructorProperties("class PromotionProperties(").filter { it != "target" }.map { "$PREFIX.${kebab(it)}" }
        val nested = constructorProperties("class Target(").map { "$PREFIX.target.${kebab(it)}" }
        return (top + nested).sorted()
    }

    /** The `val name:` constructor parameters between [header] and the `) {` that closes it. */
    private fun constructorProperties(header: String): List<String> {
        val start = source.indexOf(header).also { check(it >= 0) { "no '$header' in $SOURCE_PATH" } } + header.length
        val body = source.substring(start, source.indexOf(") {", start))
        return CONSTRUCTOR_VAL.findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun kebab(camel: String): String =
        camel.replace(Regex("([a-z0-9])([A-Z])")) {
            "${it.groupValues[1]}-${it.groupValues[2].lowercase()}"
        }

    private fun load(relative: String): Map<String, Any?> =
        YamlPropertySourceLoader()
            .load(relative, FileSystemResource(repoFile(relative)))
            .filterIsInstance<EnumerablePropertySource<*>>()
            .flatMap { source -> source.propertyNames.map { name -> name to source.getProperty(name) } }
            .toMap()

    private fun keysIn(text: String): List<String> =
        KEY_REGEX
            .findAll(text)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    /** The repo root is the nearest ancestor holding `settings.gradle.kts` (the house locator). */
    private fun repoFile(relative: String): File {
        var dir = File(".").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("settings.gradle.kts not found above ${File(".").absolutePath}")
        }
        return File(dir, relative).also { check(it.isFile) { "missing $relative" } }
    }

    private companion object {
        const val PREFIX = "datapipelines.deployment.promotion"
        const val SOURCE_PATH = "modules/auth/src/main/kotlin/co/datapipelines/auth/PromotionProperties.kt"

        val EXPECTED =
            listOf(
                "$PREFIX.inventory-cache-ttl-seconds",
                "$PREFIX.server-key",
                "$PREFIX.target.base-url",
                "$PREFIX.target.server-key",
            )

        /** A promotion key: the prefix, then one or two kebab segments (`target.base-url`). */
        val KEY_REGEX = Regex("datapipelines\\.deployment\\.promotion\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)?")

        val CONSTRUCTOR_VAL = Regex("val ([a-zA-Z]+):")
        val CLASS_DEFAULT = Regex("DEFAULT_INVENTORY_CACHE_TTL_SECONDS: Long = (\\d+)")
    }
}
