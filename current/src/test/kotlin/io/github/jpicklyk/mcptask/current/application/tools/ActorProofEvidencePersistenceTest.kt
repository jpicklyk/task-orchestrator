package io.github.jpicklyk.mcptask.current.application.tools

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.ActorVerifier
import io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 983615e7 -- "Stop persisting raw actor proof JWTs; scrub
 * existing rows". Covers test-plan scenarios S1-S3, S11, S13-S16, S18, S19 (the application/tools
 * layer's persistence-side behavior of the fix: what `manage_notes` / `advance_item` actually write
 * to the `notes` / `role_transitions` tables, as distinct from what the MCP response itself shows,
 * which [ActorProofMcpExposureTest] already covers and continues to guard).
 *
 * Harness: real H2 in-memory DB via [DirectDatabaseSchemaManager], same pattern as
 * [ActorProofMcpExposureTest]. JWKS signing/mocking follows
 * `infrastructure.config.JwksActorVerifierExpRequiredTest`'s [JwksKeySetProvider] mock + real RSA
 * key + real [SignedJWT] pattern.
 *
 * Oracle: item 983615e7's frozen `diagnosis` note (Fix steps 3-5) and `test-plan` note -- never
 * this file's own reading of the fixed source. `src/main` was not opened while writing this file;
 * the JWKS/VerifierConfig/ActorVerifier constructor shapes used below come from the two named
 * precedent test files (readable under `src/test`), not from `src/main`.
 */
class ActorProofEvidencePersistenceTest {
    companion object {
        /** The one JWKS-trusted key for most VERIFIED scenarios. */
        private val rsaKey = RSAKeyGenerator(2048).keyID("evidence-test-key").generate()

        /** A key the mocked provider does NOT trust -- used to simulate a forged/wrong-key proof. */
        private val wrongKey = RSAKeyGenerator(2048).keyID("wrong-test-key").generate()
    }

    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var manageNotesTool: ManageNotesTool
    private lateinit var advanceItemTool: AdvanceItemTool

    /** Raw handle to the same H2 database, for the B1 at-rest (raw-SQL) assertions. */
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        manageNotesTool = ManageNotesTool()
        advanceItemTool = AdvanceItemTool()
    }

    /**
     * Reads the physical `actor_proof` / `actor_proof_sha256` columns directly via raw SQL,
     * bypassing the repository mapper entirely (which hardcodes `proof = null` on every read,
     * regardless of what the column actually holds). Used by the B1 at-rest assertions to pin the
     * WRITE side of the fix independently of the READ side's own defense-in-depth.
     *
     * Matches by `body` rather than `key` -- `key` is a SQL-reserved word whose exact quoted
     * physical column name/case in the Direct-mode (H2) schema is not among the supplied
     * declarations, so this avoids depending on it. Assumes exactly one matching row (each B1 test
     * uses a note body unique to that call).
     */
    private fun rawNoteProofColumns(body: String): Pair<String?, String?> {
        var proof: String? = null
        var sha: String? = null
        transaction(db = database) {
            exec("SELECT actor_proof, actor_proof_sha256 FROM notes WHERE body = '$body'") { rs ->
                if (rs.next()) {
                    proof = rs.getString("actor_proof")
                    sha = rs.getString("actor_proof_sha256")
                }
            }
        }
        return proof to sha
    }

    /**
     * Same as [rawNoteProofColumns] but for `role_transitions`. Assumes exactly one row exists in
     * the table (true for every B1 test, each using its own freshly-created, isolated H2 database).
     */
    private fun rawTransitionProofColumns(): Pair<String?, String?> {
        var proof: String? = null
        var sha: String? = null
        transaction(db = database) {
            exec("SELECT actor_proof, actor_proof_sha256 FROM role_transitions") { rs ->
                if (rs.next()) {
                    proof = rs.getString("actor_proof")
                    sha = rs.getString("actor_proof_sha256")
                }
            }
        }
        return proof to sha
    }

    private suspend fun createTestItem(title: String = "Test Item"): String {
        val item = WorkItem(title = title)
        val result = repositoryProvider.workItemRepository().create(item)
        return ((result as Result.Success).data.id).toString()
    }

    private fun contextWithVerifier(verifier: ActorVerifier): ToolExecutionContext =
        ToolExecutionContext(repositoryProvider = repositoryProvider, actorVerifier = verifier)

    private fun actorJson(
        id: String,
        kind: String = "subagent",
        parent: String? = null,
        proof: String? = null
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            parent?.let { put("parent", it) }
            proof?.let { put("proof", it) }
        }

    private fun noteWithActorJson(
        itemId: String,
        key: String,
        role: String,
        body: String,
        actor: JsonObject
    ): JsonObject =
        buildJsonObject {
            put("itemId", itemId)
            put("key", key)
            put("role", role)
            put("body", body)
            put("actor", actor)
        }

    /** Independently computed lowercase-hex SHA-256, used as the oracle for every hash assertion. */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // JWKS signing / mocking helpers (pattern: infrastructure.config.JwksActorVerifierExpRequiredTest)
    // -------------------------------------------------------------------------

    private fun mockProvider(keys: List<RSAKey>): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns
            JwksResult(JWKSet(keys.map { it.toPublicJWK() }), CacheState(fromStaleCache = false, ageSeconds = null))
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun baseConfig(): VerifierConfig.Jwks =
        VerifierConfig.Jwks(
            jwksPath = "/unused-in-unit-tests",
            issuer = "https://test-issuer.example",
            audience = "a",
            requireSubMatch = true
        )

    private fun jwksVerifier(keys: List<RSAKey> = listOf(rsaKey)): JwksActorVerifier =
        JwksActorVerifier(config = baseConfig(), keySetProvider = mockProvider(keys))

    /** Signs a JWT with the given [key], claiming header `kid` = [kid] -- may differ from [key]'s own kid. */
    private fun signClaims(
        key: RSAKey,
        kid: String,
        subject: String,
        issuer: String = "https://test-issuer.example",
        audience: String = "a",
        jti: String = UUID.randomUUID().toString(),
        iat: Instant = Instant.now(),
        exp: Instant = iat.plusSeconds(300)
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .jwtID(jti)
                .issueTime(Date.from(iat))
                .expirationTime(Date.from(exp))
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    // ===========================================================================================
    // S1 -- manage_notes, noop verifier, proof "abc" -> actor_proof NULL, sha256=H, claims NULL
    // ===========================================================================================

    @Test
    fun `S1 manage_notes upsert with a noop-verified proof scrubs actor_proof and persists its sha256`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            // Oracle: FIPS 180-2 Appendix B.1 SHA-256 test vector for the message "abc".
            val expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertEquals(expectedHash, sha256Hex("abc"), "sanity: local hash helper must match the FIPS test vector")

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "s1-note", "work", "S1 body", actorJson(id = "agent-s1", proof = "abc")))
                        }
                    )
                },
                context
            ) as JsonObject

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s1-note")
            assertTrue(persisted is Result.Success, "expected note to be found; got $persisted")
            val note = persisted.data
            assertNotNull(note, "note should be persisted")
            assertNull(note.actorClaim?.proof, "raw proof must be scrubbed to null per item 983615e7 D5")
            assertEquals(expectedHash, note.verification?.proofSha256, "sha256 must equal SHA-256(\"abc\")")
            assertNull(note.verification?.proofClaims, "a noop verifier never produces VERIFIED claims")
        }

    // ===========================================================================================
    // B1 (reviewer-flagged gap) -- pin the at-rest write directly via raw SQL. Every other
    // assertion in this file reads through the repository mapper, which hardcodes `proof = null`
    // on read (defense in depth) regardless of what the physical column holds -- so those
    // assertions alone cannot distinguish "the WRITE nulls actor_proof" from "the WRITE never
    // touches actor_proof but the READ hides it anyway". These three tests read the physical
    // `notes` / `role_transitions` columns directly, bypassing the mapper entirely. Oracle:
    // test-plan S1/S2 "row actor_proof NULL", diagnosis Fix step 5.
    // ===========================================================================================

    @Test
    fun `B1a raw SQL confirms actor_proof is NULL and actor_proof_sha256 is set at rest after manage_notes upsert`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val expectedHash = sha256Hex("abc")

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "b1a-note", "work", "B1a body", actorJson(id = "agent-b1a", proof = "abc")))
                        }
                    )
                },
                context
            ) as JsonObject

            val (rawProof, rawSha) = rawNoteProofColumns("B1a body")
            assertNull(rawProof, "physical notes.actor_proof column must be NULL at rest after manage_notes upsert")
            assertEquals(expectedHash, rawSha, "physical notes.actor_proof_sha256 column must hold the expected hash at rest")
        }

    @Test
    fun `B1b raw SQL confirms actor_proof stays NULL and sha256 updates on a re-upsert of the same key (onUpdate path)`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "b1b-note", "work", "First", actorJson(id = "agent-b1b", proof = "abc")))
                        }
                    )
                },
                context
            ) as JsonObject
            val (firstRawProof, firstRawSha) = rawNoteProofColumns("First")
            assertNull(firstRawProof, "insert path: physical notes.actor_proof column must be NULL at rest")
            assertEquals(sha256Hex("abc"), firstRawSha, "insert path: physical notes.actor_proof_sha256 must hold the expected hash")

            // Re-upsert the SAME (itemId, key) with a different proof -- exercises the onUpdate
            // path (an existing row), not the insert path.
            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "b1b-note", "work", "Second", actorJson(id = "agent-b1b", proof = "xyz")))
                        }
                    )
                },
                context
            ) as JsonObject
            val (secondRawProof, secondRawSha) = rawNoteProofColumns("Second")
            assertNull(secondRawProof, "onUpdate path: physical notes.actor_proof column must be NULL at rest")
            assertEquals(sha256Hex("xyz"), secondRawSha, "onUpdate path: physical notes.actor_proof_sha256 must reflect the new proof")
        }

    @Test
    fun `B1c raw SQL confirms actor_proof is NULL and actor_proof_sha256 is set at rest after advance_item`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val expectedHash = sha256Hex("abc")

            advanceItemTool.execute(
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId)
                                    put("trigger", "start")
                                    put("actor", actorJson(id = "agent-b1c", proof = "abc"))
                                }
                            )
                        }
                    )
                },
                context
            ) as JsonObject

            val (rawProof, rawSha) = rawTransitionProofColumns()
            assertNull(rawProof, "physical role_transitions.actor_proof column must be NULL at rest after advance_item")
            assertEquals(expectedHash, rawSha, "physical role_transitions.actor_proof_sha256 column must hold the expected hash at rest")
        }

    // ===========================================================================================
    // S2 -- advance_item, same actor -> role_transitions row same treatment
    // ===========================================================================================

    @Test
    fun `S2 advance_item with a noop-verified proof scrubs actor_proof and persists its sha256 on the transition`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val expectedHash = sha256Hex("abc")

            advanceItemTool.execute(
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId)
                                    put("trigger", "start")
                                    put("actor", actorJson(id = "agent-s2", proof = "abc"))
                                }
                            )
                        }
                    )
                },
                context
            ) as JsonObject

            val transitions = repositoryProvider.roleTransitionRepository().findByItemId(UUID.fromString(itemId))
            assertTrue(transitions is Result.Success, "expected transitions to be found; got $transitions")
            val transition = transitions.data.single()
            assertNull(transition.actorClaim?.proof, "raw proof must be scrubbed to null per item 983615e7 D5")
            assertEquals(expectedHash, transition.verification?.proofSha256)
            assertNull(transition.verification?.proofClaims)
        }

    // ===========================================================================================
    // S3 -- VERIFIED jwks proof, full claim set -> claims persist exactly, aud=["a"], epoch seconds
    // ===========================================================================================

    @Test
    fun `S3 manage_notes with a VERIFIED jwks proof persists all 8 verified claims exactly`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val verifiedContext = contextWithVerifier(jwksVerifier())
            val iat = Instant.now().minusSeconds(5)
            val exp = iat.plusSeconds(300)
            val jti = UUID.randomUUID().toString()
            val jwt = signClaims(rsaKey, "evidence-test-key", subject = "agent-s3", jti = jti, iat = iat, exp = exp)

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "s3-note", "work", "S3 body", actorJson(id = "agent-s3", proof = jwt)))
                        }
                    )
                },
                verifiedContext
            ) as JsonObject

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s3-note")
            assertTrue(persisted is Result.Success)
            val note = persisted.data
            assertNotNull(note)
            assertNull(note.actorClaim?.proof)
            assertEquals(VerificationStatus.VERIFIED, note.verification?.status, "sanity: proof must actually verify")
            assertEquals(sha256Hex(jwt), note.verification?.proofSha256)

            val claims = note.verification?.proofClaims
            assertNotNull(claims, "a VERIFIED proof must persist its claims")
            assertEquals("https://test-issuer.example", claims.iss)
            assertEquals("agent-s3", claims.sub)
            assertEquals(listOf("a"), claims.aud)
            assertEquals(jti, claims.jti)
            assertEquals(iat.epochSecond, claims.iat)
            assertEquals(exp.epochSecond, claims.exp)
            assertEquals("evidence-test-key", claims.kid)
            assertEquals("RS256", claims.alg)
        }

    @Test
    fun `S3 advance_item with a VERIFIED jwks proof persists all 8 verified claims exactly on the transition`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val verifiedContext = contextWithVerifier(jwksVerifier())
            val iat = Instant.now().minusSeconds(5)
            val exp = iat.plusSeconds(300)
            val jti = UUID.randomUUID().toString()
            val jwt = signClaims(rsaKey, "evidence-test-key", subject = "agent-s3b", jti = jti, iat = iat, exp = exp)

            advanceItemTool.execute(
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId)
                                    put("trigger", "start")
                                    put("actor", actorJson(id = "agent-s3b", proof = jwt))
                                }
                            )
                        }
                    )
                },
                verifiedContext
            ) as JsonObject

            val transitions = repositoryProvider.roleTransitionRepository().findByItemId(UUID.fromString(itemId))
            assertTrue(transitions is Result.Success)
            val transition = transitions.data.single()
            assertNull(transition.actorClaim?.proof)
            assertEquals(VerificationStatus.VERIFIED, transition.verification?.status, "sanity: proof must actually verify")
            assertEquals(sha256Hex(jwt), transition.verification?.proofSha256)

            val claims = transition.verification?.proofClaims
            assertNotNull(claims)
            assertEquals("https://test-issuer.example", claims.iss)
            assertEquals("agent-s3b", claims.sub)
            assertEquals(listOf("a"), claims.aud)
            assertEquals(jti, claims.jti)
            assertEquals(iat.epochSecond, claims.iat)
            assertEquals(exp.epochSecond, claims.exp)
            assertEquals("evidence-test-key", claims.kid)
            assertEquals("RS256", claims.alg)
        }

    // ===========================================================================================
    // S11 -- jwks REJECTED (wrong key) -> sha256 still set, claims NULL
    // ===========================================================================================

    @Test
    fun `S11 manage_notes with a jwks-REJECTED proof still persists sha256 but no claims`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val verifiedContext = contextWithVerifier(jwksVerifier())
            // Header claims the trusted kid, but the signature is made by an untrusted key --
            // signature verification must fail (crypto), regardless of any other claim value.
            val badJwt = signClaims(wrongKey, "evidence-test-key", subject = "agent-s11")

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "s11-note", "work", "S11 body", actorJson(id = "agent-s11", proof = badJwt)))
                        }
                    )
                },
                verifiedContext
            ) as JsonObject

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s11-note")
            assertTrue(persisted is Result.Success)
            val note = persisted.data
            assertNotNull(note)
            assertNull(note.actorClaim?.proof)
            assertEquals(VerificationStatus.REJECTED, note.verification?.status, "sanity: this proof must actually be REJECTED")
            assertEquals(sha256Hex(badJwt), note.verification?.proofSha256, "sha256 is set independently of the verification outcome")
            assertNull(note.verification?.proofClaims, "REJECTED proofs must never persist unverified claims")
        }

    // ===========================================================================================
    // S13 -- malformed proof shapes ("abc", "a.b.c.d.e") -> still hashed correctly, never VERIFIED
    // ===========================================================================================

    @Test
    fun `S13 malformed non-JWT proof shapes still hash correctly and never verify`(): Unit =
        runBlocking {
            for (malformed in listOf("abc", "a.b.c.d.e")) {
                val itemId = createTestItem()
                val verifiedContext = contextWithVerifier(jwksVerifier())

                manageNotesTool.execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    noteWithActorJson(
                                        itemId,
                                        "s13-note",
                                        "work",
                                        "S13 body",
                                        actorJson(id = "agent-s13", proof = malformed)
                                    )
                                )
                            }
                        )
                    },
                    verifiedContext
                ) as JsonObject

                val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s13-note")
                assertTrue(persisted is Result.Success, "expected note to persist for proof=$malformed")
                val note = persisted.data
                assertNotNull(note, "note should be persisted for proof=$malformed")
                assertNull(note.actorClaim?.proof)
                assertEquals(
                    sha256Hex(malformed),
                    note.verification?.proofSha256,
                    "sha256 must equal the independently-computed hash for proof=$malformed"
                )
                assertNull(note.verification?.proofClaims, "malformed proof must never carry verified claims for proof=$malformed")
                assertNotEquals(
                    VerificationStatus.VERIFIED,
                    note.verification?.status,
                    "malformed proof must never verify for proof=$malformed"
                )
            }
        }

    // ===========================================================================================
    // S14 -- proof at the 10000-char boundary -> a well-formed 64-char lowercase-hex sha256.
    // (The 10001-char -> ValidationException half of this scenario is EXISTING-SURFACE and already
    // exercised at the domain layer by domain.model.ActorClaimTest's "ActorClaim proof exceeding
    // 10000 chars throws ValidationException" / "...at exactly 10000 chars is valid" -- since that
    // boundary is enforced by ActorClaim's own constructor, a proof over the limit is never
    // constructed into a persistable ActorClaim in the first place, so there is nothing new to
    // assert about persistence for the over-limit case. See test-manifest.)
    // ===========================================================================================

    @Test
    fun `S14 a proof at the 10000-char boundary persists a well-formed 64-hex sha256`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val proof = "x".repeat(10000)

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "s14-note", "work", "S14 body", actorJson(id = "agent-s14", proof = proof)))
                        }
                    )
                },
                context
            ) as JsonObject

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s14-note")
            assertTrue(persisted is Result.Success)
            val note = persisted.data
            assertNotNull(note)
            assertNull(note.actorClaim?.proof)
            val hash = note.verification?.proofSha256
            assertNotNull(hash, "a 10000-char proof (the documented maximum) must still be hashed")
            assertEquals(64, hash.length, "sha256 hex must be exactly 64 chars")
            assertTrue(hash.all { it in "0123456789abcdef" }, "sha256 must be lowercase hex: $hash")
            assertEquals(sha256Hex(proof), hash, "sha256 must equal the independently-computed hash")
        }

    // ===========================================================================================
    // S15 -- blank proof ("" / "   ") -> sha256 NULL, claims NULL
    // ===========================================================================================

    @Test
    fun `S15 a blank proof yields no sha256 and no claims`(): Unit =
        runBlocking {
            for (blank in listOf("", "   ")) {
                val itemId = createTestItem()

                manageNotesTool.execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    noteWithActorJson(
                                        itemId,
                                        "s15-note",
                                        "work",
                                        "S15 body",
                                        actorJson(id = "agent-s15", proof = blank)
                                    )
                                )
                            }
                        )
                    },
                    context
                ) as JsonObject

                val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s15-note")
                assertTrue(persisted is Result.Success, "expected note to persist for proof=\"$blank\"")
                val note = persisted.data
                assertNotNull(note)
                assertNull(note.actorClaim?.proof)
                assertNull(note.verification?.proofSha256, "a blank proof must not be hashed, for proof=\"$blank\"")
                assertNull(note.verification?.proofClaims, "a blank proof must not carry claims, for proof=\"$blank\"")
            }
        }

    // ===========================================================================================
    // S16 -- VERIFIED, unicode kid "clé-🔑" -> exact round-trip through the DB
    // ===========================================================================================

    @Test
    fun `S16 a VERIFIED proof with a unicode kid round-trips exactly through the repository`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val unicodeKid = "clé-🔑" // "clé-🔑"
            val unicodeKidKey = RSAKeyGenerator(2048).keyID(unicodeKid).generate()
            val verifiedContext = contextWithVerifier(jwksVerifier(listOf(unicodeKidKey)))
            val jwt = signClaims(unicodeKidKey, unicodeKid, subject = "agent-s16")

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(noteWithActorJson(itemId, "s16-note", "work", "S16 body", actorJson(id = "agent-s16", proof = jwt)))
                        }
                    )
                },
                verifiedContext
            ) as JsonObject

            val persisted = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s16-note")
            assertTrue(persisted is Result.Success)
            val note = persisted.data
            assertNotNull(note)
            assertEquals(VerificationStatus.VERIFIED, note.verification?.status, "sanity: unicode-kid proof must actually verify")
            val claims = note.verification?.proofClaims
            assertNotNull(claims)
            assertEquals(unicodeKid, claims.kid, "unicode kid must round-trip exactly through the DB, byte for byte")
        }

    // ===========================================================================================
    // S18 -- re-upsert same note without proof -> sha256 cleared (last-writer-wins)
    // ===========================================================================================

    @Test
    fun `S18 re-upserting the same note with an actor but no proof clears the previously-persisted sha256`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId,
                                    "s18-note",
                                    "work",
                                    "First body",
                                    actorJson(id = "agent-s18", proof = "abc")
                                )
                            )
                        }
                    )
                },
                context
            ) as JsonObject
            val firstRead = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s18-note")
            assertTrue(firstRead is Result.Success)
            val firstNote = firstRead.data
            assertNotNull(firstNote)
            assertEquals(sha256Hex("abc"), firstNote.verification?.proofSha256, "sanity: first write must persist a hash")

            // Second write: same note key, same actor id, but no proof supplied this time.
            manageNotesTool.execute(
                buildJsonObject {
                    put("operation", "upsert")
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                noteWithActorJson(
                                    itemId,
                                    "s18-note",
                                    "work",
                                    "Second body",
                                    actorJson(id = "agent-s18", proof = null)
                                )
                            )
                        }
                    )
                },
                context
            ) as JsonObject

            val secondRead = context.noteRepository().findByItemIdAndKey(UUID.fromString(itemId), "s18-note")
            assertTrue(secondRead is Result.Success)
            val secondNote = secondRead.data
            assertNotNull(secondNote)
            assertEquals("Second body", secondNote.body)
            assertNull(secondNote.verification?.proofSha256, "last-writer-wins: the second (proof-less) write must clear the sha256")
            assertNull(secondNote.verification?.proofClaims)
        }

    // ===========================================================================================
    // S19 (guard, green pre-fix) -- MCP response for a VERIFIED note never exposes proofSha256 or
    // proofClaims. This is a regression guard, not a fix-detector: diagnosis Fix step 2 explicitly
    // keeps VerificationResult.toJson enumerating fields by name (non-goal: "MCP surface"), so the
    // new fields structurally cannot appear on the wire without a separate, deliberate change to
    // that serializer. Substitute verification (since the frozen test-plan did not supply one
    // verbatim for this guard): behavioral coverage of this exact response shape already exists in
    // ActorProofMcpExposureTest's S5 (VERIFIED-note response is asserted SECRET-free); this test
    // adds the two field-name-specific negative assertions that S5 does not make.
    // ===========================================================================================

    @Test
    fun `S19 guard MCP response for a VERIFIED note never exposes proofSha256 or proofClaims`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val verifiedContext = contextWithVerifier(jwksVerifier())
            val jwt = signClaims(rsaKey, "evidence-test-key", subject = "agent-s19")

            val result =
                manageNotesTool.execute(
                    buildJsonObject {
                        put("operation", "upsert")
                        put(
                            "notes",
                            buildJsonArray {
                                add(
                                    noteWithActorJson(
                                        itemId,
                                        "s19-note",
                                        "work",
                                        "S19 body",
                                        actorJson(id = "agent-s19", proof = jwt)
                                    )
                                )
                            }
                        )
                    },
                    verifiedContext
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "expected success; got $result")
            val responseText = result.toString()
            assertFalse(responseText.contains("proofSha256"), "MCP response must never surface proofSha256: $responseText")
            assertFalse(responseText.contains("proofClaims"), "MCP response must never surface proofClaims: $responseText")
            assertFalse(responseText.contains(jwt), "MCP response must remain proof-free")

            val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject
            val verification = note["verification"]!!.jsonObject
            assertEquals("verified", verification["status"]!!.jsonPrimitive.content)
            assertFalse(verification.containsKey("proofSha256"), "verification object must not carry a proofSha256 field")
            assertFalse(verification.containsKey("proofClaims"), "verification object must not carry a proofClaims field")
        }
}
