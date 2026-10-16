package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val LEASE_YAML =
    "work_item_schemas:\n" +
        "  test-item:\n" +
        "    notes: []\n" +
        "resources:\n" +
        "  slot:\n" +
        "    description: \"test slot\"\n" +
        "traits:\n" +
        "  needs-slot:\n" +
        "    resources:\n" +
        "      - slot\n"

/**
 * Merge-round test (Q22 fix round 1, decision D-e extended): in `items_read` select `context` the `resourceLeases` holder
 * identity must not name an item outside the caller's scope. The entry (a key the target itself declares) stays; the holder
 * members (`holderItemId`, `acquiredByActorId`, `expiresAt`) are omitted for a hidden holder.
 */
class ItemsReadLeaseScopeTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a lease held by an out-of-scope item does not disclose its holder to a restricted caller`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api"), yaml = LEASE_YAML) { rig ->
            val hiddenHolder = rig.make("Hidden-holder", role = Role.WORK, traits = listOf("needs-slot"))
            val target = rig.make("Visible-target", tags = "api", traits = listOf("needs-slot"))
            rig.raw.resourceLeaseRepository().acquireAll(hiddenHolder.id, "agent-hidden", listOf("slot" to 3600))

            val scoped = select("""{"select":"context","itemId":"${target.id}"}""", TEST_TOKEN)
            val open = select("""{"select":"context","itemId":"${target.id}"}""")

            val lease =
                scoped.data
                    .arr("resourceLeases")
                    .single()
                    .jsonObject
            assertEquals("slot", lease.str("key"))
            assertFalse(lease.boolOf("held"), "the target itself holds nothing: ${scoped.raw}")
            assertFalse("holderItemId" in lease || "acquiredByActorId" in lease || "expiresAt" in lease, scoped.raw)
            assertFalse(short(hiddenHolder.id) in scoped.raw || "agent-hidden" in scoped.raw, "no holder leak: ${scoped.raw}")

            val control =
                open.data
                    .arr("resourceLeases")
                    .single()
                    .jsonObject
            assertTrue(sameItem(control.str("holderItemId"), hiddenHolder.id), "control: unrestricted sees the holder: ${open.raw}")
        }
}
