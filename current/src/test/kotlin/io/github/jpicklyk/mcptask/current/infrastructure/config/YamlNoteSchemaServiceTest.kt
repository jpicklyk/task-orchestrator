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

    // --- Warning collection tests (Wave 1) ---

    @Test
    fun `valid config loads with zero warnings`() {
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

        service.getSchemaForTags(listOf("my-schema"))
        assertEquals(0, service.getLoadWarnings().size)
    }

    @Test
    fun `entry missing key produces warning with schema name`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - role: queue
      required: true
      description: "Missing key field"
    - key: good-note
      role: work
      required: false
      description: "Valid entry"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        service.getSchemaForTags(listOf("my-schema"))
        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("my-schema"), "Warning should mention schema name 'my-schema'")
        assertTrue(warnings[0].contains("key"), "Warning should mention missing field 'key'")
    }

    @Test
    fun `entry missing role produces warning with schema name`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: some-note
      required: true
      description: "Missing role field"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        service.getSchemaForTags(listOf("my-schema"))
        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("my-schema"), "Warning should mention schema name 'my-schema'")
        assertTrue(warnings[0].contains("role"), "Warning should mention missing field 'role'")
    }

    @Test
    fun `non-boolean required produces warning and defaults to false`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: some-note
      role: queue
      required: "yes"
      description: "Non-boolean required"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("some-note"), "Warning should mention the key name")
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertFalse(schema[0].required, "required should default to false for non-boolean value")
    }

    @Test
    fun `malformed YAML produces warning and empty schemas`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            "note_schemas: [\ninvalid yaml: :\n  - broken"
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNull(schema)
        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("Failed to load"), "Warning should describe load failure")
    }

    @Test
    fun `missing note_schemas key produces warning`() {
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

        service.getSchemaForTags(listOf("any-tag"))
        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("note_schemas"), "Warning should mention 'note_schemas' key")
    }

    @Test
    fun `getLoadWarnings returns empty list on absent config file`() {
        val tempDir = createTempConfigDir()
        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        service.getSchemaForTags(listOf("any-tag"))
        assertEquals(0, service.getLoadWarnings().size)
    }

    // --- Gap M1: lazy init triggered by first call ---

    @Test
    fun `schema loading is lazy — entry with typo role is skipped even on first call`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  my-schema:
    - key: invalid-role-note
      role: badvalue
      required: true
      description: "Bad role triggers warning during load"
    - key: valid-note
      role: queue
      required: true
      description: "Valid entry"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("my-schema"))
        assertNotNull(schema, "Schema should be populated even though one entry had a bad role")
        assertEquals(1, schema.size, "Only the valid entry should remain after warning about bad role")
        assertEquals("valid-note", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)
    }

    @Test
    fun `lazy init produces consistent results across multiple calls`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  stable-schema:
    - key: stable-note
      role: work
      required: false
      description: "Stable entry"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val first = service.getSchemaForTags(listOf("stable-schema"))
        val second = service.getSchemaForTags(listOf("stable-schema"))
        val third = service.getSchemaForTags(listOf("stable-schema"))

        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
        assertEquals(first!!.size, second!!.size)
        assertEquals(first.size, third!!.size)
        assertEquals(first[0].key, second[0].key)
        assertEquals(first[0].key, third[0].key)
    }

    // --- Gap M2: note_schemas value is wrong type (list instead of map) ---

    @Test
    fun `note_schemas as list instead of map returns null without crashing`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  - key: something
    role: queue
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val result = service.getSchemaForTags(listOf("something"))
        assertNull(result, "When note_schemas is a list the cast fails and null is returned")
    }

    @Test
    fun `note_schemas as list does not crash on empty tag list`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  - key: something
    role: queue
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val result = service.getSchemaForTags(emptyList())
        assertNull(result, "Empty tags with list-type note_schemas should return null")
    }
}
