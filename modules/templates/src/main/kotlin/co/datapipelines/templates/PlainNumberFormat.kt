package co.datapipelines.templates

import freemarker.core.Environment
import freemarker.core.TemplateFormatUtil
import freemarker.core.TemplateNumberFormat
import freemarker.core.TemplateNumberFormatFactory
import freemarker.template.TemplateModelException
import freemarker.template.TemplateNumberModel
import java.math.BigDecimal
import java.util.Locale

/**
 * Renders `${number}` exactly as templates.md §4.4 specifies — **plain, at the declared scale**.
 *
 * ## Why a custom format is needed
 *
 * §4.4 requires `DECIMAL(p,s)` / `BIGDECIMAL(p,s)` to render as "plain decimal string with the
 * declared scale". None of Freemarker's built-in number formats does that: `"computer"` and
 * every `CFormat` normalize the value and drop trailing zeros, so a `BIGDECIMAL(12,2)` holding
 * `1000.00` renders as `1000` — verified against the pinned artifact this session, not assumed.
 * Scale is information a SQL author declared on purpose, and a rendered literal that silently
 * loses it is the engine editing the author's SQL.
 *
 * `Configurable.setCustomNumberFormats` + `numberFormat = "@plain"` is the documented extension
 * point for this (Freemarker 2.3.24+), and it is the *only* knob involved: no security setting
 * of [FreemarkerConfigFactory] is touched or relaxed to get it.
 *
 * ## What each canonical type renders as
 *
 * | Context value | Rendered |
 * |---|---|
 * | [BigDecimal] | `toPlainString()` — declared scale preserved (`1000.00`) |
 * | [Double] / [Float] | plain decimal, never scientific notation (`10000000000.0`, not `1.0E10`) |
 * | integral types | the digits (`42`, `9223372036854775807`) |
 *
 * Numbers deliberately stay *numbers* in the render context rather than being pre-formatted to
 * strings ([RenderContextNormalizer]), so `<#if amount gt 100>` and template arithmetic keep
 * working; this format governs only how one is written out.
 *
 * **On bound parameters (042):** a declared parameter referenced as `:name` never passes
 * through this format — the driver receives the typed object and formats it itself, which is
 * half the point of binding. This format now governs only *interpolated* numbers (structure,
 * and templates stored before the 042 migration), which is why it stays in place.
 *
 * **On templates.md Appendix A (settled, no action):** the worked example writes
 * `${min_total?c}` and shows `1000.00`. The `?c` built-in bypasses `numberFormat` entirely and
 * uses the `CFormat`, which drops trailing zeros in every published implementation, and `?c`
 * applied to a *string* quotes it (`"1000.00"`) — so no supported configuration makes that exact
 * illustration render as documented. §4.4's table is the normative statement and `${min_total}`
 * satisfies it exactly; the appendix is illustrative. Nothing here is waiting on a decision.
 */
internal object PlainNumberFormatFactory : TemplateNumberFormatFactory() {
    /** The `numberFormat` value that selects this factory. */
    const val NAME = "plain"

    override fun get(
        params: String,
        locale: Locale,
        env: Environment,
    ): TemplateNumberFormat {
        TemplateFormatUtil.checkHasNoParameters(params)
        return PlainNumberFormat
    }
}

/** The format itself — stateless, locale-independent, and therefore a singleton. */
internal object PlainNumberFormat : TemplateNumberFormat() {
    @Throws(TemplateModelException::class)
    override fun formatToPlainText(numberModel: TemplateNumberModel): String = format(numberModel.asNumber)

    /** Locale-independent by construction: no grouping separators, no locale decimal mark. */
    override fun isLocaleBound(): Boolean = false

    override fun getDescription(): String = "plain number, declared scale preserved (templates.md §4.4)"

    private fun format(number: Number?): String =
        when (number) {
            null -> ""

            is BigDecimal -> number.toPlainString()

            // Double/Float print scientific notation past ~1e7 (`1.0E10`); §4.4 says plain.
            // Routing through BigDecimal's own parse of that text keeps the value identical and
            // only changes the notation.
            is Double, is Float -> BigDecimal(number.toString()).toPlainString()

            else -> number.toString()
        }
}
