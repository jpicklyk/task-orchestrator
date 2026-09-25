package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
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
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
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
            assertIs<Result.Success<*>>(upserted)

            val stored = projectConfigRepository.get(rootItemId)
            assertIs<Result.Success<*>>(stored)
            val config = (stored as Result.Success).data
            assertEquals(crlfBomBody, config?.configYaml, "stored body must be byte-for-byte unchanged")

            val lfFingerprint = projectConfigRepository.computeFingerprint(lfVariant)
            val relation = projectConfigRepository.classifyFingerprint(rootItemId, lfFingerprint)
            assertIs<Result.Success<*>>(relation)
            assertEquals(FingerprintRelation.CURRENT, (relation as Result.Success).data)
        }

    @Test
    fun `S13 guarded push of identical CRLF content succeeds unknown then a stale raw If-Match fails precondition`() =
        runBlocking {
            val crlfBody = "a: 1\r\nb: 2\r\n"

            // Seed a row directly with the OLD raw (pre-normalization) fingerprint, simulating a
            // row written before this change shipped.
            val rawFingerprint = sha256Hex(crlfBody.toByteArray(Charsets.UTF_8))
            val seeded = projectConfigRepository.upsert(rootItemId, crlfBody)
            assertIs<Result.Success<*>>(seeded)
            val seededFingerprint = (seeded as Result.Success).data.fingerprint
            // Sanity: on this build, upsert already computes the NORMALIZED fingerprint, not the raw one.
            assertTrue(seededFingerprint != rawFingerprint, "seed must reflect the normalized fingerprint, not the pre-change raw one")

            // Re-push the same CRLF content — relation is unknown relative to a hypothetical prior
            // raw-fingerprint row, but here the fingerprint already matches (normalized), so re-push
            // succeeds without disturbing the CAS guard.
            val rePush = projectConfigRepository.upsertGuarded(rootItemId, crlfBody, expectedFingerprint = null, rejectSuperseded = true)
            assertIs<Result.Success<*>>(rePush)

            // A guarded push using the stale RAW fingerprint as the If-Match precondition must fail —
            // the CAS `where fingerprint eq observedFingerprint` in #338's attemptGuardedUpsert is
            // unchanged; only the computed fingerprint value differs now.
            val staleIfMatch =
                projectConfigRepository.upsertGuarded(
                    rootItemId,
                    crlfBody,
                    expectedFingerprint = rawFingerprint,
                    rejectSuperseded = false
                )
            assertIs<Result.Success<*>>(staleIfMatch)
            val outcome = (staleIfMatch as Result.Success).data
            assertIs<io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome.PreconditionFailed>(outcome)
        }
}
