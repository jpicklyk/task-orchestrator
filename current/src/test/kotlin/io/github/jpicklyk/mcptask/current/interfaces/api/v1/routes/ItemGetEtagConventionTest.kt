package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.irRestApp
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.make
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
import kotlin.test.assertNull

/**
 * `GET /items/{id}` follows the note resource GET's `If-None-Match` convention (Q11, carried into Q19 from Q18 D9): the
 * header may list several tags, a weak validator (`W/"v<n>"`) compares equal and `*` matches any current item. Scope is
 * still decided first: an out-of-scope id answers 403 with no ETag whatever the header holds.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemGetEtagConventionTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("c3c3c3c3-0000-4000-8000-00000000000c")
    private val r2 = UUID.fromString("d4d4d4d4-0000-4000-8000-00000000000d")

    private val ifNoneMatch = "If-None-Match"

    @Test
    fun `a weak validator, a tag list naming the current tag and a star all answer 304`(): Unit =
        irRestApp(db.db, dir) { rig ->
            val item = rig.make("Subject")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            val tag = "\"v$version\""

            for (header in listOf("W/$tag", "\"v0\", $tag", "*")) {
                val r = get("/items/${item.id}", headers = listOf(ifNoneMatch to header))
                assertEquals(304, r.status, "If-None-Match $header: ${r.raw}")
                assertEquals("", r.raw, "a 304 has no body")
            }

            val miss = get("/items/${item.id}", headers = listOf(ifNoneMatch to "\"v0\", W/\"v${version + 1}\""))
            assertEquals(200, miss.status, miss.raw)
            assertEquals(tag, miss.etag)
        }

    @Test
    fun `an out-of-scope id is 403 without an ETag even for a star or a weak validator`(): Unit =
        irRestApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            rig.make("Mine", id = r1)
            val foreign = rig.make("Gamma-secret", id = r2)
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(foreign.id)!!
                    .version

            for (header in listOf("*", "W/\"v$version\"")) {
                val r = get("/items/${foreign.id}", TEST_TOKEN, listOf(ifNoneMatch to header))
                assertEquals(403, r.status, "If-None-Match $header: ${r.raw}")
                assertNull(r.etag, "an error never sets an ETag")
                assertFalse("Gamma-secret" in r.raw)
            }
            assertEquals(304, get("/items/${foreign.id}", ADMIN_TOKEN, listOf(ifNoneMatch to "*")).status, "control: unrestricted")
        }
}
