package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineVersionStatus
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * The public projection of one stored template version (templates.md §3).
 *
 * This is an **outbound** shape: every field, including the server-assigned ones
 * ([version], [createdAt], [createdBy]), is present because a reader is entitled to see what
 * the server stored. Inbound create/update payloads use [TemplateDraft], which deliberately
 * omits those fields (the DTO rule: server-assigned fields are *absent* from inbound shapes,
 * not filtered out after binding).
 *
 * ## No parameter schema (D3)
 *
 * There is no `params_schema` field anywhere in this module. A template declares no parameters
 * of its own; the calling pipeline's `parameters` map (defaults applied) is the render context
 * and the single declaration point (templates.md §2.5). [description] is the only place a
 * template can hint at what it expects, which is why it is required.
 *
 * ## The wire keys are pinned, not inferred
 *
 * templates.md §3.1 is snake_case and §11.1 freezes it, so every field carries an explicit
 * `@JsonProperty` on all three use-site targets. Three independent Jackson behaviours would
 * otherwise rewrite these keys silently: the Java-Beans `^[a-z][A-Z]` rule (`displayName` →
 * `displayname` under a naming strategy), Kotlin's `is`-prefix getter rule (`isLibrary` →
 * bean property `library`), and whatever naming strategy an upstream mapper happens to carry —
 * and the outbound mapper at run time is the *web* module's, not this module's. The
 * annotations are the contract; no naming strategy is relied on anywhere.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Template(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int = SUPPORTED_SCHEMA_VERSION,
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int,
    @field:JsonProperty("engine") @get:JsonProperty("engine") @param:JsonProperty("engine")
    val engine: String = FREEMARKER_ENGINE,
    @field:JsonProperty("dialect") @get:JsonProperty("dialect") @param:JsonProperty("dialect")
    val dialect: Dialect,
    @field:JsonProperty("display_name") @get:JsonProperty("display_name") @param:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("description") @get:JsonProperty("description") @param:JsonProperty("description")
    val description: String,
    @field:JsonProperty("imports") @get:JsonProperty("imports") @param:JsonProperty("imports")
    val imports: List<TemplateImport> = emptyList(),
    @field:JsonProperty("body") @get:JsonProperty("body") @param:JsonProperty("body")
    val body: String,
    @field:JsonProperty("is_library") @get:JsonProperty("is_library") @param:JsonProperty("is_library")
    val isLibrary: Boolean = false,
    @field:JsonProperty("created_at") @get:JsonProperty("created_at") @param:JsonProperty("created_at")
    val createdAt: Instant,
    @field:JsonProperty("created_by") @get:JsonProperty("created_by") @param:JsonProperty("created_by")
    val createdBy: UUID,
    /**
     * The version's lifecycle status (versioning §3.1/§6, since V6) — `RELEASED` on the
     * pre-lifecycle projection; defaulted so existing constructors keep compiling, and so a
     * read that does not select the column still renders an honest value.
     */
    val status: PipelineVersionStatus = PipelineVersionStatus.RELEASED,
    /** The version's SHA-256 content hash (versioning §4) — the mutation precondition token. */
    @field:JsonProperty("body_hash") @get:JsonProperty("body_hash") @param:JsonProperty("body_hash")
    val bodyHash: String = "",
) {
    companion object {
        /** The only `schema_version` v1 accepts (templates.md §3.2). */
        const val SUPPORTED_SCHEMA_VERSION = 1

        /** The only `engine` v1 supports (enums.md §6). */
        const val FREEMARKER_ENGINE = "freemarker"
    }
}

/**
 * One immutable `template_versions` row — the persistence-facing per-version record
 * (metadata-db §4.9).
 *
 * [TemplateEngine] and [LibraryResolver] resolve imports at an exact `{id, version}` against
 * this shape: it carries the [body], the [imports] array, and the [isLibrary] flag that
 * import validation checks, all pinned to one version. A version is never updated in place
 * (templates.md §5.1), so a [TemplateVersion] is safe to cache indefinitely by its [key].
 */
data class TemplateVersion(
    val id: String,
    val version: Int,
    val engine: String = Template.FREEMARKER_ENGINE,
    val dialect: Dialect,
    val isLibrary: Boolean,
    val imports: List<TemplateImport>,
    val body: String,
    val createdAt: Instant,
    val createdBy: UUID,
) {
    /** The registry lookup key, `"{id}@{version}"`. */
    val key: String get() = "$id@$version"
}
