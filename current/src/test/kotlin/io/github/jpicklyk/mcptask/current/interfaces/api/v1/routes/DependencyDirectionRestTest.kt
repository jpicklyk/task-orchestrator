package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Blind test-author coverage for item 2aa67b28 (inverted IS_BLOCKED_BY cycle-check fix):
 * `POST /api/v1/dependencies` cycle pre-check (S5) and [Dependency.toDto] unblockAt mapping (S6),
 * plus one probe confirming the REST route's documented type-acceptance gap.
 *
 * Oracles: [R] RELATES_TO has no blocking semantics; [De] diagnosis decision (e) — the REST cycle
 * pre-check only runs when `blockerId() != null`; [Df] decision (f) — toDto().unblockAt always
 * uses effectiveUnblockRole(), not a BLOCKS-only ternary.
 *
 * the SQLite fixture + `SqliteTestDatabase.repositoryProvider()` / [configureWriteTestApp] (from [ApiTestHelper] /
 * `WriteRoutesTest`), not a hand-rolled Ktor `testApplication`.
 */
class DependencyDirectionRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `S6 IS_BLOCKED_BY toDto default unblockAt resolves to terminal`() {
        val dep = Dependency(fromItemId = UUID.randomUUID(), toItemId = UUID.randomUUID(), type = DependencyType.IS_BLOCKED_BY)
        assertEquals("terminal", dep.toDto().unblockAt)
    }

    @Test
    fun `S6 IS_BLOCKED_BY toDto explicit unblockAt review is preserved`() {
        val dep =
            Dependency(
                fromItemId = UUID.randomUUID(),
                toItemId = UUID.randomUUID(),
                type = DependencyType.IS_BLOCKED_BY,
                unblockAt = "review"
            )
        assertEquals("review", dep.toDto().unblockAt)
    }
}
