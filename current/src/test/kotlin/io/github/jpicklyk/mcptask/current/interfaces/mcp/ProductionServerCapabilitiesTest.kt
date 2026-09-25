package io.github.jpicklyk.mcptask.current.interfaces.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S5 (AR-78 / item b5081c9b): the production server advertises `tools` and `logging` only —
 * `prompts` and `resources` were removed since nothing registers a prompt or a resource anywhere
 * in this server (they were advertised-but-empty surfaces).
 */
class ProductionServerCapabilitiesTest {
    @Test
    fun `advertises tools with listChanged and logging, and nothing else`() {
        val capabilities = productionServerCapabilities()

        assertNotNull(capabilities.tools, "tools capability must be present")
        assertTrue(capabilities.tools?.listChanged == true, "tools.listChanged must be true")
        assertNotNull(capabilities.logging, "logging capability must be present")

        assertNull(capabilities.prompts, "prompts capability must be absent")
        assertNull(capabilities.resources, "resources capability must be absent")
    }
}
