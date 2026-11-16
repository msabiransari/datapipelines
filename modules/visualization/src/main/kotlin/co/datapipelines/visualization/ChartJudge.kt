package co.datapipelines.visualization

/**
 * The rendered-state port (the spec's §11.3 step 5; js-rendered charts record §4.12): the chart engine's CANONICAL
 * render of one chart case — 640 × 400 ([ChartFrame.CANONICAL]), the server's width table, nothing hidden. The
 * mechanical check judges a chart case's `rendered`, `mark_count`, `no_data` and `text_visible` from it ([ChartJudge])
 * and records its state on the case's report.
 *
 * Two implementations, chosen by the wiring from the JavaScript runtime's own answer (record §6): [EngineRenderedStateCheck]
 * where the runtime is available, [NOT_AVAILABLE] where it is not — every chart case then records `not_available` and
 * its four assertions are listed as unjudged, never failures. A save never reaches either (O-12).
 */
fun interface RenderedStateCheck {
    fun render(input: ChartInput): CanonicalRender

    /** False only for [NOT_AVAILABLE]: a chart case that was never rendered then records `not_available`. */
    val available: Boolean get() = true

    companion object {
        /** The host without the JavaScript runtime: nothing is rendered, nothing is claimed. */
        val NOT_AVAILABLE: RenderedStateCheck =
            object : RenderedStateCheck {
                override fun render(input: ChartInput): CanonicalRender = CanonicalRender.NotAvailable

                override val available: Boolean = false

                override fun toString(): String = "RenderedStateCheck.NOT_AVAILABLE"
            }
    }
}

/** [RenderedStateCheck] over the [ChartEngine] port at the judge's fixed frame. */
class EngineRenderedStateCheck(
    val engine: ChartEngine,
) : RenderedStateCheck {
    override fun render(input: ChartInput): CanonicalRender =
        try {
            CanonicalRender.Drawn(engine.render(input, ChartFrame.CANONICAL))
        } catch (failed: ChartEngineFailure) {
            CanonicalRender.Failed(failed.limit, failed.message ?: "the chart engine gave no result")
        }
}

/** What the canonical render of one case gave; [state] is the string recorded on the case's report. */
sealed interface CanonicalRender {
    val state: String

    /** The runtime is not available on this host. */
    data object NotAvailable : CanonicalRender {
        override val state: String = NOT_AVAILABLE_STATE
    }

    /** The engine's output; [state] is the engine's own (`rendered`, `no-data`, `refused`, `too-small`). */
    data class Drawn(
        val output: ChartOutput,
    ) : CanonicalRender {
        override val state: String get() = output.state
    }

    /** The evaluation threw or hit a limit ([limit] names it, null for a failure of the engine or its host): no result. */
    data class Failed(
        val limit: String?,
        val message: String,
    ) : CanonicalRender {
        override val state: String = NOT_RENDERED_STATE
    }

    companion object {
        const val NOT_AVAILABLE_STATE = "not_available"
        const val NOT_RENDERED_STATE = "not_rendered"
    }
}

/**
 * §4.12's table over one case's [CanonicalRender]: each assertion kind judged from the field the table names. A
 * render that is [CanonicalRender.NotAvailable] leaves every chart assertion unjudged; a [CanonicalRender.Failed] one
 * already failed the case (the engine's failure, once) and is not judged again.
 */
internal object ChartJudge {
    sealed interface Verdict {
        data object Holds : Verdict

        data class Fails(
            val message: String,
        ) : Verdict

        data object Unjudged : Verdict

        /** The case already failed (its rows, or the engine): nothing is judged twice. */
        data object AlreadyFailed : Verdict
    }

    private fun drawn(render: CanonicalRender?): Pair<ChartOutput?, Verdict?> =
        when (render) {
            null, is CanonicalRender.Failed -> null to Verdict.AlreadyFailed
            CanonicalRender.NotAvailable -> null to Verdict.Unjudged
            is CanonicalRender.Drawn -> render.output to null
        }

    /** `rendered`: `state` is `rendered` with `summary.marks` ≥ 1, or `no-data` when the case has no rows. */
    fun rendered(
        render: CanonicalRender?,
        rows: Int,
    ): Verdict {
        val (output, early) = drawn(render)
        if (output == null) return early ?: Verdict.AlreadyFailed
        return when {
            output.state == ChartOutput.RENDERED && output.marks >= 1 -> {
                Verdict.Holds
            }

            output.state == ChartOutput.NO_DATA && rows == 0 -> {
                Verdict.Holds
            }

            output.state == ChartOutput.NO_DATA -> {
                Verdict.Fails(
                    "The chart drew no mark at $FRAME (state no-data) over the case's $rows rows.",
                )
            }

            else -> {
                Verdict.Fails("The chart was not drawn at $FRAME (${describe(output)}).")
            }
        }
    }

    /** `mark_count`: `summary.marks` when `state` is `rendered` or `no-data`; `refused` and `too-small` fail it. */
    fun markCount(
        render: CanonicalRender?,
        equals: Int?,
    ): Verdict {
        val (output, early) = drawn(render)
        if (output == null) return early ?: Verdict.AlreadyFailed
        return when {
            output.state != ChartOutput.RENDERED && output.state != ChartOutput.NO_DATA -> {
                Verdict.Fails("The chart was not drawn at $FRAME (${describe(output)}); the case asserts $equals marks.")
            }

            equals != null && equals != output.marks -> {
                Verdict.Fails("The case's rows draw ${output.marks} marks; the case asserts $equals.")
            }

            else -> {
                Verdict.Holds
            }
        }
    }

    /** `no_data`: `state` is `no-data` (zero rows, or every measure null). */
    fun noData(render: CanonicalRender?): Verdict {
        val (output, early) = drawn(render)
        if (output == null) return early ?: Verdict.AlreadyFailed
        return when (output.state) {
            ChartOutput.NO_DATA -> Verdict.Holds
            ChartOutput.RENDERED -> Verdict.Fails("The case expects the empty state; its rows draw ${output.marks} marks.")
            else -> Verdict.Fails("The case expects the empty state; the chart was not drawn at $FRAME (${describe(output)}).")
        }
    }

    /** `text_visible`: a `text` primitive whose SHOWN text contains the string. */
    fun textVisible(
        render: CanonicalRender?,
        text: String?,
    ): Verdict {
        val (output, early) = drawn(render)
        if (output == null) return early ?: Verdict.AlreadyFailed
        return if (text != null && output.shownTexts.any { text in it }) {
            Verdict.Holds
        } else {
            Verdict.Fails("The text is not in any text the chart shows at $FRAME.")
        }
    }

    private fun describe(output: ChartOutput): String = "state ${output.state}" + (output.refusal?.let { ", $it" } ?: "")

    private val FRAME = "${ChartFrame.CANONICAL.width} × ${ChartFrame.CANONICAL.height}"
}
