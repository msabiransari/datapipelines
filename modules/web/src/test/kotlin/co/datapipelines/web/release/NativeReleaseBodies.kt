package co.datapipelines.web.release

/**
 * #877 §E.3 — the draft bodies of [NativeReleaseWorld]'s pipeline `test/monthly_revenue` v2, as the native release's unit
 * test (`PipelineReleaseServiceTest`) and `PipelineServiceIntegrationTest` wrote them: one DQL node pinning
 * `test/t.sql@2`, and its variants (a tempdb output, a SCRIPT caller, a release check, three nodes pinning two templates,
 * a CALCULATOR beside a pin). The parity test and the coordinator's release cases release exactly these.
 */
internal object NativeReleaseBodies {
    const val DRAFT =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}]}"""

    /** No caller node: the only node writes to tempdb. */
    const val TEMPDB =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},""" +
            """"output":{"target":"tempdb","table":"stg_revenue"},"depends_on":[]}]}"""

    /** A SCRIPT caller node: the pinned contract declares the columns. */
    const val SCRIPT =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"SCRIPT","source":"pg","template":{"id":"test/t.sql","version":2},""" +
            """"output":{"target":"caller"},"depends_on":[]}]}"""

    /** [DRAFT] with one release check (140). */
    const val CHECKED =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]}],""" +
            """"checks":[{"id":"revenue_matches","name":"Revenue matches the ledger","datasource":"pg",""" +
            """"sql":"SELECT 1","expected":{"kind":"value","value":1.0}}]}"""

    /** Three nodes pinning two templates: `test/t.sql@2` twice, then `test/u.sql@1`. */
    const val TWO_TEMPLATES =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"n1","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]},""" +
            """{"id":"n2","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":[]},""" +
            """{"id":"n3","type":"DQL","source":"pg","template":{"id":"test/u.sql","version":1},"depends_on":[]}]}"""

    /** [TWO_TEMPLATES] with one release check. */
    val CHECKED_TWO_TEMPLATES =
        TWO_TEMPLATES.dropLast(1) +
            ""","checks":[{"id":"revenue_matches","name":"Revenue matches","datasource":"pg",""" +
            """"sql":"SELECT 1","expected":{"kind":"value","value":1.0}}]}"""

    /** `PipelineServiceIntegrationTest`'s calculator case: a CALCULATOR node has no template pin to ask about. */
    const val CALCULATOR =
        """{"schema_version":1,"name":"test/monthly_revenue","display_name":"M","description":"d",""" +
            """"parameters":{},"settings":{"tempdb":{"engine":"H2"}},""" +
            """"nodes":[{"id":"fiscal_q","type":"CALCULATOR","kind":"fiscal_quarter","depends_on":[]},""" +
            """{"id":"reads","type":"DQL","source":"pg","template":{"id":"test/t.sql","version":2},"depends_on":["fiscal_q"]}]}"""

    /** One result column, as a qualifying execution records it. */
    const val CALLER_RECORD = """[{"name":"a","type":"STRING","nullable":true}]"""
}
