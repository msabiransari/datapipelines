package co.datapipelines.browser

import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * 454 (#454) — the settle wait the version-menu walk uses (`awaitSettledInViewport`, shared with
 * ModalBackBrowserTest) completes only after a STABLE two-frame observation, and retries every
 * unstable one before it.
 *
 * The probe sits on the real ⋯ summary of a real Versions tab. It records every rectangle the
 * wait's observations read — the browser's own `getBoundingClientRect`, returned unchanged, with
 * the animation-frame number it was read in — so the record lives OUTSIDE the wait and does not
 * depend on how the wait decides. For the first `FORCED_UNSTABLE` observations it also moves the
 * summary (a real CSSOM `translate`) on the frame after the observation's first sample, scheduled
 * FROM that sample, so the second sample two frames later reads a different rectangle by
 * construction rather than by timing. After those moves the geometry holds still. The oracle
 * reads the recorded pairs, never the wait's own answer: the forced pairs differ, and the last
 * pair before the wait returned is unchanged.
 *
 * Measured with the original wait restored in the helper (a `waitForFunction` whose predicate
 * returned the two-frame Promise; playwright-core 1.62.1): the probe recorded ONE observation —
 * top 308.0625 on frame 0, the move on frame 1, top 314.0625 on frame 2 — and the wait returned,
 * red on the completion clue. The awaited, retried observation passes.
 */
class ModalScrollStabilityBrowserTest : BrowserSuite() {
    @Test
    fun `the settle wait retries forced unstable observations and completes only after a stable pair`() {
        val admin = seedLocalUser(uniqueEmail("mss-" + generatedPassword("u").take(8)), generatedPassword("pw"), mustChange = false)
        login(admin.email, admin.oneTimePassword)
        page.waitForURL("**/dashboard")
        val pipelineId = seedPipeline("test/mss-" + generatedPassword("p").take(8).lowercase(), admin.email)
        page.navigate("$baseUrl/pipelines/$pipelineId?tab=versions")
        page.waitForSelector(".pe-root")
        page.locator("#pe-pane-versions tr[data-version-row]").first().waitFor()
        // Instant, not smooth: the only motion during the wait is the probe's own.
        page.locator(SUMMARY).first().evaluate("el => el.scrollIntoView({ block: 'center', behavior: 'instant' })")

        page.evaluate(INSTALL_PROBE, listOf(SUMMARY, FORCED_UNSTABLE))
        val record =
            try {
                awaitSettledInViewport(page, SUMMARY)
                // Read at completion: everything the wait observed before it returned.
                page.evaluate(READ_PROBE) as Map<*, *>
            } finally {
                page.evaluate(REMOVE_PROBE, SUMMARY)
            }

        val reads = (record["reads"] as List<*>).map { sample(it as Map<*, *>) }
        val moves = (record["moves"] as List<*>).map { it as Map<*, *> }
        withClue("every observation the wait made completed both samples (reads=$reads)") {
            reads.isNotEmpty() shouldBe true
            reads.size % 2 shouldBe 0
        }
        val observations = reads.chunked(2).mapIndexed { index, (first, second) -> Observation(index, first, second) }
        // The run's evidence in the XML's system-out, green or red: what the wait observed, in order.
        println("454 settle observations: $observations; probe moves: $moves; frame at read: ${record["frame"]}")
        withClue("the wait's completed observations as the probe recorded them: $observations; probe moves: $moves") {
            observations.forEach { observation ->
                withClue("observation ${observation.index} compares samples two animation frames apart") {
                    (observation.second.frame - observation.first.frame) shouldBeGreaterThanOrEqual 2
                }
            }
            observations.take(FORCED_UNSTABLE).forEach { observation ->
                withClue("forced observation ${observation.index}: the probe moved the summary between its samples") {
                    moves.any { movedBetween(it, observation) } shouldBe true
                    observation.unchanged shouldBe false
                }
            }
            withClue("the wait completed only after an unchanged pair that followed every forced unstable one") {
                observations.last().unchanged shouldBe true
                observations.size shouldBeGreaterThan FORCED_UNSTABLE
            }
        }
        withClue("the probe left nothing behind on the summary or the window") {
            page.evaluate(PROBE_RESIDUE, SUMMARY) shouldBe mapOf("probe" to false, "ownRectReader" to false, "styleAttr" to false)
        }
    }

    private data class Sample(
        val frame: Int,
        val top: Double,
        val bottom: Double,
    )

    private data class Observation(
        val index: Int,
        val first: Sample,
        val second: Sample,
    ) {
        val unchanged: Boolean get() = first.top == second.top && first.bottom == second.bottom
    }

    private fun sample(raw: Map<*, *>) =
        Sample(
            frame = (raw["frame"] as Number).toInt(),
            top = (raw["top"] as Number).toDouble(),
            bottom = (raw["bottom"] as Number).toDouble(),
        )

    /** A move the probe made for [observation], on a frame after its first sample and no later than its second. */
    private fun movedBetween(
        move: Map<*, *>,
        observation: Observation,
    ): Boolean {
        val frame = (move["frame"] as Number).toInt()
        return (move["observation"] as Number).toInt() == observation.index &&
            frame > observation.first.frame &&
            frame <= observation.second.frame
    }

    /** One pipeline (one version) in the `default` workspace — the versions panel's ⋯ menu needs nothing more. */
    private fun seedPipeline(
        name: String,
        ownerEmail: String,
    ): UUID {
        val pipelineId = UUID.randomUUID()
        DriverManager.getConnection(SharedBrowserE2e.jdbcUrl, SharedBrowserE2e.username, SharedBrowserE2e.password).use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO pipelines (id, workspace_id, name, display_name, owner_id, current_version)
                    SELECT ?, 'defa0000-0000-0000-0000-000000000001', ?, ?, id, 1 FROM users WHERE email = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, pipelineId)
                    statement.setString(2, name)
                    statement.setString(3, name)
                    statement.setString(4, ownerEmail)
                    withClue("seeded the pipeline row") { statement.executeUpdate() shouldBe 1 }
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO pipeline_versions (pipeline_id, version, body_json, created_by, body_hash)
                    SELECT ?, 1, '{}'::jsonb, id, 'seeded-fixture-hash' FROM users WHERE email = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, pipelineId)
                    statement.setString(2, ownerEmail)
                    withClue("seeded the pipeline's version row") { statement.executeUpdate() shouldBe 1 }
                }
        }
        return pipelineId
    }

    private companion object {
        const val SUMMARY = "#pe-pane-versions details.tplx-vmenu > summary"

        /** Observations the probe makes unstable before the geometry holds still. */
        const val FORCED_UNSTABLE = 2

        /**
         * Records each rectangle read of the summary and, for the first `forced` observations
         * (an even read opens one: the wait's samples come in pairs), moves the summary on the
         * next frame — registered before the wait's own frame callback, so the move lands
         * between the two samples. A frame ticker numbers every read.
         */
        val INSTALL_PROBE =
            """
            ([sel, forced]) => {
              const el = document.querySelector(sel);
              if (!el) throw new Error('no element matches ' + sel);
              const probe = { reads: [], moves: [], frame: 0, live: true, hadStyle: el.hasAttribute('style') };
              const tick = () => { if (!probe.live) return; probe.frame += 1; requestAnimationFrame(tick); };
              requestAnimationFrame(tick);
              el.getBoundingClientRect = function () {
                const rect = Element.prototype.getBoundingClientRect.call(this);
                const index = probe.reads.length;
                probe.reads.push({ frame: probe.frame, top: rect.top, bottom: rect.bottom });
                const observation = index / 2;
                if (index % 2 === 0 && observation < forced) {
                  requestAnimationFrame(() => {
                    if (!probe.live) return;
                    probe.moves.push({ observation, frame: probe.frame });
                    el.style.translate = '0 ' + (6 * probe.moves.length) + 'px';
                  });
                }
                return rect;
              };
              window.__dpSettleProbe = probe;
            }
            """.trimIndent()

        val READ_PROBE =
            """
            () => {
              const p = window.__dpSettleProbe;
              return { reads: p.reads.slice(), moves: p.moves.slice(), frame: p.frame };
            }
            """.trimIndent()

        val REMOVE_PROBE =
            """
            (sel) => {
              const p = window.__dpSettleProbe;
              const el = document.querySelector(sel);
              if (p) p.live = false;
              if (el) {
                delete el.getBoundingClientRect;
                el.style.removeProperty('translate');
                if (p && !p.hadStyle && el.getAttribute('style') === '') el.removeAttribute('style');
              }
              delete window.__dpSettleProbe;
            }
            """.trimIndent()

        val PROBE_RESIDUE =
            """
            (sel) => {
              const el = document.querySelector(sel);
              return {
                probe: window.__dpSettleProbe !== undefined,
                ownRectReader: Object.prototype.hasOwnProperty.call(el, 'getBoundingClientRect'),
                styleAttr: el.hasAttribute('style'),
              };
            }
            """.trimIndent()
    }
}
