package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * The inbound create/update shape for a template (templates.md §5.3, §9).
 *
 * ## Why a separate type from [Template]
 *
 * The DTO rule (module security review): **server-assigned fields are absent from inbound
 * shapes, never merely `@JsonIgnore`-filtered after binding.** A create request cannot express
 * `version`, `created_at`, or `created_by` — they are not fields on this class, so there is no
 * mode in which a client can set them. The server assigns them ([TemplateRepository]).
 *
 * [id] is nullable because it is auto-generated when omitted on create (templates.md §3.2);
 * on update the caller supplies it. [engine] defaults to `freemarker`, the only v1 value
 * (enums.md §6); the write path never omits it.
 *
 * ## `type` and `dialect` are conditional on each other (046, §5.3/§5.4)
 *
 * [type] is accepted on **create only**, defaulting to [TemplateType.SQL] when absent;
 * afterwards it is read-only, and a write payload carrying a different type is refused with
 * `template.validation.type_immutable` ([TemplateTypeRule] resolves the established value
 * into every draft a write path stores). A null [type] means "not stated by this payload" —
 * never a third kind of template. [dialect] is nullable because an `html` template declares
 * none; the type/dialect consistency rules are [TemplateValidator]'s, so every write surface
 * (REST, MCP, import) enforces the same pair.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)` is what makes a payload that *does* carry
 * `version` / `created_at` / `created_by` bind cleanly **without** them — the server-assigned
 * values are dropped because they have nowhere to land, not because a filter removed them.
 * Every key is pinned with an explicit `@JsonProperty` for the reasons on [Template].
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TemplateDraft(
    @field:JsonProperty("schema_version") @get:JsonProperty("schema_version") @param:JsonProperty("schema_version")
    val schemaVersion: Int = Template.SUPPORTED_SCHEMA_VERSION,
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String? = null,
    @field:JsonProperty("engine") @get:JsonProperty("engine") @param:JsonProperty("engine")
    val engine: String = Template.FREEMARKER_ENGINE,
    @field:JsonProperty("type") @get:JsonProperty("type") @param:JsonProperty("type")
    val type: TemplateType? = null,
    @field:JsonProperty("dialect") @get:JsonProperty("dialect") @param:JsonProperty("dialect")
    val dialect: Dialect? = null,
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
)
