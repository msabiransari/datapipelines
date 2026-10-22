package co.datapipelines.scripting

import java.io.OutputStream

/**
 * An in-memory byte sink that refuses to grow past [limit] (#8): the JavaScript engine encodes the
 * input into one (so a 1 GB input is refused at the budget, never built whole first) and captures
 * the guest's console and error streams in two more (the sandbox's own stream caps stop the guest;
 * this cap is the host's own, never trusted to the guest side).
 *
 * Past the limit a write calls [onOverflow] with the byte count it would have reached and throws
 * what it returns; nothing past the limit is kept.
 */
internal class BoundedBuffer(
    private val limit: Long,
    private val onOverflow: (attempted: Long) -> RuntimeException,
) : OutputStream() {
    private var bytes = ByteArray(INITIAL)
    private var size = 0

    /** Bytes written so far. */
    val length: Int get() = size

    override fun write(b: Int) {
        ensure(1)
        bytes[size++] = b.toByte()
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        ensure(len)
        System.arraycopy(b, off, bytes, size, len)
        size += len
    }

    private fun ensure(more: Int) {
        val wanted = size.toLong() + more
        if (wanted > limit) throw onOverflow(wanted)
        if (wanted > bytes.size) {
            val grown = maxOf(wanted, minOf(bytes.size.toLong() * 2, limit)).coerceAtMost(MAX_ARRAY_BYTES)
            if (grown < wanted) throw onOverflow(wanted)
            bytes = bytes.copyOf(grown.toInt())
        }
    }

    /** The bytes written, decoded as UTF-8. */
    fun utf8(): String = String(bytes, 0, size, Charsets.UTF_8)

    private companion object {
        const val INITIAL = 256

        /** The largest byte array the JVM reliably allocates (the JDK's own soft limit, `Integer.MAX_VALUE - 8`). */
        const val MAX_ARRAY_BYTES = Int.MAX_VALUE.toLong() - 8
    }
}
