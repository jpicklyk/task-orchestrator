package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.Role
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlNoteSchemaServiceTest {
    private fun createTempConfigDir(): File {
        val tempDir = Files.createTempDirectory("yaml-schema-test").toFile()
        tempDir.deleteOnExit()
        return tempDir
    }

    private fun writeConfig(
        dir: File,
        content: String
    ): File {
        val configDir = File(dir, ".taskorchestrator")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")
        configFile.writeText(content)
        return configFile
    }

    @Test
    fun `returns null when config file absent`() {
        val tempDir = createTempConfigDir()
        // No config file written — directory exists but .taskorchestrator/config.yaml does not
        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getSchemaForTags(listOf("any-tag")))
        assertNull(service.getSchemaForTags(emptyList()))
    }

    @Test
    fun `parses schema from config yaml`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("acceptance-criteria", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)
        assertEquals(true, schema[0].required)
        assertEquals("Acceptance criteria", schema[0].description)
    }

    @Test
    fun `returns null for tag not in schema`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getSchemaForTags(listOf("unknown-tag")))
    }

    @Test
    fun `hasReviewPhase true when review role entry exists`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
    - key: test-coverage
      role: review
      required: false
      description: "Test coverage summary"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertTrue(service.hasReviewPhase(listOf("my-schema")))
    }

    @Test
    fun `hasReviewPhase false when no review role entry`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertFalse(service.hasReviewPhase(listOf("my-schema")))
    }

    @Test
    fun `first matching tag wins when multiple tags provided`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  schema-a:
    - key: note-a
      role: queue
      required: true
      description: "Note A"
  schema-b:
    - key: note-b
      role: work
      required: false
      description: "Note B"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        // schema-a comes first in the tag list so it should win
        val schema = service.getSchemaForTags(listOf("schema-a", "schema-b"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("note-a", schema[0].key)
    }

    @Test
    fun `multiple entries in schema are all parsed`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: note-one
      role: queue
      required: true
      description: "Note one"
    - key: note-two
      role: work
      required: false
      description: "Note two"
      guidance: "Detailed guidance here"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertEquals(2, schema.size)
        assertEquals("note-one", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)
        assertEquals("note-two", schema[1].key)
        assertEquals(Role.WORK, schema[1].role)
        assertEquals("Detailed guidance here", schema[1].guidance)
    }

    @Test
    fun `returns empty schema when note_schemas section is absent`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
other_config:
  key: value
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getSchemaForTags(listOf("any-tag")))
    }

    @Test
    fun `hasReviewPhase false when no schema matches`() {
        val tempDir = createTempConfigDir()
        // No config file
        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertFalse(service.hasReviewPhase(listOf("any-tag")))
    }

    @Test
    fun `skips entry with typo role value`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: bad-note
      role: queu
      required: true
      description: "Typo role"
    - key: good-note
      role: queue
      required: true
      description: "Valid role"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("good-note", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)
    }

    @Test
    fun `skips entry with blocked role`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: blocked-note
      role: blocked
      required: true
      description: "Blocked role"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertTrue(schema.isEmpty())
    }

    // --- Default schema fallback tests ---

    @Test
    fun `returns default schema when no named schema matches`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
  default:
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
    - key: review-checklist
      role: review
      required: false
      description: "Review checklist"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("unknown-tag"))
        assertNotNull(schema)
        assertEquals(2, schema.size)
        assertEquals("implementation-notes", schema[0].key)
        assertEquals(Role.WORK, schema[0].role)
        assertEquals("review-checklist", schema[1].key)
        assertEquals(Role.REVIEW, schema[1].role)
    }

    @Test
    fun `specific schema takes priority over default`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
  default:
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("acceptance-criteria", schema[0].key)
    }

    @Test
    fun `empty tags list returns default schema`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(emptyList())
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("implementation-notes", schema[0].key)
    }

    @Test
    fun `hasReviewPhase returns false for default with only work notes`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertFalse(service.hasReviewPhase(listOf("unknown-tag")))
    }

    @Test
    fun `hasReviewPhase returns true for default with review notes`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: implementation-notes
      role: work
      required: true
      description: "Implementation notes"
    - key: review-checklist
      role: review
      required: false
      description: "Review checklist"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertTrue(service.hasReviewPhase(listOf("unknown-tag")))
    }

    @Test
    fun `skips entry with terminal role`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: terminal-note
      role: terminal
      required: true
      description: "Terminal role"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema)
        assertTrue(schema.isEmpty())
    }
}
