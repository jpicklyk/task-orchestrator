package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.snapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent P11 r1 tests (item 919d379e), REST surface, for fix-decision H1: `POST /items/{id}/advance` resolves the
 * item's per-root config inside the advance's write unit and `GET /items/{id}/gate` inside its preview's read unit; neither
 * has a last-known-good, so a config read fault answers `503 config_unavailable` (no `Retry-After`) even with a warm cache and
 * the advance changes nothing.
 *
 * Oracles: fix-decisions-r1 H1; api-rest.md `config_unavailable` row of the error table and the 503 bullets of the
 * `GET /items/{id}/gate` and `POST /items/{id}/advance` responses (idempotency via the `Idempotency-Key` header, section 5).
 * The fault is real (project_config renamed away after the cache is warm); the control test proves the same fixture advances.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceConfigFailClosedRestTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun Application.configureApp(rig: EventLogRig) {
        val ctx = rig.ctx
        val uow = rig.composition.unitOfWork
        configureTestApp(makeWriteAuthConfig()) {
            itemGateRoutes(rig.provider, ctx.configResolver, ctx.transitionPreview())
            itemWriteRoutes(rig.provider, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(uow), ctx.advanceServiceFactory(), uow)
        }
    }

    private fun driver(dir: Path) = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))

    @Test
    fun `H1 control - with healthy config REST advance returns 200 and moves the item to work`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = driver(dir)
            val f = d.configFaultFixture()
            application { configureApp(d.rig) }

            val response =
                client.post("/api/v1/items/${f.item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals(Role.WORK, d.role(f.item))
        }

    @Test
    fun `H1 REST advance answers 503 config_unavailable with a warm cache and changes nothing`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = driver(dir)
            val f = d.configFaultFixture()
            application { configureApp(d.rig) }
            val before = d.snapshot(f.item)
            d.breakConfigReads()

            val response =
                client.post("/api/v1/items/${f.item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(before, d.snapshot(f.item), "no role change, no transition row, no event: ${response.bodyAsText()}")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("config_unavailable"), response.bodyAsText())
            assertNull(response.headers["Retry-After"], "no Retry-After on a config_unavailable 503")
        }

    @Test
    fun `H1 a keyed REST advance answers 503 config_unavailable with a warm cache and changes nothing`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = driver(dir)
            val f = d.configFaultFixture()
            application { configureApp(d.rig) }
            val before = d.snapshot(f.item)
            d.breakConfigReads()

            val response =
                client.post("/api/v1/items/${f.item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("config_unavailable"), response.bodyAsText())
            assertNull(response.headers["Retry-After"], "no Retry-After on a config_unavailable 503")
            assertEquals(before, d.snapshot(f.item), "no role change, no transition row, no event")
        }

    @Test
    fun `H1 REST gate answers 503 config_unavailable with a warm cache`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = driver(dir)
            val f = d.configFaultFixture()
            application { configureApp(d.rig) }
            d.breakConfigReads()

            val response = client.get("/api/v1/items/${f.item.id}/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains("config_unavailable"), response.bodyAsText())
            assertNull(response.headers["Retry-After"], "no Retry-After on a config_unavailable 503")
        }
}
