package co.datapipelines.web

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

/**
 * Module-local log capture following #362: append and snapshot-copy own the same list monitor.
 * Readers receive detached lists in capture order, retaining the original events and metadata.
 * Never read or clear the inherited backing list from a consumer.
 */
class SnapshotListAppender : ListAppender<ILoggingEvent>() {
    init {
        start()
    }

    override fun append(eventObject: ILoggingEvent) {
        synchronized(list) { super.append(eventObject) }
    }

    /** A detached snapshot; later appends and caller list mutations cannot change each other. */
    fun events(): List<ILoggingEvent> = synchronized(list) { List(list.size) { list[it] } }

    /** Formats only detached events, so formatting never walks the live backing list. */
    fun messages(): List<String> = events().map { it.formattedMessage }
}

/** Captures [logger] during [block], detaching and stopping even when the block throws. */
fun capturingLogEvents(
    logger: Logger,
    appender: SnapshotListAppender = SnapshotListAppender(),
    block: () -> Unit,
): List<ILoggingEvent> {
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
    return appender.events()
}
