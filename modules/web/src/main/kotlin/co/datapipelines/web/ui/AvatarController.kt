package co.datapipelines.web.ui

import co.datapipelines.auth.AuthProperties
import co.datapipelines.auth.AuthenticatedPrincipal
import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The avatar proxy (#197): `GET /avatar` serves the SIGNED-IN principal's own OIDC picture,
 * fetched server-side, so the browser never loads an image from an identity provider's
 * host and the CSP's `img-src` narrows to `'self' data:` (`SecurityHeaders` — the standing
 * `https:` grant was a beacon waiting for the first user-authored image URL).
 *
 * Why a proxy and not an `img-src` host list: the picture host is NOT derivable from the
 * provider's `issuerUri` — measured on the providers this product names (2026-09-25):
 * Google issues from `accounts.google.com` but serves pictures from
 * `lh3.googleusercontent.com`; Entra's pictures live on Microsoft Graph; a self-hosted
 * Keycloak's sit wherever its operator mapped them (the test realm emits none at all).
 * A list derived from the issuers would refuse the real deployment's avatars, so the
 * acceptance's second arm applies.
 *
 * ## The outbound fence (the SSRF posture)
 * The fetch target is never caller-chosen: it is the caller's OWN `users.profile_picture_url`
 * row, written only by `OidcSuccessHandler` from the IdP's `picture` claim. On top of that:
 *
 * - **Allowlisted hosts only** — `datapipelines.auth.oidc.providers[*].picture-hosts`
 *   ([AuthProperties.Provider.pictureHosts]), the union across providers. Empty (the shipped
 *   default) fetches nothing and every request answers 404 — the avatar falls back to
 *   initials, exactly the no-OIDC-picture rendering. A malicious or compromised IdP can
 *   therefore point the fetch only at hosts the operator has already named.
 * - **Scheme and authority checks** — `http`/`https` only, no `userinfo` component, a host
 *   required, and NO explicit port (#246): the allowlist entries are bare hostnames, so a
 *   port in the URL has nothing to match and would otherwise steer the connection to any
 *   port on a trusted host. Anything else answers 404 without a network hop.
 * - **No redirects followed** — a 3xx is a refusal, so a compliant first answer cannot be
 *   laundered through a host the allowlist never saw.
 * - **Size cap** — [MAX_BYTES] read from the body regardless of the declared
 *   `Content-Length` (which the remote server controls); a bigger body is a refusal.
 * - **Raster images only** — the response's `Content-Type` must be one of [RASTER_TYPES]
 *   (PNG, JPEG, GIF, WebP; #246 — SVG is out, see there); the type is passed through
 *   verbatim, never sniffed.
 * - **Bounded time** — the whole fetch, headers AND body, completes within
 *   [AvatarImageFetcher.BODY_DEADLINE] or is abandoned (#246), so a slow or stalled provider
 *   host holds one request thread for at most that long, never a browser.
 *
 * A small TTL cache ([AvatarCache]) keeps a page's renders from re-fetching the provider
 * on every request, and the answer carries `Cache-Control: private` so the browser caches
 * it per user without any shared cache holding user data.
 */
@RestController
class AvatarController(
    private val userRepository: UserRepository,
    hosts: AvatarHosts,
    private val fetcher: AvatarImageFetcher,
) {
    private val allowedHosts: Set<String> = hosts.names
    private val cache = AvatarCache(maxEntries = 64, ttl = CACHE_TTL)

    @GetMapping("/avatar")
    @RequiredScope(Permission.PROFILE_READ)
    fun avatar(): ResponseEntity<ByteArray> {
        val url = storedPictureUrl() ?: return NOT_FOUND
        val image = cachedOrFetched(url) ?: return NOT_FOUND
        return served(image)
    }

    /** The signed-in principal's own stored picture URL, or null when there is nothing to serve. */
    private fun storedPictureUrl(): String? {
        val principal =
            SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedPrincipal
                ?: return null
        return userRepository.findById(principal.userId)?.profilePictureUrl?.takeIf { it.isNotBlank() }
    }

    /** The cached answer, or one fetch through the fence — the result lands in the cache either way. */
    private fun cachedOrFetched(url: String): AvatarImage? {
        cache.get(url)?.let { return it }
        val uri = validated(url) ?: return null
        return fetcher.fetch(uri)?.also { cache.put(url, it) }
    }

    /**
     * The stored URL as a fetch target, or null with the reason in the log (host only — the
     * path is user data). Every fence here is checked BEFORE any network hop.
     */
    private fun validated(url: String): URI? {
        val uri = runCatching { URI(url) }.getOrNull()?.takeIf { it.isAbsolute }
        val reason =
            when {
                uri == null -> "unparseable URL"
                uri.userInfo != null -> "userinfo component"
                uri.host?.lowercase() !in allowedHosts -> "host not allowlisted"
                uri.port != -1 -> "port not allowed"
                uri.scheme?.lowercase() !in SCHEMES -> "scheme not http(s)"
                else -> null
            }
        if (reason != null) log.warn("event=avatar.refused reason={} host={}", reason, uri?.host)
        return if (reason == null) uri else null
    }

    private fun served(image: AvatarImage): ResponseEntity<ByteArray> =
        ResponseEntity
            .ok()
            .contentType(image.contentType)
            .contentLength(image.bytes.size.toLong())
            .cacheControl(CacheControl.maxAge(BROWSER_MAX_AGE).cachePrivate())
            .body(image.bytes)

    companion object {
        private val log = LoggerFactory.getLogger(AvatarController::class.java)

        private val SCHEMES = setOf("http", "https")

        /** The most bytes the proxy will read from a provider answer. */
        const val MAX_BYTES = 1 shl 20

        /**
         * The only content types served (#246), compared on type and subtype. Raster formats
         * decode to pixels and nothing else. SVG is out: it is a DOCUMENT format that can carry
         * script, event handlers and external references, and `/avatar` answers from this
         * origin — `nosniff` and the CSP contain a direct navigation to it today, but a raster
         * allowlist means the answer can never be active content whatever the headers say.
         */
        val RASTER_TYPES: Set<MediaType> =
            setOf(MediaType.IMAGE_PNG, MediaType.IMAGE_JPEG, MediaType.IMAGE_GIF, MediaType("image", "webp"))

        /** No cached or fetched avatar is admitted above this — bounds the cache's footprint. */
        const val CACHE_ADMIT_MAX_BYTES = 256 * 1024

        private val CACHE_TTL: Duration = Duration.ofMinutes(5)
        private val BROWSER_MAX_AGE: Duration = Duration.ofMinutes(5)

        private val NOT_FOUND: ResponseEntity<ByteArray> = ResponseEntity.status(HttpStatus.NOT_FOUND).build()
    }
}

/**
 * #197: the avatar proxy's wiring, beside the code it wires (UiConfig sat at detekt's
 * function ceiling — the two beans this route needs went into their own configuration).
 */
@org.springframework.context.annotation.Configuration
class AvatarConfiguration {
    /** The picture-host allowlist; typo'd entries refuse startup (see [AvatarHosts]). */
    @org.springframework.context.annotation.Bean
    fun avatarHosts(authProperties: AuthProperties): AvatarHosts = AvatarHosts(authProperties)

    /** The transport: redirects never followed, size cap on the read (see [AvatarImageFetcher]). */
    @org.springframework.context.annotation.Bean
    fun avatarImageFetcher(): AvatarImageFetcher = AvatarImageFetcher()
}

/**
 * The operator's picture-host allowlist, built once from [AuthProperties]: a typo'd entry
 * refuses STARTUP here rather than silently 404-ing avatars — the same shape
 * `ClientAddressResolver` set for `trusted-proxies` (Configuration §3.4).
 */
class AvatarHosts(
    authProperties: AuthProperties,
) {
    val names: Set<String> =
        authProperties.oidc.providers
            .flatMap { it.pictureHosts }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .onEach { entry ->
                require(isBareHostname(entry)) {
                    "datapipelines.auth.oidc.providers[*].picture-hosts entry '$entry' is not a bare hostname " +
                        "(no scheme, port, path, userinfo or wildcard)"
                }
            }.toSet()

    private fun isBareHostname(entry: String): Boolean =
        entry.length > 1 &&
            !entry.any { it in "/:@*" } &&
            !entry.startsWith(".") &&
            !entry.endsWith(".")
}

/** One fetched avatar: the bytes and the provider's own (already an `image` type) content type. */
class AvatarImage(
    val bytes: ByteArray,
    val contentType: MediaType,
)

/**
 * The transport half of the avatar proxy — the fence's transport behaviors live here because
 * only a real HTTP client can hold them: redirects never followed, [AvatarController.MAX_BYTES]
 * read from the body whatever the declared `Content-Length` says, one of
 * [AvatarController.RASTER_TYPES] required, and the whole fetch bounded by [BODY_DEADLINE].
 * Open for tests that substitute the WIRE (an in-JVM server), never the semantics.
 * [close] stops the deadline thread (Spring infers it as the bean's destroy method).
 */
open class AvatarImageFetcher(
    private val maxBytes: Int = AvatarController.MAX_BYTES,
) : AutoCloseable {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    /**
     * Closes a response body when its fetch's deadline passes. A read blocked on a body that
     * stopped arriving cannot check a clock; the JDK's response stream wakes such a reader
     * when it is closed (and cancels the exchange), so the close IS the deadline for a stall.
     */
    private val deadlines =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "dp-avatar-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

    /** Null = refused by the fence or failed — the controller cannot tell a browser why, and does not try. */
    open fun fetch(uri: URI): AvatarImage? {
        // One budget from here: connect and headers ([REQUEST_TIMEOUT], whose JDK timer starts
        // before connect) and then the body, which gets whatever of it the headers left.
        val deadline = System.nanoTime() + BODY_DEADLINE.toNanos()
        val request =
            HttpRequest
                .newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: java.io.IOException) {
                log.info("event=avatar.fetch_failed host={} error={}", uri.host, e.javaClass.simpleName)
                return null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info("event=avatar.fetch_failed host={} error=interrupted", uri.host)
                return null
            }
        return read(response, deadline)
    }

    override fun close() {
        deadlines.shutdownNow()
    }

    /** Status, content type and capped, deadline-bound read — each fence in turn, each failure a quiet null. */
    private fun read(
        response: HttpResponse<InputStream>,
        deadline: Long,
    ): AvatarImage? {
        val stream = response.body()
        val closeAtDeadline =
            deadlines.schedule({ runCatching { stream.close() } }, deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
        try {
            val mediaType = imageType(response) ?: return null
            val bytes = readCapped(stream, deadline) ?: return null
            return AvatarImage(bytes, mediaType)
        } finally {
            closeAtDeadline.cancel(false)
            runCatching { stream.close() }
        }
    }

    /** The answer's content type, provided the answer is a success carrying one of the raster types. */
    private fun imageType(response: HttpResponse<InputStream>): MediaType? {
        if (response.statusCode() !in HTTP_SUCCESS_MIN..HTTP_SUCCESS_MAX) return null
        val raw = response.headers().firstValue("Content-Type").orElse(null) ?: return null
        val mediaType =
            try {
                MediaType.parseMediaType(raw)
            } catch (e: InvalidMediaTypeException) {
                log.info("event=avatar.fetch_failed error=content_type detail={}", e.message)
                return null
            }
        val raster = AvatarController.RASTER_TYPES.any { it.equalsTypeAndSubtype(mediaType) }
        if (!raster) log.info("event=avatar.fetch_failed error=content_type detail={}", mediaType)
        return mediaType.takeIf { raster }
    }

    /**
     * Reads up to [maxBytes] bytes before [deadline] (a [System.nanoTime] instant); null once the
     * stream runs past the cap (the declared length is not trusted) or the deadline passes —
     * checked before every chunk, and a read blocked past it is woken by the scheduled close.
     */
    private fun readCapped(
        stream: InputStream,
        deadline: Long,
    ): ByteArray? {
        val buffer = ByteArrayOutputStream(minOf(maxBytes, BUFFER_HINT))
        val chunk = ByteArray(READ_CHUNK)
        while (true) {
            val read = readChunk(stream, chunk, deadline) ?: return null
            if (read < 0) return buffer.toByteArray()
            val total = buffer.size() + read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
    }

    /** One chunk's byte count (-1 at the end), or null — logged — once the deadline has passed or the read failed. */
    private fun readChunk(
        stream: InputStream,
        chunk: ByteArray,
        deadline: Long,
    ): Int? {
        if (passed(deadline)) return deadlineRefusal()
        return try {
            stream.read(chunk)
        } catch (e: java.io.IOException) {
            // A read blocked past the deadline is woken by the scheduled close with an IOException.
            if (passed(deadline)) {
                deadlineRefusal()
            } else {
                log.info("event=avatar.fetch_failed error=read detail={}", e.message)
                null
            }
        }
    }

    private fun passed(deadline: Long): Boolean = System.nanoTime() - deadline >= 0

    private fun deadlineRefusal(): Int? {
        log.info("event=avatar.fetch_failed error=deadline budget_ms={}", BODY_DEADLINE.toMillis())
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(AvatarImageFetcher::class.java)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

        /**
         * The budget for the WHOLE fetch — connect, headers and body (#246). [REQUEST_TIMEOUT]
         * bounds only the time to headers; without this a provider that sends headers and
         * then dribbles (or stops) holds a request thread until the size cap or the
         * connection's end. 5 s = the header budget the fetch already had, now covering the
         * body too: a real provider serves an avatar (tens of KB from a CDN) in well under a
         * second, so the budget refuses only a host that is broken or hostile, and the page
         * falls back to initials rather than waiting on it.
         */
        val BODY_DEADLINE: Duration = Duration.ofSeconds(5)

        private const val HTTP_SUCCESS_MIN = 200
        private const val HTTP_SUCCESS_MAX = 299
        private const val READ_CHUNK = 8 * 1024
        private const val BUFFER_HINT = 64 * 1024
    }
}

/**
 * A tiny bounded TTL cache so a page's renders do not re-fetch the provider host on every
 * request: at most [maxEntries] avatars, each admitted only at [CACHE_ADMIT_MAX_BYTES] or
 * below (so the worst-case footprint is `maxEntries × CACHE_ADMIT_MAX_BYTES` ≈ 16 MiB),
 * each expiring [ttl] after it was fetched. Synchronized: avatar requests are rare and small.
 */
class AvatarCache(
    private val maxEntries: Int,
    private val ttl: Duration,
) {
    private class Entry(
        val image: AvatarImage,
        val fetchedAt: Instant,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
        }

    @Synchronized
    fun get(url: String): AvatarImage? {
        val entry = entries[url] ?: return null
        if (Duration.between(entry.fetchedAt, Instant.now()) > ttl) {
            entries.remove(url)
            return null
        }
        return entry.image
    }

    @Synchronized
    fun put(
        url: String,
        image: AvatarImage,
    ) {
        if (image.bytes.size > CACHE_ADMIT_MAX_BYTES) return
        entries[url] = Entry(image, Instant.now())
    }

    companion object {
        const val CACHE_ADMIT_MAX_BYTES = AvatarController.CACHE_ADMIT_MAX_BYTES

        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
    }
}
