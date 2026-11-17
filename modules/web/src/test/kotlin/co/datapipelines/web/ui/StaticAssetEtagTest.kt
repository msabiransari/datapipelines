package co.datapipelines.web.ui

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.support.StaticApplicationContext
import org.springframework.core.io.AbstractResource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The static files' validator is their CONTENT (#1023 D3), proven through a real resource handler that takes its
 * settings from [StaticAssetCacheConfig.withCacheRule], the one function every handler of #1023 uses — never from a
 * copy here. Mock requests against the handler the framework's own registry builds over a temporary directory.
 */
class StaticAssetEtagTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a file on disk is answered by its bytes, whatever its length and its time`() {
        val file = dir.resolve(NAME)
        Files.write(file, FIRST)
        val handler = handler(ContentEtag())

        // (1) asked → 200, the ETag is the SHA-256 of the body, the re-check rule, no Last-Modified.
        val first = get(handler)
        first.status shouldBe 200
        first.contentAsByteArray.toList() shouldBe FIRST.toList()
        val firstEtag = first.getHeader("ETag")
        firstEtag shouldBe quoted(sha256(first.contentAsByteArray))
        first.getHeader("Cache-Control") shouldBe "no-cache"
        first.getHeader("Last-Modified").shouldBeNull()

        // (2) different bytes, SAME length, the old time set back: the old ETag gets the new body in full.
        val time = Files.getLastModifiedTime(file)
        Files.write(file, SECOND)
        Files.setLastModifiedTime(file, FileTime.fromMillis(time.toMillis()))
        Files.size(file) shouldBe FIRST.size.toLong()
        val changed = get(handler, ifNoneMatch = firstEtag)
        changed.status shouldBe 200
        changed.contentAsByteArray.toList() shouldBe SECOND.toList()
        val secondEtag = changed.getHeader("ETag")
        secondEtag shouldBe quoted(sha256(SECOND))
        secondEtag shouldNotBe firstEtag

        // (3) asked again with the new ETag → 304, an empty body, still re-checked.
        val same = get(handler, ifNoneMatch = secondEtag)
        same.status shouldBe 304
        same.contentAsByteArray.size shouldBe 0
        same.getHeader("Cache-Control") shouldBe "no-cache"
    }

    @Test
    fun `a packaged file is read once and its hash kept, one entry per file`() {
        val etags = ContentEtag()
        val generator = requireNotNull(handler(etags).etagGenerator) { "the handler has no ETag generator" }
        val standIn = PackagedStandIn(FIRST)

        // (4) a resource that is not a file on disk, asked twice → read once, one entry kept.
        val first = generator.apply(standIn)
        val second = generator.apply(standIn)
        first shouldBe sha256(FIRST)
        second shouldBe first
        standIn.reads shouldBe 1
        etags.keptEntries shouldBe 1
    }

    /** The handler the registry builds for one location, configured by the production function. */
    private fun handler(etags: ContentEtag): ResourceHttpRequestHandler {
        val registry = ExposedRegistry()
        StaticAssetCacheConfig.withCacheRule(
            registry.addResourceHandler(PATTERN).addResourceLocations(dir.toUri().toString()),
            StaticAssetCacheConfig.RECHECK_RULE,
            etags,
        )
        val mapping = registry.mapping() as SimpleUrlHandlerMapping
        return mapping.urlMap[PATTERN] as ResourceHttpRequestHandler
    }

    private fun get(
        handler: ResourceHttpRequestHandler,
        ifNoneMatch: String? = null,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", "/t/$NAME")
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, NAME)
        if (ifNoneMatch != null) request.addHeader("If-None-Match", ifNoneMatch)
        val response = MockHttpServletResponse()
        handler.handleRequest(request, response)
        return response
    }

    private class ExposedRegistry : ResourceHandlerRegistry(StaticApplicationContext(), MockServletContext()) {
        fun mapping() = handlerMapping
    }

    /** A file as the packaged jar holds it: not a file on disk, its reads counted. */
    private class PackagedStandIn(
        private val bytes: ByteArray,
    ) : AbstractResource() {
        var reads = 0
            private set

        override fun getDescription(): String = "packaged stand-in"

        override fun getURL(): URL = URI.create("jar:file:/stand-in.jar!/static/css/$NAME").toURL()

        override fun getInputStream(): InputStream {
            reads++
            return ByteArrayInputStream(bytes)
        }
    }

    private companion object {
        const val NAME = "probe.css"
        const val PATTERN = "/t/**"
        val FIRST = "body { color: red; }\n".toByteArray()
        val SECOND = "body { color: tan; }\n".toByteArray()

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun quoted(value: String) = "\"$value\""
    }
}
