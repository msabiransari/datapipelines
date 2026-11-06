package co.datapipelines.config

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** Keeps the applicability evidence for the final #461 renewal true until #834 removes the exemptions. */
class SpringAdvisoryExemptionsTest {
    private val mainSources by lazy { trackedFiles().filter { "/src/main/" in it.path } }
    private val lockfiles by lazy { trackedFiles().filter { it.path.endsWith(".lockfile") } }

    @Test
    fun `main sources and resources configure no XSLT views`() {
        withClue("main-source inventory must not be vacuous") { (mainSources.size >= MAIN_SOURCE_FLOOR) shouldBe true }
        withClue("#461 XSLT exemption no longer applies") {
            SpringAdvisoryPolicy.xsltViolations(mainSources).shouldBeEmpty()
        }
    }

    @Test
    fun `SSE sends carry only serialized JSON or constant comments and no WebFlux is locked`() {
        val audit = SpringAdvisoryPolicy.sseAudit(mainSources)
        println("#461 main=${mainSources.size} lockfiles=${lockfiles.size} SSE sends=${audit.sends} JSON data=${audit.data}")
        withClue("the inventory must include every existing emitter send") { (audit.sends >= SSE_SEND_FLOOR) shouldBe true }
        withClue("the inventory must include all four existing data sinks") { (audit.data >= SSE_DATA_FLOOR) shouldBe true }
        withClue("lockfile inventory must not be vacuous") { (lockfiles.size >= LOCKFILE_FLOOR) shouldBe true }
        withClue("#461 SSE exemption no longer applies") { audit.violations.shouldBeEmpty() }
        withClue("#461 WebFlux exemption no longer applies") { SpringAdvisoryPolicy.webfluxViolations(lockfiles).shouldBeEmpty() }
    }

    @Test
    fun `XSLT detection includes qualified types and resource configuration`() {
        listOf(
            "org.springframework.web.servlet.view.xslt.XsltView()",
            "org.springframework.web.servlet.view.xslt.XsltViewResolver()",
            "spring.mvc.view.type=xslt",
            "<xsl:stylesheet version=\"1.0\"/>",
        ).forEach { text ->
            withClue(text) { SpringAdvisoryPolicy.xsltViolations(listOf(AdvisorySource("config.xml", text))).size shouldBe 1 }
        }
        SpringAdvisoryPolicy.xsltViolations(listOf(AdvisorySource("view.xsl", ""))).size shouldBe 1
        SpringAdvisoryPolicy.xsltViolations(listOf(AdvisorySource("view.xslt", ""))).size shouldBe 1
    }

    @Test
    fun `SSE policy rejects view objects and opaque sends including aliases and new sender paths`() {
        listOf("ModelAndView(\"view\")", "org.springframework.web.servlet.view.FragmentsRendering.fragment(\"view\").build()")
            .forEach { value ->
                val code =
                    "import org.springframework.web.servlet.mvc.method.annotation.SseEmitter\n" +
                        "fun emit() { emitter.send(SseEmitter.event().data($value)) }"
                SpringAdvisoryPolicy.sseAudit(listOf(AdvisorySource("new/Sender.kt", code))).violations.isNotEmpty() shouldBe true
            }
        listOf("emitter.send(opaque)", "emitter.send(SseEmitter.event().data(payload))").forEach { send ->
            val code = "import org.springframework.web.servlet.mvc.method.annotation.SseEmitter as Events\nfun emit() { $send }"
            SpringAdvisoryPolicy.sseAudit(listOf(AdvisorySource("new/Sender.kt", code))).violations.size shouldBe 1
        }
        val alias = AdvisorySource("Alias.kt", "typealias Events = org.springframework.web.servlet.mvc.method.annotation.SseEmitter")
        val caller = AdvisorySource("new/Sender.kt", "val emitter: Events\nfun emit() { emitter.send(view) }")
        SpringAdvisoryPolicy.sseAudit(listOf(alias, caller)).violations.size shouldBe 1
    }

    @Test
    fun `SSE policy accepts JSON with nested calls and constant comments without confusing strings or comments for calls`() {
        val code =
            """
            import org.springframework.web.servlet.mvc.method.annotation.SseEmitter as Events
            fun emit() {
                emitter.send(Events.event().data(mapper.writeValueAsString(project(a, b)), MediaType.APPLICATION_JSON))
                emitter.send(Events.event().comment(ApiErrors.REVOKED))
                // emitter.send(ModelAndView("not code"))
                val text = "emitter.send(ModelAndView())"
            }
            """.trimIndent()
        val audit = SpringAdvisoryPolicy.sseAudit(listOf(AdvisorySource("Sender.kt", code)))
        audit.sends shouldBe 2
        audit.data shouldBe 1
        audit.violations.shouldBeEmpty()
        SpringAdvisoryPolicy
            .sseAudit(listOf(AdvisorySource("Page.kt", "fun page() = ModelAndView(\"page\")")))
            .violations
            .shouldBeEmpty()
    }

    @Test
    fun `WebFlux detection covers both framework and starter coordinates`() {
        listOf(
            "org.springframework:spring-webflux:7.0.9=runtimeClasspath",
            "org.springframework.boot:spring-boot-starter-webflux:4.1.0=testRuntimeClasspath",
            "io.modelcontextprotocol.sdk:mcp-spring-webflux:0.18.3=runtimeClasspath",
        ).forEach { text ->
            SpringAdvisoryPolicy.webfluxViolations(listOf(AdvisorySource("gradle.lockfile", text))).size shouldBe 1
        }
        SpringAdvisoryPolicy
            .webfluxViolations(
                listOf(AdvisorySource("gradle.lockfile", "org.springframework:spring-webmvc:6.2.19=runtimeClasspath")),
            ).shouldBeEmpty()
    }

    private fun trackedFiles(): List<AdvisorySource> {
        var root = File(".").absoluteFile
        while (!File(root, "settings.gradle.kts").isFile) root = root.parentFile ?: error("Repository root not found")
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root).redirectErrorStream(true).start()
        val paths =
            process.inputStream
                .readBytes()
                .decodeToString()
                .split('\u0000')
                .filter { it.isNotBlank() }
        check(process.waitFor() == 0) { "git ls-files failed in $root" }
        return paths.filter { "/src/main/" in it || it.endsWith(".lockfile") }.mapNotNull { path ->
            val bytes = File(root, path).readBytes()
            if (bytes.contains(0.toByte())) null else AdvisorySource(path, bytes.decodeToString())
        }
    }

    private companion object {
        const val MAIN_SOURCE_FLOOR = 1_000
        const val LOCKFILE_FLOOR = 23
        const val SSE_SEND_FLOOR = 12
        const val SSE_DATA_FLOOR = 4
    }
}
