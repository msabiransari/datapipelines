package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * A template's kind — `sql`, `html`, or one of the two transform types `jsonata` /
 * `javascript` (template-hierarchy-design §5, since 046; the transform types since 7b,
 * transform-nodes design §2.1; enums.md §6A).
 *
 * The type is chosen at template **create** and is immutable across every version of the
 * template (§5.3): there is no legitimate migration of a body between the SQL and HTML
 * worlds, and allowing one would let a single identity flip meaning under every pinned
 * reference's feet. Enforcement lives at the write paths (`template.validation.type_immutable`)
 * and, structurally, in the `chk_type_dialect` database invariant.
 *
 * The type conditions the rest of the contract: a `sql` template **requires** a `dialect`
 * and renders through the escaping-free engine configuration; an `html` template must have
 * **no** dialect (`template.validation.dialect_not_allowed`) and renders through a second,
 * auto-escaping configuration (§6). Pipeline nodes may reference only `sql` templates
 * (`pipeline.validation.template_type_mismatch`, §7).
 *
 * A transform type renders nothing: the body is an expression evaluated as a pure function
 * of its input object by the `modules/scripting` engine seam, `engine` is `none`, `dialect`,
 * `imports` and `is_library` are refused, and the version carries the contract / invariants /
 * tests blocks (transform-nodes design §2.2). `javascript` is refused at save until round two
 * (`transform.js.unavailable`) — the enum value exists so the wire vocabulary and the CHECK
 * constraints are final.
 */
enum class TemplateType(
    /** The wire value (`type` field of the Template JSON, `type` column of `template_versions`). */
    @JsonValue val wire: String,
) {
    SQL("sql"),
    HTML("html"),
    JSONATA("jsonata"),
    JAVASCRIPT("javascript"),
    ;

    /** A transform type evaluates its body as a pure function; it never renders through Freemarker. */
    val isTransform: Boolean get() = this == JSONATA || this == JAVASCRIPT

    companion object {
        /** All wire values, in declaration order — the `supported` detail of validation failures. */
        val WIRE_VALUES: List<String> = entries.map { it.wire }

        /** Binds a wire value, or null when it is none of them — callers own the refusal code. */
        @JsonCreator
        fun fromWire(wire: String): TemplateType? = entries.firstOrNull { it.wire == wire }
    }
}
