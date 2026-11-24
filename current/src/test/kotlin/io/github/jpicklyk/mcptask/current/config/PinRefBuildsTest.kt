package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.model.item.LifecycleMode
import io.github.jpicklyk.mcptask.current.model.schema.PinLayer
import io.github.jpicklyk.mcptask.current.model.schema.SchemaPin
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4 of item 512d64b3. Oracle: `SchemaPin.builds` KDoc (null for a schema-free pin and for a pin taken before V38, read
 * as builds) and the task-scope decision D2: a keyed pin's side carries `builds` as `samePin` reads it, a schema-free
 * pin's side carries `builds` null, and the key is always present.
 */
class PinRefBuildsTest {
    private val server = UUID.randomUUID()
    private val root = UUID.randomUUID()

    private fun keyed(builds: Boolean?) = SchemaPin(server, root, "test-item", PinLayer.SERVER, LifecycleMode.AUTO, Instant.EPOCH, builds)

    @Test
    fun `a schema-free pin writes builds as an always-present null`() {
        val payload = SchemaPinService.refOf(SchemaPin(null, null, null, null, null, Instant.EPOCH, null)).payload()

        assertTrue(payload.containsKey("builds"), "builds is a key of every side: $payload")
        assertEquals(null, payload["builds"])
    }

    @Test
    fun `a schema-free pin with a stray builds value still writes null`() {
        val payload = SchemaPinService.refOf(SchemaPin(null, null, null, null, null, Instant.EPOCH, false)).payload()

        assertEquals(null, payload["builds"])
    }

    @Test
    fun `a keyed pin with NULL builds writes true`() {
        assertEquals(true, SchemaPinService.refOf(keyed(null)).payload()["builds"])
    }

    @Test
    fun `a keyed pin writes its stored builds`() {
        assertEquals(true, SchemaPinService.refOf(keyed(true)).payload()["builds"])
        assertEquals(false, SchemaPinService.refOf(keyed(false)).payload()["builds"])
    }
}
