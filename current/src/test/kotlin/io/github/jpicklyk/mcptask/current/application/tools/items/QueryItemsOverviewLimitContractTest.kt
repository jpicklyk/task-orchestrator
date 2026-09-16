package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.repository.ItemFetchResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract tests for bug `1a04106a` — `query_items`'s `validateParams` "overview" arm never
 * mirrored the search arm's floor check on `limit`. Global overview passed `limit=0` straight to
 * `findRootItems`, producing a silent, misleadingly-successful empty page (SQL `LIMIT 0`).
 * Anchored overview applied `limit` via `Iterable.take(n)`, which requires `n >= 0`, so
 * `limit=-1` crashed inside `execute()` as an uncaught `IllegalArgumentException` — surfaced to
 * callers as a generic "Internal error" rather than the clean `ToolValidationException` shape the
 * search arm already provides for the same class of bad input. Scoped overview (`itemId` set)
 * never reads `limit` at all and must keep ignoring it — the fix must not become an unauthorized
 * tightening of that mode.
 *
 * Fix (per `diagnosis`): in the overview arm, when `itemId` is absent (anchored or global mode),
 * read `limit` and throw `ToolValidationException("limit must be at least 1")` — the same message
 * family the search arm already throws — if it is non-null and `< 1`. Scoped mode is exempt.
 *
 * All nine scenarios are EXISTING-SURFACE per the frozen `test-plan`: the fix adds a branch inside
 * the existing `validateParams` overview arm, reusing the existing `ToolValidationException` type
 * and `optionalInt` helper — no new type, parameter, or enum constant. A plain revert of the fix
 * yields real behavioral red for every failure-mode scenario below.
 *
 * Per `McpToolAdapter.kt:58-90`, `validateParams()` runs in the MCP adapter BEFORE `execute()`;
 * only exceptions `validateParams()` throws become the clean `ToolValidationException`/
 * "Validation error" shape — anything `execute()` throws instead falls through to the adapter's
 * outer catch as "Internal error". S1-S8 therefore call `validateParams()` directly (mirroring
 * `QueryItemsLimitContractTest.kt`'s S3 precedent) so they observe the actual contract surface;
 * calling `execute()` alone could never observe this fix's rejection. S9 is the sole execute()-level
 * scenario, confirming the fix does not disturb valid overview input.
 *
 * Harness mirrors `QueryItemsLimitContractTest.kt` — [MockRepositoryProvider] + MockK
 * `coEvery`/`coVerify`, no FTS5/H2 dependency.
 */
class QueryItemsOverviewLimitContractTest {
    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    // ──────────────────────────────────────────────
    // S1 / S2 — global overview (no itemId, no anchorId): limit=0 and limit=-1 are rejected
    // ──────────────────────────────────────────────

    @Test
    fun `S1 global overview limit=0 is rejected by validateParams`() {
        val tool = QueryItemsTool()

        val exception =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "limit" to JsonPrimitive(0),
                    ),
                )
            }

        assertTrue(
            "limit must be at least 1" in exception.message.orEmpty(),
            "expected the search-arm message family, got: ${exception.message}",
        )
    }

    @Test
    fun `S2 global overview limit=-1 is rejected by validateParams`() {
        val tool = QueryItemsTool()

        val exception =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "limit" to JsonPrimitive(-1),
                    ),
                )
            }

        assertTrue(
            "limit must be at least 1" in exception.message.orEmpty(),
            "expected the search-arm message family, got: ${exception.message}",
        )
    }

    // ──────────────────────────────────────────────
    // S3 / S4 — anchored overview (anchorId set): limit=0 and limit=-1 are rejected
    //
    // Pre-fix red-proof note for S4: negative limit did NOT throw out of validateParams before the
    // fix — it crashed later inside execute() via `Iterable.take(-1)`. A plain revert therefore
    // makes this assertFailsWith see no exception at all (validateParams returns normally), which
    // is genuine behavioral red for this scenario, not a compile failure.
    // ──────────────────────────────────────────────

    @Test
    fun `S3 anchored overview limit=0 is rejected by validateParams`() {
        val tool = QueryItemsTool()
        val anchorId = UUID.randomUUID().toString()

        val exception =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "anchorId" to JsonPrimitive(anchorId),
                        "limit" to JsonPrimitive(0),
                    ),
                )
            }

        assertTrue(
            "limit must be at least 1" in exception.message.orEmpty(),
            "expected the search-arm message family, got: ${exception.message}",
        )
    }

    @Test
    fun `S4 anchored overview limit=-1 is rejected by validateParams`() {
        val tool = QueryItemsTool()
        val anchorId = UUID.randomUUID().toString()

        val exception =
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "anchorId" to JsonPrimitive(anchorId),
                        "limit" to JsonPrimitive(-1),
                    ),
                )
            }

        assertTrue(
            "limit must be at least 1" in exception.message.orEmpty(),
            "expected the search-arm message family, got: ${exception.message}",
        )
    }

    // ──────────────────────────────────────────────
    // S5 / S6 — boundary: limit=1 does not throw for global or anchored overview
    // ──────────────────────────────────────────────

    @Test
    fun `S5 global overview limit=1 does not throw`() {
        val tool = QueryItemsTool()

        tool.validateParams(
            params(
                "operation" to JsonPrimitive("overview"),
                "limit" to JsonPrimitive(1),
            ),
        )
        // Reaching here without an exception is the assertion: the floor is "< 1", so 1 is valid.
    }

    @Test
    fun `S6 anchored overview limit=1 does not throw`() {
        val tool = QueryItemsTool()
        val anchorId = UUID.randomUUID().toString()

        tool.validateParams(
            params(
                "operation" to JsonPrimitive("overview"),
                "anchorId" to JsonPrimitive(anchorId),
                "limit" to JsonPrimitive(1),
            ),
        )
    }

    // ──────────────────────────────────────────────
    // S7 / S8 — adversarial: scoped overview (itemId set) keeps ignoring limit, even at 0 or -1.
    // Guards against the fix overreaching into the one mode the decision anchor exempts.
    // ──────────────────────────────────────────────

    @Test
    fun `S7 scoped overview limit=-1 does not throw`() {
        val tool = QueryItemsTool()
        val itemId = UUID.randomUUID().toString()

        tool.validateParams(
            params(
                "operation" to JsonPrimitive("overview"),
                "itemId" to JsonPrimitive(itemId),
                "limit" to JsonPrimitive(-1),
            ),
        )
    }

    @Test
    fun `S8 scoped overview limit=0 does not throw`() {
        val tool = QueryItemsTool()
        val itemId = UUID.randomUUID().toString()

        tool.validateParams(
            params(
                "operation" to JsonPrimitive("overview"),
                "itemId" to JsonPrimitive(itemId),
                "limit" to JsonPrimitive(0),
            ),
        )
    }

    // ──────────────────────────────────────────────
    // S9 — happy path (execute()-level): global overview with a valid limit still succeeds and
    // still forwards `limit` to `findRootItems` unchanged. Confirms the fix does not disturb valid
    // input; mirrors QueryItemsLimitContractTest.kt's S8 harness shape.
    // ──────────────────────────────────────────────

    @Test
    fun `S9 global overview limit=1 succeeds and forwards limit to findRootItems`() =
        runBlocking {
            val mocks = MockRepositoryProvider()

            coEvery {
                mocks.workItemRepo.findRootItems(limit = 1, offset = 0, excludeTerminal = false)
            } returns Result.Success(ItemFetchResult(items = emptyList(), skipped = 0))
            coEvery { mocks.workItemRepo.countRootItems(any()) } returns Result.Success(0L)

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "limit" to JsonPrimitive(1),
                    ),
                    mocks.context(),
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            coVerify(exactly = 1) {
                mocks.workItemRepo.findRootItems(limit = 1, offset = 0, excludeTerminal = false)
            }
        }
}
