package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * P8 review finding 1, REST path: `POST /items/{id}/advance` with an `Idempotency-Key` runs the advance inside the
 * key's unit, which rolls back on the non-2xx gate rejection. The `transition.rejected` row must still be recorded,
 * exactly once per attempt: a retry with the same key re-executes (a gate rejection is not a stored response) and
 * records exactly one more; a request without the key records exactly one.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RestAdvanceRejectionRowTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val gate: WorkItemSchemaService =
        object : WorkItemSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) listOf(NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true)) else null
        }

    private suspend fun rejectedRows() =
        db
            .repositoryProvider()
            .eventStore()
            .readAfter(0L, null, 10_000)
            .filter { it.type == DomainEvent.TRANSITION_REJECTED }

    private suspend fun ApplicationTestBuilder.advance(
        itemId: UUID,
        key: String?,
    ): HttpResponse =
        client.post("/api/v1/items/$itemId/advance") {
            header("Authorization", "Bearer $WRITE_TOKEN")
            if (key != null) header("Idempotency-Key", key)
            contentType(ContentType.Application.Json)
            setBody("""{"trigger":"start"}""")
        }

    @Test
    fun `keyed REST advance that is gate-blocked records exactly one transition rejected row per attempt`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(
                        WorkItem(title = "Gated", role = Role.QUEUE, depth = 0, tags = "feature-task")
                    )
                }
            application { configureWriteTestApp(repo, schemaService = gate, unitOfWork = db.unitOfWork()) }
            val key = UUID.randomUUID().toString()

            val first = advance(item.id, key)
            val body = first.bodyAsText()
            assertFalse(first.status.value in 200..299, "the advance must be rejected: ${first.status} $body")
            assertEquals(1, rejectedRows().size, "the key's unit rolled back; its rejection row must survive: $body")

            advance(item.id, key)
            assertEquals(2, rejectedRows().size, "the retry with the same key re-executes and records one more")

            advance(item.id, null)
            assertEquals(3, rejectedRows().size, "an unkeyed rejection records exactly one row")
            assertEquals(Role.QUEUE, repo.workItemRepository().getById(item.id)!!.role)
        }
}
