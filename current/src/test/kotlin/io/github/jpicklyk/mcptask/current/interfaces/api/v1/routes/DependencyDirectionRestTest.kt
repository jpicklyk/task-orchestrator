package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blind test-author coverage for item 2aa67b28 (inverted IS_BLOCKED_BY cycle-check fix):
 * `POST /api/v1/dependencies` cycle pre-check (S5) and [Dependency.toDto] unblockAt mapping (S6),
 * plus one probe confirming the REST route's documented type-acceptance gap.
 *
 * Oracles: [R] RELATES_TO has no blocking semantics; [De] diagnosis decision (e) — the REST cycle
 * pre-check only runs when `blockerId() != null`; [Df] decision (f) — toDto().unblockAt always
 * uses effectiveUnblockRole(), not a BLOCKS-only ternary.
 *
 * H2 + [buildH2RepositoryProvider] / [configureWriteTestApp] (from [ApiTestHelper] /
 * `WriteRoutesTest`), not a hand-rolled Ktor `testApplication`.
 */
class DependencyDirectionRestTest {
    @Test
    fun `S5 POST relates_to succeeds even though a stored BLOCKS edge exists between the same items`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) =
                runBlocking {
                    val x = repo.workItemRepository().create(WorkItem(title = "A", depth = 0)).getOrNull()!!
                    val y = repo.workItemRepository().create(WorkItem(title = "B", depth = 0)).getOrNull()!!
                    // A BLOCKS B already stored
                    repo.dependencyRepository().create(
                        Dependency(fromItemId = x.id, toItemId = y.id, type = DependencyType.BLOCKS)
                    )
                    Pair(x, y)
                }
            application { configureWriteTestApp(repo) }

            // B relates_to A — RELATES_TO has no blocking semantics and must not be cycle-checked
            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"relates_to"}""")
                }

            assertEquals(
                HttpStatusCode.Created,
                response.status,
                "relates_to must not be rejected by the cycle pre-check: ${response.bodyAsText()}"
            )

            val persisted =
                runBlocking {
                    withContext(Dispatchers.IO) {
                        repo.dependencyRepository().findByItemId(b.id)
                    }
                }
            assertTrue(
                persisted.any { it.type == DependencyType.RELATES_TO && it.fromItemId == b.id && it.toItemId == a.id },
                "relates_to edge should be persisted"
            )
        }

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

    @Test
    fun `probe REST POST rejects lower-case is_blocked_by type (blocks and relates_to only)`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (a, b) =
                runBlocking {
                    val x = repo.workItemRepository().create(WorkItem(title = "A", depth = 0)).getOrNull()!!
                    val y = repo.workItemRepository().create(WorkItem(title = "B", depth = 0)).getOrNull()!!
                    Pair(x, y)
                }
            application { configureWriteTestApp(repo) }

            val response =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"is_blocked_by"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("validation_error"), "REST has no is_blocked_by branch: $body")
        }
}
