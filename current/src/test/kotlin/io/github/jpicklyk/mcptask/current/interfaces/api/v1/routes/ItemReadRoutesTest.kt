package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irRestApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.str
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.titles
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for the item resource GETs, scenarios S8, S9 (REST half), S14 and S15 of
 * the frozen test-plan (NEW-SURFACE, except the 404 half of S15), over a real bearer principal.
 *
 * Oracles: task-scope item 11 (`GET /items/{id}` is `items_read {select:get, itemId:id}` with the SAME access as `/ops`;
 * no query parameters, any is `unknown_parameter` on `/<name>`; on ok it sets `ETag: "v{version}"` and answers 304 with no
 * body when `If-None-Match` equals it; never an ETag or 304 on an error, scope first; `GET /items/{id}/children` is
 * `{select:list, parentId:id, limit?, cursor?}`, other parameters are `unknown_parameter`, a non-integer limit is
 * `invalid_request`, no ETag; `GET /items`, `/items/roots`, `/items/{id}/tree`, `/items/{id}/breadcrumbs`,
 * `/items/{id}/schema`, `/search` and `/notes/search` are deleted), item 4 of the acceptance criteria (the kept GETs equal
 * the `/ops` body minus `meta.reqId`), D2 (restricted scope: a missing id is forbidden) and RFC 9110 sections 8.8.3 and 13.1.2
 * (an entity tag is a quoted string; a matching If-None-Match yields 304). Expected values are literals or the stored version.
 *
 * Not asserted (no oracle): weak validators (`W/"v1"`), `If-None-Match: *`, comma-separated tag lists, and whether a 304 repeats
 * the ETag header. Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemReadRoutesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
    private val r2 = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")

    private fun etag(version: Long) = "\"v$version\""

    private fun opsGet(id: Any) = """{"select":"get","itemId":"$id"}"""

    private fun noReqId(r: Reply): String = r.raw.replace(Regex("\"reqId\":\"[^\"]*\""), "\"reqId\":\"<REQ>\"")

    private val ifNoneMatch = "If-None-Match"

    // ------------------------------------------------------------------ S8

    @Test
    fun `S8 GET item answers the ops get body with ETag v-version`() =
        irApp(db.db, dir) { rig ->
            val parent = rig.make("Parent")
            val item = rig.make("Subject", parent, tags = "alpha", summary = "s")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version

            val rest = get("/items/${item.id}")
            val ops = select(opsGet(item.id))

            assertEquals(200, rest.status, rest.raw)
            assertEquals(noReqId(ops), noReqId(rest), "the REST body is the /ops body minus meta.reqId")
            assertEquals(etag(version), rest.etag)
        }

    @Test
    fun `S8 a matching If-None-Match answers 304 without a body, a different one answers 200`() =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("Subject")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version

            val matching = get("/items/${item.id}", headers = listOf(ifNoneMatch to etag(version)))
            assertEquals(304, matching.status)
            assertEquals("", matching.raw, "a 304 has no body")

            for (other in listOf(etag(version + 1), etag(0), "\"v$version-x\"", "v$version")) {
                val r = get("/items/${item.id}", headers = listOf(ifNoneMatch to other))
                assertEquals(200, r.status, "If-None-Match $other: ${r.raw}")
                assertEquals(etag(version), r.etag, other)
                assertTrue(r.ok)
            }
        }

    @Test
    fun `S8 after an update the old ETag no longer matches and the new one does`() =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("Before")
            val old =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            assertEquals(304, get("/items/${item.id}", headers = listOf(ifNoneMatch to etag(old))).status, "control")

            rig.ctx.itemCommandService
                .patch(patchOf(rig.raw.workItemRepository().getById(item.id)!!, title = "After"))
                .orFail()
            val now =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            assertNotEquals(old, now, "the write moved the version")

            val stale = get("/items/${item.id}", headers = listOf(ifNoneMatch to etag(old)))
            assertEquals(200, stale.status, stale.raw)
            assertEquals(etag(now), stale.etag)
            assertEquals("After", stale.data.str("title"))
            assertEquals(304, get("/items/${item.id}", headers = listOf(ifNoneMatch to etag(now))).status)
        }

    @Test
    fun `S8 replaying the same GET answers the same ETag and body`() =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("Replayed")

            val first = get("/items/${item.id}")
            val second = get("/items/${item.id}")

            assertEquals(first.etag, second.etag)
            assertEquals(noReqId(first), noReqId(second))
        }

    @Test
    fun `S8 GET item accepts an id prefix and the answer still carries the item's own ETag`() =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("ByPrefix", id = UUID.fromString("5ae0c3d1-0000-4000-8000-0000000000aa"))
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version

            val r = get("/items/5ae0c3d1")

            assertEquals(200, r.status, r.raw)
            assertEquals(item.id.toString(), r.data.str("id"))
            assertEquals(etag(version), r.etag)
        }

    // ------------------------------------------------------------------ S9 (REST)

    @Test
    fun `S9 an out-of-scope GET is 403 even with the correct ETag and carries no ETag`() =
        irRestApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            rig.make("Mine", id = r1)
            val foreign = rig.make("Beta-secret", id = r2)
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(foreign.id)!!
                    .version

            val plain = get("/items/${foreign.id}", TEST_TOKEN)
            assertEquals(403, plain.status, plain.raw)
            assertEquals("forbidden", plain.code)
            assertEquals("root", plain.detail.str("scope"))
            assertNull(plain.etag, "an error never sets an ETag")

            val conditional = get("/items/${foreign.id}", TEST_TOKEN, listOf(ifNoneMatch to etag(version)))
            assertEquals(403, conditional.status, "scope is decided before the ETag: ${conditional.raw}")
            assertEquals("forbidden", conditional.code)
            assertNull(conditional.etag)
            assertFalse("Beta-secret" in conditional.raw)

            val control = get("/items/${foreign.id}", ADMIN_TOKEN, listOf(ifNoneMatch to etag(version)))
            assertEquals(304, control.status, "control: the unrestricted caller gets the 304 for the same request")
        }

    @Test
    fun `S9 a missing id is 403 under a restricted scope and 404 without an ETag when unrestricted`() =
        irRestApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            rig.make("Mine", id = r1)
            val missing = "99999999-9999-4999-8999-999999999999"

            val scoped = get("/items/$missing", TEST_TOKEN)
            assertEquals(403, scoped.status, scoped.raw)
            assertEquals("forbidden", scoped.code)

            val open = get("/items/$missing")
            assertEquals(404, open.status, open.raw)
            assertEquals("not_found", open.code)
            assertNull(open.etag)

            val malformed = get("/items/xyz")
            assertEquals(400, malformed.status, malformed.raw)
            assertEquals("invalid_request", malformed.code)
            assertNull(malformed.etag)
        }

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 any query parameter on GET item is unknown_parameter`() =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("Subject")

            for (query in listOf("foo=1", "ifVersion=1", "fields=createdAt", "select=get")) {
                val r = get("/items/${item.id}?$query")
                val name = query.substringBefore("=")
                assertEquals(400, r.status, "$query: ${r.raw}")
                assertEquals("unknown_parameter", r.code, query)
                assertEquals(listOf("/$name"), r.parameters(), query)
                assertNull(r.etag, query)
            }
            assertEquals(200, get("/items/${item.id}").status, "control: no parameter")
        }

    @Test
    fun `S14 children - a non-integer limit is invalid_request, other parameters are unknown_parameter, no ETag`() =
        irRestApp(db.db, dir) { rig ->
            val parent = rig.make("Parent")
            rig.make("kid", parent)
            val path = "/items/${parent.id}/children"

            for (limit in listOf("x", "1.5", "")) {
                val r = get("$path?limit=$limit")
                assertEquals(400, r.status, "limit '$limit': ${r.raw}")
                assertEquals("invalid_request", r.code, "limit '$limit'")
                assertTrue("/limit" in r.fields(), "limit '$limit': ${r.fields()}")
            }
            val tooBig = get("$path?limit=201")
            assertEquals("invalid_request", tooBig.code, tooBig.raw)

            for (name in listOf("foo", "parentId", "sort", "role")) {
                val r = get("$path?$name=1")
                assertEquals(400, r.status, "$name: ${r.raw}")
                assertEquals("unknown_parameter", r.code, name)
                assertEquals(listOf("/$name"), r.parameters(), name)
            }
            val bad = get("$path?cursor=not-a-cursor")
            assertEquals("invalid_cursor", bad.code, bad.raw)

            val ok = get(path)
            assertEquals(200, ok.status)
            assertNull(ok.etag, "the children list has no ETag")
        }

    @Test
    fun `S14 children equal the ops list of the parent, including paging`() =
        irApp(db.db, dir) { rig ->
            val parent = rig.make("Parent")
            repeat(5) { rig.make("kid-$it", parent) }
            val path = "/items/${parent.id}/children"

            val all = get(path)
            assertEquals(noReqId(select("""{"select":"list","parentId":"${parent.id}"}""")), noReqId(all))
            assertEquals(5, all.total)

            val first = get("$path?limit=2")
            assertEquals(noReqId(select("""{"select":"list","parentId":"${parent.id}","limit":2}""")), noReqId(first))
            assertEquals(2, first.items.size)

            val cursor = assertNotNull(first.next)
            val second = get("$path?limit=2&cursor=$cursor")
            assertEquals(
                noReqId(select("""{"select":"list","parentId":"${parent.id}","limit":2,"cursor":"$cursor"}""")),
                noReqId(second)
            )
            val ids = (first.items + second.items).map { it.str("id") }
            assertEquals(4, ids.toSet().size, "the pages are disjoint")
        }

    @Test
    fun `S11 children under a tag scope list visible items only`() =
        irRestApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            rig.make("A1", p, tags = "api")
            rig.make("B1", p, tags = "ap")
            rig.make("B2", p, Role.QUEUE)

            val scoped = get("/items/${p.id}/children", TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf("A1"), scoped.items.titles())
            assertEquals(1, scoped.total)
            assertFalse("B1" in scoped.raw || "B2" in scoped.raw, scoped.raw)
            assertEquals(3, get("/items/${p.id}/children").total, "control: the unrestricted caller counts all three")
        }

    // ------------------------------------------------------------------ S15 (full topology)

    @Test
    fun `S15 the deleted routes are 404 and the kept GETs are served in the production topology`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Subject")
            rig.make("Kid", item)
            val id = item.id

            for (
            path in
            listOf(
                "/items/$id/tree",
                "/items/$id/breadcrumbs",
                "/items/$id/schema",
                "/search?query=x",
                "/notes/search?query=x"
            )
            ) {
                val r = get(path)
                assertEquals(404, r.status, "$path must be gone: ${r.raw}")
            }

            val kept = get("/items/$id")
            assertEquals(200, kept.status, kept.raw)
            assertNotNull(kept.etag)
            assertEquals(200, get("/items/$id/children").status)
        }

    /**
     * ORACLE-DEGRADED (arbitration record in test-manifest): the plan says "deleted routes 404", but two of the deleted paths
     * overlap routes that remain: `/items` still has write methods (so a GET is a method mismatch) and `/items/roots` is
     * captured by `/items/{id}`. The intent is that the old list / roots payload is gone, so these two assert a client error
     * that is not a success and carries no `items` list, rather than the literal 404.
     */
    @Test
    fun `S15 the old list and roots routes no longer serve a list`() =
        irApp(db.db, dir) { rig ->
            rig.make("Subject")

            for (path in listOf("/items", "/items/roots")) {
                val r = get(path)
                assertTrue(r.status in 400..499, "$path must be a client error, was ${r.status}: ${r.raw}")
                assertFalse("\"items\":[" in r.raw, "$path must not serve a list: ${r.raw}")
            }
        }
}
