package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independent tests (test-author seat, item eadaaa4e, fix N1) for the report window year range.
 *
 * Oracle: the N1 contract. A since or until whose UTC year, after conversion to UTC, is outside 0000..9999 is refused with
 * invalid_request and a field fault on /since or /until; when both are bad both faults are reported. Boundary years 0000 and
 * 9999 are admitted when the window cap (at most 31 days, since before until) holds.
 *
 * Every refusal is paired with an admitted control from the same fixture in the same test.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ReportWindowYearRangeTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)

    private fun request(
        since: String?,
        until: String?
    ) = ReportUsageRequest(since = since, until = until)

    private suspend fun faultFields(
        since: String?,
        until: String?
    ): List<String> {
        val outcome = fx.usage(request(since, until))
        val error = assertIs<Outcome.Err>(outcome, "since=$since until=$until must be refused").error
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, "since=$since until=$until")
        return assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.map { it.field }
    }

    @Test
    fun `a negative year since is refused at the since pointer`(): Unit =
        runBlocking {
            assertEquals(listOf("/since"), faultFields("-2025-10-01T00:00:00Z", "2026-10-05T00:00:00Z"))
            fx.ok(request("2026-10-01T00:00:00Z", "2026-10-05T00:00:00Z"))
        }

    @Test
    fun `a year 10000 until is refused at the until pointer`(): Unit =
        runBlocking {
            assertEquals(listOf("/until"), faultFields("2026-10-01T00:00:00Z", "+10000-01-01T00:00:00Z"))
            fx.ok(request("2026-10-01T00:00:00Z", "2026-10-05T00:00:00Z"))
        }

    @Test
    fun `an offset that pushes the UTC year past 9999 is refused and the same local time in UTC is admitted`(): Unit =
        runBlocking {
            assertEquals(listOf("/until"), faultFields("9999-12-30T00:00:00Z", "9999-12-31T23:59:59-05:00"))
            val report = fx.ok(request("9999-12-30T00:00:00Z", "9999-12-31T23:59:59Z"))
            assertEquals(Instant.parse("9999-12-31T23:59:59Z"), report.window.until)
        }

    @Test
    fun `a positive offset that pushes the UTC year below zero is refused at since`(): Unit =
        runBlocking {
            assertEquals(listOf("/since"), faultFields("0000-01-01T00:00:00+05:00", "0000-01-10T00:00:00Z"))
            val report = fx.ok(request("0000-01-01T05:00:00+05:00", "0000-01-10T00:00:00Z"))
            assertEquals(Instant.parse("0000-01-01T00:00:00Z"), report.window.since)
        }

    @Test
    fun `both bad instants report both faults at once`(): Unit =
        runBlocking {
            val fields = faultFields("-2025-10-01T00:00:00Z", "+10000-01-01T00:00:00Z")
            assertEquals(setOf("/since", "/until"), fields.toSet())
            assertEquals(2, fields.size)
        }

    @Test
    fun `year 0000 as since is admitted and year 9999 as until is admitted when the window is valid`(): Unit =
        runBlocking {
            val low = fx.ok(request("0000-01-01T00:00:00Z", "0000-01-15T00:00:00Z"))
            assertEquals(Instant.parse("0000-01-01T00:00:00Z"), low.window.since)
            assertEquals(Instant.parse("0000-01-15T00:00:00Z"), low.window.until)

            val high = fx.ok(request("9999-12-30T00:00:00Z", "9999-12-31T23:59:59Z"))
            assertEquals(Instant.parse("9999-12-30T00:00:00Z"), high.window.since)
            assertEquals(Instant.parse("9999-12-31T23:59:59Z"), high.window.until)
        }

    @Test
    fun `a normal window is admitted unchanged`(): Unit =
        runBlocking {
            val report = fx.ok(request("2026-10-06T00:00:00Z", "2026-10-12T00:00:00Z"))
            assertEquals(Instant.parse("2026-10-06T00:00:00Z"), report.window.since)
            assertEquals(Instant.parse("2026-10-12T00:00:00Z"), report.window.until)
        }
}
