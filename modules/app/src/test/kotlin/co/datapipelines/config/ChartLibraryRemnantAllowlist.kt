package co.datapipelines.config

/**
 * The allowlist of [ChartLibraryRemnantGuardTest] (#988, the js-rendered charts record's A11): every hit of the
 * guard's pattern set must match one entry here, and every entry must match at least one hit (an entry that
 * matches nothing is red — remove it). There are exactly four kinds, each entry carrying its reason; a fifth kind
 * is a change to the record, not to this file.
 */
internal object ChartLibraryRemnantAllowlist {
    /** The four kinds of the record's A11, numbered as there. */
    enum class Kind(
        val number: Int,
    ) {
        GUARD_SELF(1),
        RETIRED_KIND_TEST(2),
        CHANGE_LOG(3),
        HISTORICAL_SPEC(4),
    }

    /** One allowlist entry: which hits it admits, and why. */
    sealed interface Entry {
        val kind: Kind
        val reason: String

        /** True when this entry admits the hit on [line] of [path]; [inChangeLog] is the line's Change Log membership. */
        fun admits(
            path: String,
            line: String,
            inChangeLog: Boolean,
        ): Boolean
    }

    /** Every line of one tracked file. */
    data class WholeFile(
        val path: String,
        override val kind: Kind,
        override val reason: String,
    ) : Entry {
        override fun admits(
            path: String,
            line: String,
            inChangeLog: Boolean,
        ): Boolean = path == this.path
    }

    /** The lines of one tracked file that contain [fragment], and no other line of it. */
    data class LineInFile(
        val path: String,
        val fragment: String,
        override val kind: Kind,
        override val reason: String,
    ) : Entry {
        override fun admits(
            path: String,
            line: String,
            inChangeLog: Boolean,
        ): Boolean = path == this.path && fragment in line
    }

    /** A line inside a product doc's Change Log appendix, found by its heading and never by file. */
    data class ChangeLogAppendix(
        override val kind: Kind,
        override val reason: String,
    ) : Entry {
        override fun admits(
            path: String,
            line: String,
            inChangeLog: Boolean,
        ): Boolean = inChangeLog
    }

    private const val GUARD_DIR = "modules/app/src/test/kotlin/co/datapipelines/config/"

    val ENTRIES: List<Entry> =
        listOf(
            WholeFile(
                GUARD_DIR + "ChartLibraryRemnantGuardTest.kt",
                Kind.GUARD_SELF,
                "the guard's own source: it spells the pattern set and its controls",
            ),
            WholeFile(
                GUARD_DIR + "ChartLibraryRemnantAllowlist.kt",
                Kind.GUARD_SELF,
                "this allowlist: the retired-kind entry below quotes the one string that test submits",
            ),
            LineInFile(
                "modules/visualization/src/test/kotlin/co/datapipelines/visualization/VisualizationReaderTest.kt",
                """put("kind", "plotly")""",
                Kind.RETIRED_KIND_TEST,
                "record O-14: the regression test submits the retired kind's one string and expects the " +
                    "ordinary unknown-kind refusal; only that line, never the rest of the file",
            ),
            ChangeLogAppendix(
                Kind.CHANGE_LOG,
                "a product doc's Change Log rows are history and are never rewritten; a line above the heading is red",
            ),
            WholeFile(
                "docs/superpowers/specs/2026-09-25-dashboard-authoring-design-draft.md",
                Kind.HISTORICAL_SPEC,
                "the historical dashboard design draft, superseded (its first lines say so); its text stays as history",
            ),
            WholeFile(
                "docs/superpowers/specs/2026-09-28-dashboard-implementation-spec.md",
                Kind.HISTORICAL_SPEC,
                "the historical dashboard implementation spec, superseded (its first lines say so); its text stays as history",
            ),
        )
}
