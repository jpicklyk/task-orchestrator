package io.github.jpicklyk.mcptask.current.test

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.CallLogStore
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Raw JDBC reads of `call_log` and `events`, independent of every production store class. */
object CallLogRows {
    /** Every call_log row as column to value (INTEGER as Long, TEXT as String, BLOB as ByteArray), ordered by req_id. */
    fun read(jdbcUrl: String): List<Map<String, Any?>> = query(jdbcUrl, "SELECT * FROM call_log ORDER BY req_id")

    fun count(jdbcUrl: String): Int = query(jdbcUrl, "SELECT count(*) AS n FROM call_log").single()["n"].let { (it as Number).toInt() }

    /** The call_log rows of one tool, ordered by `at` then req_id. */
    fun forTool(
        jdbcUrl: String,
        tool: String
    ): List<Map<String, Any?>> =
        read(jdbcUrl)
            .filter {
                it["tool"] == tool
            }.sortedWith(compareBy({ it["at"] as String }, { it["req_id"] as String }))

    /** (req_id, session_id) of every events row, ordered by seq. */
    fun eventStamps(jdbcUrl: String): List<Pair<String?, String?>> =
        query(jdbcUrl, "SELECT req_id, session_id FROM events ORDER BY seq").map { it["req_id"] as String? to it["session_id"] as String? }

    /** (type, req_id, proof_status) of every events row, ordered by seq. */
    fun eventRows(jdbcUrl: String): List<Triple<String, String?, String?>> =
        query(jdbcUrl, "SELECT type, req_id, proof_status FROM events ORDER BY seq").map {
            Triple(it["type"] as String, it["req_id"] as String?, it["proof_status"] as String?)
        }

    private fun query(
        jdbcUrl: String,
        sql: String
    ): List<Map<String, Any?>> =
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    buildList {
                        while (rs.next()) {
                            add(
                                (1..meta.columnCount).associate {
                                    meta.getColumnLabel(it) to
                                        rs.getObject(it).let { v -> if (v is Int) v.toLong() else v }
                                }
                            )
                        }
                    }
                }
            }
        }
}

/** A minimal valid [CallLogRecord] (every non-null column set); override only what a test is about. */
fun sampleCallLogRecord(
    reqId: String,
    tool: String = "sample_tool",
    at: Instant = Instant.parse("2026-03-04T05:06:07.089Z")
): CallLogRecord =
    CallLogRecord(
        reqId = reqId,
        at = at,
        surface = CallLogRecord.SURFACE_MCP,
        tool = tool,
        outcome = CallLogRecord.OUTCOME_OK,
        latencyMs = 1
    )

/** An 8-character id from the Crockford alphabet for test number [n] (n in 0..999999). */
fun testReqId(n: Int): String = "t" + n.toString().padStart(6, '0') + "a"

/**
 * A [CallLogStore] that records each appended batch, can be gated (suspends until [release]) and can be told to throw
 * on its first call. [append] returns the number of records it was given.
 */
class RecordingCallLogStore(
    private val gate: CompletableDeferred<Unit>? = null,
    private val failFirstCalls: Int = 0
) : CallLogStore {
    val batches = CopyOnWriteArrayList<List<CallLogRecord>>()
    private val calls = AtomicInteger()

    /** The number of append calls that have started (counted before the gate is awaited). */
    val entered = AtomicInteger()

    val records: List<CallLogRecord> get() = batches.flatten()

    fun release() {
        gate?.complete(Unit)
    }

    override suspend fun append(records: List<CallLogRecord>): Int {
        entered.incrementAndGet()
        gate?.await()
        if (calls.incrementAndGet() <= failFirstCalls) error("simulated store failure")
        batches += records.toList()
        return records.size
    }
}

/** A sink that remembers every submitted record. */
class CollectingSink : CallLogSink {
    val records = CopyOnWriteArrayList<CallLogRecord>()

    override fun submit(record: CallLogRecord) {
        records += record
    }
}

/** One captured log event: level, formatted message, logger name and the MDC map snapshotted at append time. */
data class LogSnapshot(
    val level: Level,
    val logger: String,
    val message: String,
    val mdc: Map<String, String>
)

/**
 * Captures log events with the MDC read at append() time (logback reads it lazily otherwise, which returns the wrong
 * map once a coroutine has unwound its MDC scope). Attach to a named logger (or the root logger) with [attach].
 */
class LogCapture : AppenderBase<ILoggingEvent>() {
    val events = CopyOnWriteArrayList<LogSnapshot>()
    private var attachedTo: Logger? = null
    private var savedLevel: Level? = null

    override fun append(eventObject: ILoggingEvent) {
        events +=
            LogSnapshot(eventObject.level, eventObject.loggerName, eventObject.formattedMessage, eventObject.mdcPropertyMap ?: emptyMap())
    }

    fun attach(loggerName: String = Logger.ROOT_LOGGER_NAME): LogCapture {
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        savedLevel = logger.level
        logger.level = Level.ALL
        start()
        logger.addAppender(this)
        attachedTo = logger
        return this
    }

    fun detach() {
        attachedTo?.let {
            it.detachAppender(this)
            it.level = savedLevel
        }
        attachedTo = null
        stop()
    }
}
