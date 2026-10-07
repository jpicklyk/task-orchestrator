package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryParamsTest {
    private val id = UUID.randomUUID()

    @Test
    fun `absent and blank are Absent for every parser`() {
        for (raw in listOf(null, "", "   ")) {
            assertEquals(QueryParam.Absent, parseUuidParam("parentId", raw))
            assertEquals(QueryParam.Absent, parseInstantParam("since", raw))
            assertEquals(QueryParam.Absent, parseRoleParam("role", raw))
            assertEquals(QueryParam.Absent, parsePriorityParam("priority", raw))
            assertEquals(QueryParam.Absent, parseClaimStatusParam("claimStatus", raw))
            assertEquals(QueryParam.Absent, parseNonNegativeIntParam("depth", raw))
        }
    }

    @Test
    fun `valid values parse`() {
        assertEquals(QueryParam.Present(id), parseUuidParam("parentId", id.toString()))
        assertEquals(
            QueryParam.Present(Instant.parse("2025-01-01T00:00:00Z")),
            parseInstantParam("since", "2025-01-01T00:00:00Z")
        )
        assertEquals(QueryParam.Present(Role.WORK), parseRoleParam("role", "WORK"))
        assertEquals(QueryParam.Present(Role.WORK), parseRoleParam("role", "work"))
        assertEquals(QueryParam.Present(Priority.HIGH), parsePriorityParam("priority", "High"))
        assertEquals(QueryParam.Present("claimed"), parseClaimStatusParam("claimStatus", "CLAIMED"))
        assertEquals(QueryParam.Present(0), parseNonNegativeIntParam("depth", "0"))
        assertEquals(QueryParam.Present(3), parseNonNegativeIntParam("depth", "3"))
    }

    @Test
    fun `invalid values are Invalid and name the parameter and echo the value`() {
        val cases =
            listOf(
                parseUuidParam("parentId", "xyz") to "Invalid parentId 'xyz': must be a UUID",
                parseInstantParam("since", "yesterday") to "Invalid since 'yesterday': must be an ISO-8601 instant",
                parseRoleParam("role", "done") to "Invalid role 'done': expected one of queue, work, review, blocked, terminal",
                parsePriorityParam("priority", "critical") to "Invalid priority 'critical': expected one of high, medium, low",
                parseClaimStatusParam("claimStatus", "bogus") to "Invalid claimStatus 'bogus': expected one of claimed, unclaimed, expired",
                parseNonNegativeIntParam("depth", "abc") to "Invalid depth 'abc': must be a non-negative integer",
                parseNonNegativeIntParam("depth", "-1") to "Invalid depth '-1': must be a non-negative integer",
            )
        for ((result, message) in cases) {
            assertEquals(QueryParam.Invalid(message), result)
        }
    }

    @Test
    fun `echoed value is truncated to 100 characters`() {
        val result = parseUuidParam("parentId", "x".repeat(500))
        val invalid = assertIs<QueryParam.Invalid>(result)
        assertTrue(invalid.message.length < 200, invalid.message)
        assertTrue(!invalid.message.contains("x".repeat(101)), invalid.message)
    }
}
