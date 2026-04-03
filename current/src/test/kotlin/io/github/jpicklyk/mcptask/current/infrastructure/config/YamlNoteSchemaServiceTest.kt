package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
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

    // --- work_item_schemas format tests ---

    @Test
    fun `parses work_item_schemas format with lifecycle and notes`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: auto
    notes:
      - key: specification
        role: queue
        required: true
        description: "Feature specification"
        guidance: "Describe what to build"
      - key: implementation-notes
        role: work
        required: true
        description: "Implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("feature-task"))
        assertNotNull(schema)
        assertEquals(2, schema.size)
        assertEquals("specification", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)
        assertEquals(true, schema[0].required)
        assertEquals("Describe what to build", schema[0].guidance)
        assertEquals("implementation-notes", schema[1].key)
        assertEquals(Role.WORK, schema[1].role)
    }

    @Test
    fun `getSchemaForType returns correct WorkItemSchema with lifecycle`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  bug-fix:
    lifecycle: manual
    notes:
      - key: repro-steps
        role: queue
        required: true
        description: "Reproduction steps"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForType("bug-fix")
        assertNotNull(schema)
        assertEquals("bug-fix", schema.type)
        assertEquals(LifecycleMode.MANUAL, schema.lifecycleMode)
        assertEquals(1, schema.notes.size)
        assertEquals("repro-steps", schema.notes[0].key)
    }

    @Test
    fun `getSchemaForType returns null for unknown type without default`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: auto
    notes:
      - key: spec
        role: queue
        required: true
        description: "Spec"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getSchemaForType("unknown-type"))
    }

    @Test
    fun `getSchemaForType falls back to default schema`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: auto
    notes:
      - key: spec
        role: queue
        required: true
        description: "Spec"
  default:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "Default implementation notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForType("unknown-type")
        assertNotNull(schema)
        assertEquals("default", schema.type)
        assertEquals(1, schema.notes.size)
        assertEquals("implementation-notes", schema.notes[0].key)
    }

    @Test
    fun `getSchemaForType returns null when type is null`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  default:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "Default"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getSchemaForType(null))
    }

    @Test
    fun `work_item_schemas with invalid lifecycle logs warning and defaults to AUTO`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: invalid-mode
    notes:
      - key: spec
        role: queue
        required: true
        description: "Spec"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForType("feature-task")
        assertNotNull(schema)
        assertEquals(LifecycleMode.AUTO, schema.lifecycleMode)

        val warnings = service.getLoadWarnings()
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("invalid-mode"), "Warning should mention the invalid value")
        assertTrue(warnings[0].contains("AUTO"), "Warning should mention defaulting to AUTO")
    }

    @Test
    fun `legacy note_schemas still works for backward compatibility`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  feature-task:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Acceptance criteria"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("feature-task"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("acceptance-criteria", schema[0].key)
        assertEquals(Role.QUEUE, schema[0].role)

        // Also accessible via getSchemaForType
        val typeSchema = service.getSchemaForType("feature-task")
        assertNotNull(typeSchema)
        assertEquals(LifecycleMode.AUTO, typeSchema.lifecycleMode)
        assertEquals(1, typeSchema.notes.size)
    }

    @Test
    fun `work_item_schemas wins when both keys are present`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: manual
    notes:
      - key: from-new-format
        role: queue
        required: true
        description: "From new format"
note_schemas:
  feature-task:
    - key: from-legacy-format
      role: work
      required: false
      description: "From legacy format"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForTags(listOf("feature-task"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("from-new-format", schema[0].key, "work_item_schemas should win over note_schemas")

        val typeSchema = service.getSchemaForType("feature-task")
        assertNotNull(typeSchema)
        assertEquals(LifecycleMode.MANUAL, typeSchema.lifecycleMode)
    }

    @Test
    fun `getSchemaForTags still works after work_item_schemas migration`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  schema-a:
    lifecycle: auto
    notes:
      - key: note-a
        role: queue
        required: true
        description: "Note A"
  schema-b:
    lifecycle: auto
    notes:
      - key: note-b
        role: work
        required: false
        description: "Note B"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        // First tag wins
        val schema = service.getSchemaForTags(listOf("schema-a", "schema-b"))
        assertNotNull(schema)
        assertEquals(1, schema.size)
        assertEquals("note-a", schema[0].key)
    }

    @Test
    fun `work_item_schemas lifecycle auto-reopen parses correctly`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  epic:
    lifecycle: auto-reopen
    notes:
      - key: overview
        role: queue
        required: false
        description: "Epic overview"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val schema = service.getSchemaForType("epic")
        assertNotNull(schema)
        assertEquals(LifecycleMode.AUTO_REOPEN, schema.lifecycleMode)
    }

    // ──────────────────────────────────────────────
    // Traits section parsing
    // ──────────────────────────────────────────────

    @Test
    fun `traits section parsed into getTraitNotes lookup`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: session-tracking
      role: work
      required: true
      description: "Session tracking"

traits:
  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        guidance: "Check auth, data handling, access control"
  needs-perf-review:
    notes:
      - key: performance-baseline
        role: queue
        required: false
        description: "Performance baseline"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val securityNotes = service.getTraitNotes("needs-security-review")
        assertNotNull(securityNotes)
        assertEquals(1, securityNotes.size)
        assertEquals("security-assessment", securityNotes[0].key)
        assertEquals(Role.REVIEW, securityNotes[0].role)
        assertTrue(securityNotes[0].required)
        assertEquals("Security review", securityNotes[0].description)
        assertEquals("Check auth, data handling, access control", securityNotes[0].guidance)

        val perfNotes = service.getTraitNotes("needs-perf-review")
        assertNotNull(perfNotes)
        assertEquals(1, perfNotes.size)
        assertEquals("performance-baseline", perfNotes[0].key)
        assertEquals(Role.QUEUE, perfNotes[0].role)
        assertFalse(perfNotes[0].required)
    }

    @Test
    fun `getTraitNotes returns null for unknown trait`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: session-tracking
      role: work
      required: true
      description: "Session tracking"

traits:
  known-trait:
    notes:
      - key: some-note
        role: work
        required: false
        description: "Some note"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getTraitNotes("unknown-trait"))
        assertNotNull(service.getTraitNotes("known-trait"))
    }

    @Test
    fun `missing traits section means no traits available`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: session-tracking
      role: work
      required: true
      description: "Session tracking"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertNull(service.getTraitNotes("any-trait"))
    }

    @Test
    fun `default_traits parsed from work_item_schemas`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  feature-task:
    lifecycle: auto
    default_traits:
      - needs-security-review
      - needs-perf-review
    notes:
      - key: specification
        role: queue
        required: true
        description: "Specification"

traits:
  needs-security-review:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
  needs-perf-review:
    notes:
      - key: performance-baseline
        role: queue
        required: false
        description: "Performance baseline"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val defaults = service.getDefaultTraits("feature-task")
        assertEquals(listOf("needs-security-review", "needs-perf-review"), defaults)

        val schema = service.getSchemaForType("feature-task")
        assertNotNull(schema)
        assertEquals(listOf("needs-security-review", "needs-perf-review"), schema.defaultTraits)
    }

    @Test
    fun `getDefaultTraits returns empty for type without default_traits`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
work_item_schemas:
  simple-task:
    lifecycle: auto
    notes:
      - key: spec
        role: queue
        required: true
        description: "Spec"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        assertEquals(emptyList(), service.getDefaultTraits("simple-task"))
        assertEquals(emptyList(), service.getDefaultTraits("nonexistent-type"))
        assertEquals(emptyList(), service.getDefaultTraits(null))
    }

    @Test
    fun `trait entry with invalid role generates warning`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: session-tracking
      role: work
      required: true
      description: "Session tracking"

traits:
  bad-trait:
    notes:
      - key: bad-note
        role: invalid-role
        required: true
        description: "This should be skipped"
      - key: good-note
        role: review
        required: true
        description: "This should be kept"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val notes = service.getTraitNotes("bad-trait")
        assertNotNull(notes)
        assertEquals(1, notes.size)
        assertEquals("good-note", notes[0].key)
    }

    @Test
    fun `traits with multiple notes across roles`() {
        val tempDir = createTempConfigDir()
        writeConfig(
            tempDir,
            """
note_schemas:
  default:
    - key: session-tracking
      role: work
      required: true
      description: "Session tracking"

traits:
  comprehensive-review:
    notes:
      - key: design-review
        role: queue
        required: true
        description: "Design review"
      - key: security-review
        role: review
        required: true
        description: "Security review"
      - key: performance-notes
        role: work
        required: false
        description: "Performance notes"
            """.trimIndent()
        )

        val configPath = tempDir.toPath().resolve(".taskorchestrator/config.yaml")
        val service = YamlNoteSchemaService(configPath)

        val notes = service.getTraitNotes("comprehensive-review")
        assertNotNull(notes)
        assertEquals(3, notes.size)
        assertEquals(Role.QUEUE, notes[0].role)
        assertEquals(Role.REVIEW, notes[1].role)
        assertEquals(Role.WORK, notes[2].role)
    }
}
