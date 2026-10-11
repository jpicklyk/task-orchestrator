package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Route-level wiring tests for item `e941c2c7`: the already-capped route (`PUT /roots/{rootId}/plans/{slug}`; the
 * `PUT /roots/{rootId}/config` route moved to the `config` tool in Q23) goes through [receiveBounded], keeps its own unchanged numeric cap, and shares
 * the one `payload_too_large` ErrorDto shape. The JSON item write routes these tests also covered are gone (Q13 moved
 * advance to the `advance` tool, Q18 moved item create/update/delete to `items_write`); the bytes-consumed proof
 * (stage 1 vs. stage 2) lives in [BoundedReceiveTest] against a small synthetic cap.
 */
private const val MAX_PLAN_DOCUMENT_BODY_BYTES = 65_536 // PlanDocumentService.MAX_BODY_BYTES — unchanged by this item

/** A declared length that disagrees with what [actualBytes] really delivers. */
private class RouteMismatchedLengthContent(
    override val contentLength: Long,
    override val contentType: ContentType,
    private val actualBytes: ByteArray,
) : OutgoingContent.ReadChannelContent() {
    override fun readFrom(): ByteReadChannel = ByteReadChannel(actualBytes)
}

private fun createRoot(repo: io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider): WorkItem =
    runBlocking {
        (repo.workItemRepository().create(WorkItem(title = "Root", type = "project", depth = 0))!!)
    }

class WriteRoutesBodyLimitTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // S6 regression, folded into the same wiring proof: the ALREADY-capped plan-document route keeps its
    // own unchanged numeric cap and goes through receiveBounded (same shared shape).
    // The existing "over the size cap returns 413" test in PlanDocumentRoutesTest.kt:174 already asserts the
    // numeric-cap regression with an accurate Content-Length; this one asserts the SAME cap via a lying
    // Content-Length, proving the receiveBounded wiring reached the route without disturbing its cap value.

    @Test
    fun `PUT roots rootId plans slug with an overstated Content-Length is rejected 413`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo, unitOfWork = db.unitOfWork()) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    setBody(
                        RouteMismatchedLengthContent(
                            contentLength = MAX_PLAN_DOCUMENT_BODY_BYTES + 1L,
                            contentType = ContentType.Text.Plain,
                            actualBytes = byteArrayOf('x'.code.toByte()),
                        ),
                    )
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"))
            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertTrue(persisted == null)
        }
}
