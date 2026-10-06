package co.datapipelines.web.navigation

import co.datapipelines.pipeline.NavigationFamily
import co.datapipelines.pipeline.NavigationRequest
import co.datapipelines.pipeline.NavigationRow
import co.datapipelines.pipeline.ReadLens
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Navigation payloads contain only these fields, never stored artifact bodies. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TreeNode(
    val key: String,
    val parentKey: String?,
    val kind: String,
    val name: String,
    val path: String,
    val hasChildren: Boolean,
    val match: Boolean,
    val resourceId: UUID? = null,
    val href: String? = null,
    val version: Int? = null,
    val draftVersion: Int? = null,
)

/** Continuation exhausts logical results; repeated ancestors do not consume the match bound. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TreePage(
    val family: String,
    val mode: String,
    val root: String,
    val parent: String,
    val query: String?,
    val workspaceId: UUID,
    val viewToken: String,
    val nodes: List<TreeNode>,
    val nextCursor: String?,
)

/** Shared input, continuation and projection discipline for the five session sources. */
class TreeProjection {
    private val mapper = jacksonObjectMapper()
    private val secret = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }

    @Suppress("LongParameterList") // explicit continuation identity fields plus repository-owned loader
    fun page(
        family: NavigationFamily,
        workspace: UUID,
        actor: UUID,
        lens: ReadLens,
        root: String,
        parent: String,
        query: String?,
        cursor: String?,
        load: (NavigationRequest) -> List<NavigationRow>,
    ): TreePage {
        validatePath(root)
        validatePath(parent)
        if (parent != root && !parent.startsWith(if (root.isEmpty()) "" else "$root/")) invalid()
        val normalized = query?.trim()?.replace(Regex("\\s+"), " ")
        if (normalized != null && (normalized.isEmpty() || normalized.length > MAX_PATH)) invalid()
        val membership =
            when (lens) {
                ReadLens.Everything -> "everything"
                is ReadLens.Only -> lens.names.sorted().joinToString("\n")
            }
        val view = digest("$workspace\n$actor\n$membership")
        val identity = digest("${family.route}\n$root\n$parent\n${normalized.orEmpty()}\n$view")
        val after = cursor?.let { decode(it, identity) }.orEmpty()
        val rows = load(NavigationRequest(workspace, lens, root, parent, normalized, after, NavigationRequest.PAGE_SIZE + 1))
        val delivered = rows.take(NavigationRequest.PAGE_SIZE)
        val (nodes, accepted) = projectNodes(family, delivered, root, normalized != null)
        val next = if (rows.size > accepted) encode(identity, delivered[accepted - 1].orderKey) else null
        return TreePage(
            family.route,
            if (normalized ==
                null
            ) {
                "browse"
            } else {
                "search"
            },
            root,
            parent,
            normalized,
            workspace,
            view,
            nodes,
            next,
        )
    }

    private fun projectNodes(
        family: NavigationFamily,
        rows: List<NavigationRow>,
        root: String,
        searching: Boolean,
    ): Pair<List<TreeNode>, Int> {
        val nodes = linkedMapOf<String, TreeNode>()
        var bytes = ENVELOPE_RESERVE
        var accepted = 0
        for (row in rows) {
            val ancestors = if (searching) ancestors(row.path, root).map { folder(it, root) } else emptyList()
            val node = if (row.id == null) folder(row.path, root) else artifact(family, row, root, searching)
            val additions = (ancestors + node).filter { it.key !in nodes }
            val cost = additions.sumOf { mapper.writeValueAsBytes(it).size + 1 }
            if (cost > MAX_RESPONSE_BYTES - bytes) {
                if (accepted ==
                    0
                ) {
                    throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Navigation metadata is too large")
                }
                break
            }
            additions.forEach { nodes[it.key] = it }
            bytes += cost
            accepted += 1
        }
        return nodes.values.toList() to accepted
    }

    private fun folder(
        path: String,
        root: String,
    ): TreeNode =
        TreeNode("folder:$path", parentKey(path, root), "folder", path.substringAfterLast('/'), path, hasChildren = true, match = false)

    private fun artifact(
        family: NavigationFamily,
        row: NavigationRow,
        root: String,
        matched: Boolean,
    ): TreeNode {
        val target =
            if (family ==
                NavigationFamily.TEMPLATES
            ) {
                row.path.split('/').joinToString("/") { encodeSegment(it) }
            } else {
                row.id.toString()
            }
        return TreeNode(
            row.key,
            parentKey(row.path, root),
            "artifact",
            row.name,
            row.path,
            hasChildren = false,
            match = matched,
            resourceId = row.id,
            href = "/${family.route}/$target",
            version = row.version,
            draftVersion = row.draftVersion,
        )
    }

    private fun parentKey(
        path: String,
        root: String,
    ): String? {
        val parent = path.substringBeforeLast('/', "")
        return if (parent == root) null else "folder:$parent"
    }

    private fun ancestors(
        path: String,
        root: String,
    ): List<String> =
        path
            .split('/')
            .dropLast(1)
            .runningReduce { prefix, segment -> "$prefix/$segment" }
            .filter { it != root && (root.isEmpty() || it.startsWith("$root/")) }

    private fun encode(
        identity: String,
        after: String,
    ): String {
        val payload = "$identity\n$after".toByteArray(StandardCharsets.UTF_8)
        return BASE64.encodeToString(payload) + "." + BASE64.encodeToString(sign(payload))
    }

    private fun decode(
        cursor: String,
        identity: String,
    ): String {
        if (cursor.length > MAX_CURSOR) invalid()
        val parts = cursor.split('.')
        if (parts.size != 2) invalid()
        val payload =
            try {
                DECODER.decode(parts[0])
            } catch (_: IllegalArgumentException) {
                invalid()
            }
        val signature =
            try {
                DECODER.decode(parts[1])
            } catch (_: IllegalArgumentException) {
                invalid()
            }
        if (!MessageDigest.isEqual(sign(payload), signature)) invalid()
        val text = String(payload, StandardCharsets.UTF_8)
        if (text.substringBefore('\n') != identity) invalid()
        val after = text.substringAfter('\n', "")
        if (after.isEmpty()) invalid()
        return after
    }

    private fun sign(payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(payload)
        }

    private fun digest(value: String): String =
        BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun validatePath(path: String) {
        if (path.length > MAX_PATH || path.any { it.isISOControl() }) invalid()
        if (path.isEmpty()) return
        path.split('/').forEach { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") invalid()
        }
    }

    private fun encodeSegment(value: String): String =
        java.net.URLEncoder
            .encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")

    private fun invalid(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tree request or continuation")

    companion object {
        const val MAX_RESPONSE_BYTES = 1048576
        private const val ENVELOPE_RESERVE = 16384
        private const val MAX_PATH = 512
        private const val MAX_CURSOR = 4096
        private const val SECRET_BYTES = 32
        private val BASE64 = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
    }
}
