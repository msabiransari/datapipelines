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
 *   required; anything else answers 404 without a network hop.
 * - **No redirects followed** — a 3xx is a refusal, so a compliant first answer cannot be
 *   laundered through a host the allowlist never saw.
 * - **Size cap** — [MAX_BYTES] read from the body regardless of the declared
 *   `Content-Length` (which the remote server controls); a bigger body is a refusal.
 * - **Image content only** — the response's `Content-Type` must be an `image` type
 *   (`image/png`, `image/jpeg`, ...); the type is passed through verbatim, never sniffed.
 * - **Bounded time** — a few seconds of connect/request budget, so a slow provider host
 *   holds one render thread briefly, not a browser.
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
 * read from the body whatever the declared `Content-Length` says, an `image` content type
 * required. Open for tests that substitute the WIRE (an in-JVM server), never the semantics.
 */
open class AvatarImageFetcher(
    private val maxBytes: Int = AvatarController.MAX_BYTES,
) {
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()

    /** Null = refused by the fence or failed — the controller cannot tell a browser why, and does not try. */
    open fun fetch(uri: URI): AvatarImage? {
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
        return read(response)
    }

    /** Status, content type and capped read — each fence in turn, each failure a quiet null. */
    private fun read(response: HttpResponse<InputStream>): AvatarImage? {
        val stream = response.body()
        try {
            val mediaType = imageType(response) ?: return null
            val bytes = readCapped(stream) ?: return null
            return AvatarImage(bytes, mediaType)
        } finally {
            runCatching { stream.close() }
        }
    }

    /** The answer's content type, provided the answer is a success carrying an `image` type. */
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
        return mediaType.takeIf { it.type == "image" }
    }

    /** Reads up to [maxBytes] bytes; null once the stream runs past the cap (the declared length is not trusted). */
    private fun readCapped(stream: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(minOf(maxBytes, BUFFER_HINT))
        val chunk = ByteArray(READ_CHUNK)
        while (true) {
            val read =
                try {
                    stream.read(chunk)
                } catch (e: java.io.IOException) {
                    log.info("event=avatar.fetch_failed error=read detail={}", e.message)
                    return null
                }
            if (read < 0) return buffer.toByteArray()
            val total = buffer.size() + read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AvatarImageFetcher::class.java)
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)

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
