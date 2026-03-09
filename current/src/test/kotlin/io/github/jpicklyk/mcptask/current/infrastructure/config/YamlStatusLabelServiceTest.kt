package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class YamlStatusLabelServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `NoOp returns hardcoded defaults`(): Unit {
        assertEquals("in-progress", NoOpStatusLabelService.resolveLabel("start"))
        assertEquals("done", NoOpStatusLabelService.resolveLabel("complete"))
        assertEquals("blocked", NoOpStatusLabelService.resolveLabel("block"))
        assertEquals("cancelled", NoOpStatusLabelService.resolveLabel("cancel"))
        assertEquals("done", NoOpStatusLabelService.resolveLabel("cascade"))
        assertNull(NoOpStatusLabelService.resolveLabel("resume"))
        assertNull(NoOpStatusLabelService.resolveLabel("reopen"))
    }

    @Test
    fun `NoOp returns null for unknown trigger`(): Unit {
        assertNull(NoOpStatusLabelService.resolveLabel("unknown"))
    }

    @Test
    fun `missing config file uses defaults`(): Unit {
        val nonExistentPath = tempDir.resolve(".taskorchestrator/config.yaml")
        val service = YamlStatusLabelService(nonExistentPath)

        assertEquals("in-progress", service.resolveLabel("start"))
        assertEquals("done", service.resolveLabel("complete"))
        assertEquals("blocked", service.resolveLabel("block"))
        assertEquals("cancelled", service.resolveLabel("cancel"))
        assertEquals("done", service.resolveLabel("cascade"))
        assertNull(service.resolveLabel("resume"))
        assertNull(service.resolveLabel("reopen"))
    }

    @Test
    fun `config without status_labels section uses defaults`(): Unit {
        val configFile = createConfigFile("""
            note_schemas:
              default:
                - key: test
                  role: queue
                  required: false
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertEquals("in-progress", service.resolveLabel("start"))
        assertEquals("done", service.resolveLabel("complete"))
    }

    @Test
    fun `custom status_labels override defaults`(): Unit {
        val configFile = createConfigFile("""
            status_labels:
              start: "working"
              complete: "finished"
              block: "on-hold"
              cancel: "abandoned"
              cascade: "auto-done"
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertEquals("working", service.resolveLabel("start"))
        assertEquals("finished", service.resolveLabel("complete"))
        assertEquals("on-hold", service.resolveLabel("block"))
        assertEquals("abandoned", service.resolveLabel("cancel"))
        assertEquals("auto-done", service.resolveLabel("cascade"))
    }

    @Test
    fun `custom config with explicit null values`(): Unit {
        val configFile = createConfigFile("""
            status_labels:
              start: "active"
              resume: null
              reopen: null
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertEquals("active", service.resolveLabel("start"))
        assertNull(service.resolveLabel("resume"))
        assertNull(service.resolveLabel("reopen"))
    }

    @Test
    fun `custom config with partial overrides returns null for unmapped triggers`(): Unit {
        val configFile = createConfigFile("""
            status_labels:
              start: "doing"
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertEquals("doing", service.resolveLabel("start"))
        // Triggers not in custom config return null (no implicit defaults)
        assertNull(service.resolveLabel("complete"))
        assertNull(service.resolveLabel("block"))
    }

    @Test
    fun `config coexists with note_schemas`(): Unit {
        val configFile = createConfigFile("""
            note_schemas:
              default:
                - key: test
                  role: queue
                  required: false
            status_labels:
              start: "in-progress"
              complete: "done"
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertEquals("in-progress", service.resolveLabel("start"))
        assertEquals("done", service.resolveLabel("complete"))
    }

    @Test
    fun `empty config file uses defaults`(): Unit {
        val configFile = createConfigFile("")

        val service = YamlStatusLabelService(configFile)

        // Falls back to NoOp defaults
        assertEquals("in-progress", service.resolveLabel("start"))
        assertEquals("done", service.resolveLabel("complete"))
    }

    @Test
    fun `malformed YAML uses defaults gracefully`(): Unit {
        val configFile = createConfigFile("{{{{invalid yaml")

        val service = YamlStatusLabelService(configFile)

        // Falls back to NoOp defaults
        assertEquals("in-progress", service.resolveLabel("start"))
    }

    @Test
    fun `unknown trigger returns null`(): Unit {
        val configFile = createConfigFile("""
            status_labels:
              start: "active"
        """.trimIndent())

        val service = YamlStatusLabelService(configFile)

        assertNull(service.resolveLabel("unknown-trigger"))
    }

    private fun createConfigFile(content: String): Path {
        val configDir = File(tempDir.toFile(), ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile.toPath()
    }
}
