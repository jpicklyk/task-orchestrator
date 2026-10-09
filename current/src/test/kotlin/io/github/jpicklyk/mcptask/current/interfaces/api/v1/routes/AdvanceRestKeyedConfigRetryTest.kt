package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11Driver.Companion.transitioned
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.breakConfigReads
import io.github.jpicklyk.mcptask.current.test.configFaultFixture
import io.github.jpicklyk.mcptask.current.test.rawExec
import io.github.jpicklyk.mcptask.current.test.snapshot
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
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

/**
 * Independent P11 tests (item 919d379e, round r2), REST: a keyed `POST /items/{id}/advance` that answered
 * `503 config_unavailable` is not recorded for `Idempotency-Key` replay, so the retry with the same key and body after the
 * fault clears EXECUTES (200, no `Idempotent-Replayed` header, one row, one event).
 *
 * Oracle: plan v4-phase1-core.md section 3.9 (only ok results are stored; transient failures are not) and api-rest.md
 * idempotency section (replay is a stored response returned with `Idempotent-Replayed: true`; a config or database fault is not recorded).
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AdvanceRestKeyedConfigRetryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun Application.configureApp(rig: EventLogRig) {
        val ctx = rig.ctx
        val uow = rig.composition.unitOfWork
        configureTestApp(makeWriteAuthConfig()) {
            itemWriteRoutes(rig.provider, DegradedModePolicy.ACCEPT_CACHED, IdempotencyService(uow), ctx.advanceServiceFactory(), uow)
        }
    }

    @Test
    fun `a keyed REST advance that answered 503 config_unavailable re-executes on retry with the same key`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val f = d.configFaultFixture()
            val before = d.snapshot(f.item)
            application { configureApp(d.rig) }
            val key = UUID.randomUUID().toString()

            suspend fun send() =
                client.post("/api/v1/items/${f.item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header("Idempotency-Key", key)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }

            d.breakConfigReads()
            val first = send()
            assertEquals(HttpStatusCode.ServiceUnavailable, first.status, first.bodyAsText())
            assertEquals(before, d.snapshot(f.item), "fail-closed: nothing changed")

            rawExec(d.jdbcUrl, "ALTER TABLE project_config_gone RENAME TO project_config")
            val retry = send()

            assertEquals(HttpStatusCode.OK, retry.status, "the retry EXECUTES instead of replaying the 503: ${retry.bodyAsText()}")
            assertNull(retry.headers["Idempotent-Replayed"], "an executed retry is not a replay")
            assertEquals(Role.WORK, d.role(f.item))
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size, "exactly one row")
            assertEquals(
                1,
                d.rig
                    .rows()
                    .transitioned()
                    .size,
                "exactly one event"
            )

            val again = send()
            assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
            assertEquals("true", again.headers["Idempotent-Replayed"], "control: once stored, the third identical call replays")
            assertEquals(before.transitionRows + 1, d.transitions(f.item).size, "the replay executes nothing")
        }
}
