package co.datapipelines.templates

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.TemplateRef
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.DatapipelinesException
import freemarker.core.InvalidReferenceException
import freemarker.template.Configuration
import freemarker.template.TemplateException
import freemarker.template.TemplateNotFoundException
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** The result of a guarded render — the shape the dry-renderer classifies without throwing. */
internal sealed interface RenderOutcome {
    data class Success(
        val sql: String,
    ) : RenderOutcome

    /** A Freemarker variable the context did not supply — the §12.6 undeclared-variable case. */
    data class UndefinedVariable(
        val variable: String?,
        val detail: String,
    ) : RenderOutcome

    /**
     * The reference — or a library in its closure — resolved to no stored version. Kept separate
     * from [Failed] because templates.md §8.2 gives it its own code at execution time
     * (`pipeline.node.template_not_found`, not `template_render_failed`).
     */
    data class NotFound(
        val detail: String,
    ) : RenderOutcome

    /** Any other render failure — bad built-in, timeout, size cap, unresolvable macro. */
    data class Failed(
        val detail: String,
    ) : RenderOutcome
}

/**
 * Raised when [TemplateEngine.render] fails at **run** time (templates.md §8.2).
 *
 * Carries `pipeline.node.template_render_failed` (§13.4, HTTP 500) by default — the executor-time
 * code, deliberately distinct from the save-time `pipeline.validation.template_render_failed` a
 * pipeline's dry-render raises. [code] is overridden for exactly one case §8.2 separates out: a
 * reference that resolves to no stored version is `pipeline.node.template_not_found`.
 *
 * The dry-render path never throws this; it maps the same underlying [RenderOutcome] into a
 * [DryRenderOutcome][co.datapipelines.pipeline.DryRenderOutcome].
 */
class TemplateRenderException(
    detail: String,
    val ref: TemplateRef,
    code: String = PipelineErrorCodes.Node.TEMPLATE_RENDER_FAILED,
) : DatapipelinesException(
        code = code,
        message = "Rendering template '${ref.key.truncateForError()}' failed: $detail",
        details = mapOf("template" to ref.key.truncateForError(), "detail" to detail),
    )

/**
 * Wraps Freemarker under the templates.md §4.3 configuration and owns the two render guards.
 *
 * Since 046 (template-hierarchy-design §6) one engine holds **two** hardened
 * [Configuration]s over the same [RegistryTemplateLoader]: the `sql` one (exactly the
 * pre-046 configuration, no output escaping) and the `html` one (identical hardening plus
 * `HTMLOutputFormat` and forced auto-escaping). [renderNow] selects between them by the
 * resolved version's `type` — the single dispatch point, so no caller of `render`/`execute`
 * can send an `html` body through the escaping-free configuration by forgetting to ask.
 * The loader itself is type-blind (it resolves `{name}@{version}` regardless of type), and a
 * version's key only ever loads through one of the two configurations because the type is
 * immutable per template (§5.3) — the two caches cannot disagree about a key.
 *
 * Both configurations share the watchdog pool and the render budget below; nothing about the
 * guards is per-type.
 *
 * ## The render guards, and why they are shaped this way (§4.3)
 *
 * Freemarker has no timeout setting, so each render runs on a worker the watchdog interrupts at
 * `renderTimeoutMs`. Two facts, both verified against the pinned 2.3.34 jar, decide the rest:
 *  - **`Thread.interrupt()` alone does not abort a render.** A runaway `<#list>` outlived
 *    `cancel(true)` indefinitely. [InterruptibleConfiguration] registers Freemarker's thread-
 *    interruption post-processor on every template it loads, which is what makes the interrupt
 *    land; without it a timeout would leak one core-burning thread per runaway.
 *  - **The output cap does not bound heap.** `<#assign s = s + s>` reaches gigabytes without
 *    writing a byte, so [BoundedWriter] cannot see it. The bounded worker pool below is what
 *    bounds that: a render that ignores everything still occupies one of a fixed number of
 *    slots, and the next one is rejected rather than queued forever.
 *
 * The pool is therefore **bounded** with a bounded queue and an abort policy — never
 * `newCachedThreadPool`, which grows a thread per concurrent render and so converts one
 * uninterruptible template into an unbounded thread leak.
 */
class TemplateEngine(
    private val registry: TemplateRegistry,
    cacheSize: Int,
    private val renderTimeoutMs: Long,
    private val maxOutputChars: Long,
) : Closeable {
    private val loader: RegistryTemplateLoader = RegistryTemplateLoader(registry)

    private val sqlConfiguration: Configuration = FreemarkerConfigFactory.create(loader, cacheSize)

    private val htmlConfiguration: Configuration = FreemarkerConfigFactory.createHtml(loader, cacheSize)

    private val workers =
        ThreadPoolExecutor(
            MAX_CONCURRENT_RENDERS,
            MAX_CONCURRENT_RENDERS,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_QUEUED_RENDERS),
            object : ThreadFactory {
                private val counter = AtomicLong()

                override fun newThread(r: Runnable): Thread =
                    Thread(r, "template-render-${counter.incrementAndGet()}").apply { isDaemon = true }
            },
            // Reject rather than run-on-the-caller: a caller thread that picked up a runaway
            // render could not be interrupted by the watchdog, which is the leak this pool exists
            // to prevent. A rejected render fails fast with a catalog code instead.
            ThreadPoolExecutor.AbortPolicy(),
        )

    /** Live render workers — the assertion surface for "an aborted render really terminates". */
    internal val activeRenders: Int get() = workers.activeCount

    /**
     * The render executor itself, for tests that must assert its **configuration**.
     *
     * Observing behaviour is not enough here: after N sequential renders a cached (unbounded)
     * pool and a bounded one are indistinguishable, so only reading `corePoolSize` /
     * `maximumPoolSize` / the queue capacity / the rejection handler can tell the TPL-SEC-4 fix
     * from its regression.
     */
    internal val renderExecutor: ThreadPoolExecutor get() = workers

    /**
     * Renders [ref] against [context] to a SQL string, throwing [TemplateRenderException] on
     * any failure. This is the executor-time entry point.
     *
     * @param maxOutputChars per-render output budget. Defaults to the engine-wide backstop the
     *   constructor was given; `dag` passes the real per-execution staging budget here, because
     *   it *injects* this engine (dag-executor §5.2) and so cannot set it at construction.
     */
    @JvmOverloads
    fun render(
        ref: TemplateRef,
        context: Map<String, Any?>,
        maxOutputChars: Long = this.maxOutputChars,
    ): String =
        when (val outcome = execute(ref, context, maxOutputChars)) {
            is RenderOutcome.Success -> {
                outcome.sql
            }

            is RenderOutcome.UndefinedVariable -> {
                throw TemplateRenderException(outcome.detail, ref)
            }

            // §8.2 gives a missing template its own executor-time code; every other failure
            // (undefined variable, timeout, size cap, bad built-in) is template_render_failed.
            is RenderOutcome.NotFound -> {
                throw TemplateRenderException(outcome.detail, ref, PipelineErrorCodes.Node.TEMPLATE_NOT_FOUND)
            }

            is RenderOutcome.Failed -> {
                throw TemplateRenderException(outcome.detail, ref)
            }
        }

    /**
     * Renders [ref] against [context] and classifies the result **without throwing** — the
     * primitive [TemplateDryRendererImpl] needs so a broken template is one collected failure
     * rather than an escaped exception (pipeline-contract §17.2).
     */
    internal fun execute(
        ref: TemplateRef,
        context: Map<String, Any?>,
        maxOutputChars: Long = this.maxOutputChars,
    ): RenderOutcome {
        val normalized = RenderContextNormalizer.normalize(context)
        val future: Future<String> =
            try {
                workers.submit(Callable { renderNow(ref, normalized, maxOutputChars) })
            } catch (e: RejectedExecutionException) {
                return RenderOutcome.Failed(
                    "render capacity exhausted ($MAX_CONCURRENT_RENDERS concurrent, " +
                        "$MAX_QUEUED_RENDERS queued): ${e.javaClass.simpleName}",
                )
            }
        return try {
            RenderOutcome.Success(future.get(renderTimeoutMs, TimeUnit.MILLISECONDS))
        } catch (e: TimeoutException) {
            // Interrupt, and it lands: every template this engine loads carries Freemarker's
            // thread-interruption checks (InterruptibleConfiguration), so the worker really
            // unwinds instead of running on with the flag set.
            future.cancel(true)
            RenderOutcome.Failed("render exceeded the ${renderTimeoutMs}ms timeout (${e.javaClass.simpleName})")
        } catch (e: ExecutionException) {
            classify(e.cause ?: e)
        }
    }

    private fun renderNow(
        ref: TemplateRef,
        normalizedContext: Map<String, Any?>,
        maxOutputChars: Long,
    ): String {
        // 046 §6: the version's type picks the configuration. The lookup rides the registry's
        // resolved-version LRU (one map hit once warm), and a null version falls through to the
        // sql configuration so the loader's TemplateNotFoundException — and the NotFound
        // classification that turns it into template_not_found — is preserved exactly as it was.
        val configuration =
            if (registry.lookup(ref.id, ref.version)?.type == TemplateType.HTML) {
                htmlConfiguration
            } else {
                sqlConfiguration
            }
        val template = configuration.getTemplate(ref.key)
        val buffer = BoundedWriter(StringBuilder(), maxOutputChars)
        template.process(normalizedContext, buffer)
        return buffer.output()
    }

    private fun classify(cause: Throwable): RenderOutcome =
        when (cause) {
            // Thrown by the loader when a "{id}@{version}" key resolves to nothing — either the
            // reference itself or a library in its closure (templates.md §8.2).
            is TemplateNotFoundException -> {
                RenderOutcome.NotFound("template or imported library not found: ${cause.templateName.truncateForError()}")
            }

            is InvalidReferenceException -> {
                RenderOutcome.UndefinedVariable(
                    variable = cause.blamedExpressionString,
                    detail = "undefined variable: ${(cause.blamedExpressionString ?: "unknown").truncateForError()}",
                )
            }

            is OutputSizeExceededException -> {
                RenderOutcome.Failed("output exceeded the ${cause.limitChars}-character cap")
            }

            is TemplateException -> {
                RenderOutcome.Failed(cause.messageWithoutStackTop.truncateForError())
            }

            else -> {
                RenderOutcome.Failed((cause.message ?: cause.javaClass.simpleName).truncateForError())
            }
        }

    override fun close() {
        workers.shutdownNow()
    }

    companion object {
        /**
         * Concurrent renders allowed at once — the bound that turns "a render might not stop"
         * from an unbounded thread leak into a fixed, survivable ceiling.
         *
         * Derived from the host rather than introduced as a config key: configuration.md §3.9
         * defines the templates keys and does not name one for this, and inventing a key here
         * would put a second definition of a setting outside its authority (D8).
         */
        val MAX_CONCURRENT_RENDERS: Int = maxOf(2, Runtime.getRuntime().availableProcessors())

        /**
         * Renders allowed to wait for a slot. Queueing is bounded because a waiting render is
         * already spending its own `render-timeout-ms` budget — the wall-clock cap starts at
         * submit, so a queued render fails on time rather than late.
         */
        val MAX_QUEUED_RENDERS: Int = MAX_CONCURRENT_RENDERS * 4
    }
}

/**
 * A Freemarker [TemplateException]'s own one-line message, without its rendered FTL stack —
 * concise enough to echo into an error response and already the author's own input.
 */
private val TemplateException.messageWithoutStackTop: String
    get() = message?.substringBefore("\n\n")?.substringBefore("----") ?: "template error"
