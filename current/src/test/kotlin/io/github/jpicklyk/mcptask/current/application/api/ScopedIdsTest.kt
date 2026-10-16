package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Q32 part H, scenario S17: the scope-aware prefix rule ([ScopedIds]) every tool now shares. Under a restricted scope an
 * unmatched prefix and a prefix matching only out-of-scope items give the SAME `forbidden` (scope is no existence oracle);
 * two matches with one admitted resolves to it; three with two admitted lists only those two; an unrestricted scope keeps
 * the plain `not_found` / `ambiguous_id`. Oracle: the `items_read` rule this hoists (api-reference "Id resolution").
 */
class ScopedIdsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("e1e1e1e1-0000-4000-8000-000000000001")
    private val r2 = UUID.fromString("e2e2e2e2-0000-4000-8000-000000000002")
    private val a1 = UUID.fromString("aaaaaaaa-1000-4000-8000-000000000001")
    private val a2 = UUID.fromString("aaaaaaaa-2000-4000-8000-000000000002")
    private val b1 = UUID.fromString("bbbbbbbb-1000-4000-8000-000000000001")
    private val b2 = UUID.fromString("bbbbbbbb-2000-4000-8000-000000000002")
    private val b3 = UUID.fromString("bbbbbbbb-3000-4000-8000-000000000003")
    private val c3 = UUID.fromString("cccccccc-3000-4000-8000-000000000003")

    private val inR1 = AccessScope.principal(setOf(r1), emptySet())

    private fun seed() {
        val repo = db.repositoryProvider().workItemRepository()
        runBlocking {
            repo.create(WorkItem(id = r1, title = "R1"))
            repo.create(WorkItem(id = r2, title = "R2"))
            for ((id, root) in listOf(a1 to r1, a2 to r2, b1 to r1, b2 to r1, b3 to r2, c3 to r2)) {
                repo.create(WorkItem(id = id, title = "child $id", parentId = root, depth = 1, rootId = root))
            }
        }
    }

    private suspend fun resolve(
        scope: AccessScope,
        prefix: String
    ) = ScopedIds.resolve(db.repositoryProvider().workItemRepository(), scope, IdRef(prefix), "/id")

    @Test
    fun `S17 the shared rule - identical forbidden, filtered ambiguity, unrestricted unchanged`(): Unit =
        runBlocking {
            seed()
            // A single match is resolved and left to the target check (a filter id is scoped by the store), so the
            // comparison runs through resolveTarget, as every tool's target ids do.
            val repo = db.repositoryProvider().workItemRepository()
            val unmatched = assertIs<Outcome.Err>(ScopedIds.resolveTarget(repo, inR1, IdRef("dddddddd"), "/id")).error
            val outOnly = assertIs<Outcome.Err>(ScopedIds.resolveTarget(repo, inR1, IdRef("cccccccc"), "/id")).error
            assertEquals(ErrorCode.FORBIDDEN, assertIs<Outcome.Err>(resolve(inR1, "dddddddd")).error.code, "no match is forbidden")
            assertEquals(ErrorCode.FORBIDDEN, unmatched.code)
            assertEquals(unmatched, outOnly, "an unmatched prefix and an out-of-scope-only prefix are indistinguishable")

            assertEquals(Outcome.Ok(a1), resolve(inR1, "aaaaaaaa"), "two matches, one admitted: it resolves")

            val ambiguous = assertIs<Outcome.Err>(resolve(inR1, "bbbbbbbb")).error
            assertEquals(ErrorCode.AMBIGUOUS_ID, ambiguous.code)
            assertEquals(listOf(b1, b2), assertIs<ErrorDetail.AmbiguousId>(ambiguous.detail).candidates, "only admitted candidates")

            val open = AccessScope.unrestricted()
            assertEquals(ErrorCode.NOT_FOUND, assertIs<Outcome.Err>(resolve(open, "dddddddd")).error.code)
            assertEquals(
                listOf(a1, a2),
                assertIs<ErrorDetail.AmbiguousId>(assertIs<Outcome.Err>(resolve(open, "aaaaaaaa")).error.detail).candidates
            )

            val target = ScopedIds.resolveTarget(db.repositoryProvider().workItemRepository(), inR1, IdRef(c3.toString()), "/id")
            assertEquals(ErrorCode.FORBIDDEN, assertIs<Outcome.Err>(target).error.code, "a full out-of-scope id is forbidden as a target")
        }

    /** The error member each tool answers for [prefix] under a root-R1 scope (the first element's error for a batch). */
    private suspend fun EventLogRig.errorOf(
        tool: String,
        arguments: String
    ): JsonObject {
        val registry = ToolRegistry(buildToolSpecs())
        val principal =
            Principal(
                id = "h-scoped",
                scope = AccessScope.principal(rootIds = setOf(r1), tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.ADMIN),
                kind = PrincipalKind.SERVICE,
                proofStatus = ProofStatus.ISSUED
            )
        val body =
            buildToolDispatcher(ctx)
                .dispatch(
                    checkNotNull(registry[tool]),
                    ToolProfile.ORCHESTRATION,
                    Json.parseToJsonElement(arguments).jsonObject,
                    "h-req",
                    CallLogRecord.SURFACE_MCP,
                    principal.toCallAccess(redactNoteAttribution = true)
                ).envelope.body
        val data = body["data"] as? JsonObject
        val element =
            data
                ?.get("results")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
        return (element?.get("error") ?: body["error"])!!.jsonObject
    }

    @Test
    fun `S17 deps notes advance and items_write answer an unmatched and an out-of-scope-only prefix identically`(): Unit =
        runBlocking {
            seed()
            val rig = EventLogRig.build(db.db, dir)
            val calls =
                listOf(
                    "deps_read" to { p: String -> """{"select":"list","item":"$p"}""" },
                    "notes_read" to { p: String -> """{"select":"list","itemId":"$p"}""" },
                    "advance" to { p: String -> """{"ops":[{"op":"transition","itemId":"$p","trigger":"start"}]}""" },
                    "items_write" to { p: String -> """{"ops":[{"op":"update","id":"$p","title":"x"}]}""" },
                )
            for ((tool, args) in calls) {
                val unmatched = rig.errorOf(tool, args("dddddddd"))
                val outOnly = rig.errorOf(tool, args("cccccccc"))
                assertEquals("forbidden", unmatched["code"]?.jsonPrimitive?.content, "$tool: $unmatched")
                assertEquals(unmatched, outOnly, "$tool: the two prefixes are indistinguishable")
                assertTrue("cccccccc" !in outOnly.toString(), "$tool: no foreign candidate is named")
            }
        }
}
