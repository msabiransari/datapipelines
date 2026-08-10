package co.datapipelines.templates

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One entry of a template's `imports` array — a library binding (templates.md §6.3, D12).
 *
 * Each entry binds one library version to one namespace [alias]. The body never writes an
 * `<#import>` directive itself: the engine synthesizes `<#import "{id}@{version}" as {alias}>`
 * from this array at render time ([RegistryTemplateLoader]), so an alias can never point at an
 * unvalidated, unpinned, or non-library template (templates.md §6.3, §4.2).
 *
 * All three fields carry an explicit `@JsonProperty` on every use-site target: the imports
 * array is a frozen wire shape (templates.md §11.1) and a naming strategy configured upstream
 * must not be able to rewrite these keys (the Java-Beans `^[a-z][A-Z]` trap).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TemplateImport(
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int,
    @field:JsonProperty("alias") @get:JsonProperty("alias") @param:JsonProperty("alias")
    val alias: String,
) {
    /** The registry lookup key, `"{id}@{version}"` — the form [RegistryTemplateLoader] resolves. */
    val key: String get() = "$id@$version"
}
