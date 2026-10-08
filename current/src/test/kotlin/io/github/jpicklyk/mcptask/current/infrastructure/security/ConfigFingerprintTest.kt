package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.ProjectConfigTable
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies [configFingerprint] / [normalizeConfigForFingerprint] against the fixture shared with
 * the plugin-side JS test (`hooks/tests/config-sync.test.mjs`) — both implementations MUST hash
 * identically for the same BOM/CRLF inputs. See `current/src/test/resources/fixtures/config-fingerprint-vectors.json`.
 *
 * Also covers the repository-level guarantees from the run plan's binding decision: the stored
 * config body is never normalized (only the value fed into the hash changes), and the #338
 * compare-and-set-in-transaction guard logic is untouched by this change.
 */
class ConfigFingerprintTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private data class Vector(
        val name: String,
        val input: String,
        val expectedSha256: String
    )

    private fun loadVectors(): List<Vector> {
        val stream =
            requireNotNull(javaClass.getResourceAsStream("/fixtures/config-fingerprint-vectors.json")) {
                "fixture not found on classpath: /fixtures/config-fingerprint-vectors.json"
            }
        val text = stream.readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonArray
        return root.map {
            val obj = it.jsonObject
            Vector(
                name = obj.getValue("name").jsonPrimitive.content,
                input = obj.getValue("input").jsonPrimitive.content,
                expectedSha256 = obj.getValue("expectedSha256").jsonPrimitive.content
            )
        }
    }

    @Test
    fun `configFingerprint matches every shared fixture vector`() {
        val vectors = loadVectors()
        assertTrue(vectors.isNotEmpty(), "fixture must not be empty")
        for (vector in vectors) {
            assertEquals(
                vector.expectedSha256,
                configFingerprint(vector.input),
                "vector \"${vector.name}\" mismatched"
            )
        }
    }

    @Test
    fun `SQLiteProjectConfigRepository computeFingerprint matches every shared fixture vector`() =
        runBlocking {
            val database = sqliteDb.database
            val databaseManager = sqliteDb.databaseManager
            val repository = SQLiteProjectConfigRepository(databaseManager)

            for (vector in loadVectors()) {
                assertEquals(
                    vector.expectedSha256,
                    repository.computeFingerprint(vector.input),
                    "vector \"${vector.name}\" mismatched"
                )
            }
        }

    // --- S12/S13: repository-level guarantees (stored body unchanged; #338 guard intact) ---

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            database = sqliteDb.database
            databaseManager = sqliteDb.databaseManager
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    @Test
    fun `S12 upserting CRLF plus BOM body classifies the LF variant as current and stores the original bytes unchanged`() =
        runBlocking {
            val crlfBomBody = "a: 1\r\nb: 2\r\n"
            val lfVariant = "a: 1\nb: 2\n"

            val upserted = projectConfigRepository.upsert(rootItemId, crlfBomBody)
            assertNotNull(upserted)

            val stored = projectConfigRepository.get(rootItemId)
            assertNotNull(stored)
            val config = stored
            assertEquals(crlfBomBody, config?.configYaml, "stored body must be byte-for-byte unchanged")

            val lfFingerprint = projectConfigRepository.computeFingerprint(lfVariant)
            val relation = projectConfigRepository.classifyFingerprint(rootItemId, lfFingerprint)
            assertNotNull(relation)
            assertEquals(FingerprintRelation.CURRENT, relation)
        }

    @Test
    fun `S13 guarded push of identical CRLF content succeeds unknown then a stale raw If-Match fails precondition`(): Unit =
        runBlocking {
            val crlfBody = "a: 1\r\nb: 2\r\n"
            val normalizedFingerprint = configFingerprint(crlfBody)
            val rawFingerprint = sha256Hex(crlfBody.toByteArray(Charsets.UTF_8))
            assertTrue(
                rawFingerprint != normalizedFingerprint,
                "fixture body must actually exercise the CRLF normalization gap"
            )

            // Seed a row via the normal (already-covered) create path, then directly overwrite its
            // fingerprint column to the OLD raw (pre-normalization) value, simulating a row written
            // by a pre-change server -- the config body itself stays exactly what a pre-change
            // server would have stored (raw CRLF bytes, never normalized).
            val seeded = projectConfigRepository.upsert(rootItemId, crlfBody)
            assertNotNull(seeded)
            suspendTransaction(db = database) {
                ProjectConfigTable.update({ ProjectConfigTable.rootItemId eq rootItemId }) {
                    it[ProjectConfigTable.fingerprint] = rawFingerprint
                }
            }

            // Re-push the same CRLF content with no If-Match precondition. The observed fingerprint
            // (raw) matches neither the pushed fingerprint (normalized) nor anything in history, so
            // the relation is UNKNOWN, not SUPERSEDED -- the push is accepted per the run plan's
            // rollout semantics (one unknown relation + re-push per affected root).
            val rePush =
                projectConfigRepository.upsertGuarded(rootItemId, crlfBody, expectedFingerprint = null, rejectSuperseded = true)
            assertNotNull(rePush)
            val applied =
                assertIs<io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome.Applied>(
                    rePush
                )
            assertEquals(normalizedFingerprint, applied.config.fingerprint, "fingerprint must become the normalized value")

            val afterRePush = projectConfigRepository.get(rootItemId)
            assertNotNull(afterRePush)
            val storedFingerprintHistory = afterRePush
            assertEquals(crlfBody, storedFingerprintHistory?.configYaml, "stored body must remain byte-for-byte unchanged")

            val relationAfterRePush = projectConfigRepository.classifyFingerprint(rootItemId, rawFingerprint)
            assertNotNull(relationAfterRePush)
            assertEquals(
                FingerprintRelation.SUPERSEDED,
                relationAfterRePush,
                "the pre-change raw fingerprint must now be in history"
            )

            // A guarded push using the stale RAW fingerprint as the If-Match precondition must fail --
            // the CAS `where fingerprint eq observedFingerprint` in #338's attemptGuardedUpsert is
            // unchanged; only the computed fingerprint value differs now.
            val staleIfMatch =
                projectConfigRepository.upsertGuarded(
                    rootItemId,
                    crlfBody,
                    expectedFingerprint = rawFingerprint,
                    rejectSuperseded = false
                )
            assertNotNull(staleIfMatch)
            val outcome = staleIfMatch
            assertIs<io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome.PreconditionFailed>(outcome)
        }
}
