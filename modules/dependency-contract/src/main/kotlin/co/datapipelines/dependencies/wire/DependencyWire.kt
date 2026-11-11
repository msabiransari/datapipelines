package co.datapipelines.dependencies.wire

/**
 * Where the frozen §11.1 wire contract lives as files (owner ruling 2026-11-08, option 1): the JSON Schema 2020-12 and
 * one golden payload per named case, as classpath resources of this module. G4 serves the shapes and asserts its route
 * goldens' `data`/`details` byte-equal to these files; G6 loads them for its renderer tests. The envelopes, catalog
 * codes and HTTP statuses are G4's and appear in neither.
 */
object DependencyWire {
    /** The schema's `$id`; the path segment and the file name carry the wire major version `v1`. */
    const val SCHEMA_ID = "https://datapipelines.co/schemas/dependencies/v1/dependencies-v1.schema.json"

    /** The schema as a classpath resource. */
    const val SCHEMA_RESOURCE = "/co/datapipelines/dependencies/wire/dependencies-v1.schema.json"

    /** The golden directory as a classpath resource: `<case>.json`, a `data` object or an error `details` object. */
    const val GOLDEN_RESOURCE_DIR = "/co/datapipelines/dependencies/wire/goldens"

    /** The `data.schema_version` constant; a consumer refuses a payload whose version it does not know. */
    const val SCHEMA_VERSION = 1
}

/** The §11.1 request query fields a refusal names in `details.field`. */
enum class RequestField(
    val wire: String,
) {
    FAMILY("family"),
    NAME("name"),
    ID("id"),
    VERSION("version"),
}

/** The §11.1 request-rule refusals, `details.reason` of the route's `dependencies.request.invalid` (closed set). */
enum class RefusalReason(
    val wire: String,
) {
    /** `family` is not one of the eight production keys. */
    FAMILY_UNKNOWN("family_unknown"),

    /** The selector shape does not fit the family: `id` on a named family, `name` with `id`, `version` where forbidden. */
    SELECTOR_INCOMPATIBLE("selector_incompatible"),

    /** The family's native name validator refused `name` (never a repository lookup). */
    NAME_INVALID("name_invalid"),

    /** `id` is not the canonical 36-character lower-case UUID form. */
    ID_INVALID("id_invalid"),

    /** `version` is absent where required, non-decimal, signed, zero-led, `current` or outside 1..2147483647. */
    VERSION_INVALID("version_invalid"),

    /** A value is longer than 200 characters or not UTF-8 — checked before any grammar. */
    VALUE_TOO_LONG("value_too_long"),
}
