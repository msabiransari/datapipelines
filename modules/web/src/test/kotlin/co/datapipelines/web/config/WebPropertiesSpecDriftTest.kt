package co.datapipelines.web.config

import co.datapipelines.web.TestRepoFiles
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The standing guard that the `@ConfigurationProperties` defaults equal configuration.md's
 * documented defaults — that document is the single authority for config keys (§1), and a binding
 * class that quietly disagrees with it is a second authority.
 *
 * Every key this module binds is listed explicitly: a default edited in the doc without a
 * corresponding property change fails here, and a property default edited without the doc fails
 * here too.
 */
class WebPropertiesSpecDriftTest {
    private val documented: Map<String, String> by lazy {
        TestRepoFiles
            .read(TestRepoFiles.CONFIG_SPEC_PATH)
            .lineSequence()
            .mapNotNull { ROW_REGEX.find(it) }
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `sse property defaults match configuration-md section 3-6`() {
        val props = SseProperties()
        documented.getValue("datapipelines.sse.heartbeat-interval-seconds") shouldBe props.heartbeatIntervalSeconds.toString()
        documented.getValue("datapipelines.sse.disconnect-grace-seconds") shouldBe props.disconnectGraceSeconds.toString()
        documented.getValue("datapipelines.sse.max-streams-per-user") shouldBe props.maxStreamsPerUser.toString()
    }

    @Test
    fun `rate-limit property defaults match configuration-md section 3-7`() {
        val props = RateLimitProperties()
        documented.getValue("datapipelines.rate-limit.requests-per-second") shouldBe props.requestsPerSecond.toString()
        documented.getValue("datapipelines.rate-limit.requests-per-minute") shouldBe props.requestsPerMinute.toString()
    }

    @Test
    fun `result property defaults match configuration-md section 3-5`() {
        val props = ResultProperties()
        documented.getValue("datapipelines.result.ttl-default-seconds") shouldBe props.ttlDefaultSeconds.toString()
        documented.getValue("datapipelines.result.ttl-min-seconds") shouldBe props.ttlMinSeconds.toString()
        documented.getValue("datapipelines.result.ttl-max-seconds") shouldBe props.ttlMaxSeconds.toString()
        documented.getValue("datapipelines.result.max-size-bytes") shouldBe props.maxSizeBytes.toString()
        documented.getValue("datapipelines.result.page-size-rows") shouldBe props.pageSizeRows.toString()
        documented.getValue("datapipelines.result.page-max-rows") shouldBe props.pageMaxRows.toString()
    }

    @Test
    fun `executor property defaults match configuration-md section 3-2`() {
        val props = ExecutorProperties()
        documented.getValue("datapipelines.executor.max-parallel-nodes") shouldBe props.maxParallelNodes.toString()
        documented.getValue("datapipelines.executor.max-concurrent-executions-per-user") shouldBe
            props.maxConcurrentExecutionsPerUser.toString()
        documented.getValue("datapipelines.executor.max-concurrent-executions-per-instance") shouldBe
            props.maxConcurrentExecutionsPerInstance.toString()
        // The deprecated alias (050/R2): documented with default `unset` — the nullable
        // binding MUST stay unset so application.yml defines it nowhere.
        documented.getValue("datapipelines.executor.max-concurrent-executions-global") shouldBe "unset"
        @Suppress("DEPRECATION")
        props.maxConcurrentExecutionsGlobal shouldBe null
        documented.getValue("datapipelines.executor.node-query-timeout-seconds") shouldBe props.nodeQueryTimeoutSeconds.toString()
        documented.getValue("datapipelines.executor.execution-timeout-seconds") shouldBe props.executionTimeoutSeconds.toString()
    }

    @Test
    fun `pipelines property defaults match configuration-md section 3-16`() {
        val props = PipelineProperties()
        documented.getValue("datapipelines.pipelines.max-composition-depth") shouldBe props.maxCompositionDepth.toString()
    }

    @Test
    fun `staging and idempotency defaults match configuration-md sections 3-3 and 3-8`() {
        val staging = StagingH2Properties()
        documented.getValue("datapipelines.staging.h2.mode") shouldBe staging.mode
        documented.getValue("datapipelines.staging.h2.max-memory-mb") shouldBe staging.maxMemoryMb.toString()
        documented.getValue("datapipelines.staging.h2.insert-batch-size") shouldBe staging.insertBatchSize.toString()
        documented.getValue("datapipelines.staging.h2.result-batch-size") shouldBe staging.resultBatchSize.toString()
        documented.getValue("datapipelines.staging.h2.query-timeout-seconds") shouldBe staging.queryTimeoutSeconds.toString()
        documented.getValue("datapipelines.idempotency.ttl-seconds") shouldBe IdempotencyProperties().ttlSeconds.toString()
    }

    @Test
    fun `executions property defaults match configuration-md section 3-11`() {
        val props = ExecutionsProperties()
        documented.getValue("datapipelines.executions.stale-timeout-minutes") shouldBe props.staleTimeoutMinutes.toString()
        // Bound since 050/T60 — the retention job enforces it hourly.
        documented.getValue("datapipelines.executions.event-retention-days") shouldBe props.eventRetentionDays.toString()
        // 057: the failure record's detail level — enum, so the wire value IS the documented one.
        documented.getValue("datapipelines.executions.error-detail") shouldBe props.errorDetail.wire
    }

    @Test
    fun `org property defaults match configuration-md section 3-21`() {
        val props = OrgProperties()
        documented.getValue("datapipelines.org.currency.name") shouldBe props.currency.name
        // The `$` symbol is why ROW_REGEX's default cell admits it: a currency symbol IS the
        // default, and a guard that could not read it would silently drop the key from the
        // parse and turn its assertion into a `getValue` failure nobody would read as drift.
        documented.getValue("datapipelines.org.currency.symbol") shouldBe props.currency.symbol
        documented.getValue("datapipelines.org.fiscal-start-date") shouldBe props.fiscalStartDate
        documented.getValue("datapipelines.org.week-start") shouldBe props.weekStart
        documented.getValue("datapipelines.org.timezone") shouldBe props.timezone
    }

    private companion object {
        /**
         * A full `| \`datapipelines.*\` | \`default\` | description |` row of the §3 tables
         * (hardened 025 D3): the description cell runs to end-of-line and may contain
         * ESCAPED pipes (`\|`), never a bare one — so a row whose column count changes
         * does not match at all and its key DISAPPEARS from the parse (the `getValue`
         * calls then fail loudly), instead of the old behavior of silently reading
         * whichever cell happened to sit in the default position.
         */
        val ROW_REGEX =
            Regex("""^\|\s*`(datapipelines\.[a-z0-9.\-]+)`\s*\|\s*`?([A-Za-z0-9$\-]+)`?\s*\|(?:[^|\n]|\\\|)*\|$""")
    }
}
