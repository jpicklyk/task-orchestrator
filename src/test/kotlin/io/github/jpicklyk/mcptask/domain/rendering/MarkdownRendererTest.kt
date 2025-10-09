package io.github.jpicklyk.mcptask.domain.rendering

import io.github.jpicklyk.mcptask.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class MarkdownRendererTest {

    private lateinit var renderer: MarkdownRenderer
    private val testTaskId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val testFeatureId = UUID.fromString("661e8511-f30c-41d4-a716-557788990000")
    private val testProjectId = UUID.fromString("772f9622-a41d-52e5-b827-668899101111")
    private val testTimestamp = Instant.parse("2025-05-10T14:30:00Z")

    @BeforeEach
    fun setup() {
        renderer = MarkdownRenderer()
    }

    @Test
    fun `renderTask creates basic markdown with frontmatter`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "This is a test task summary",
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 7,
            createdAt = testTimestamp,
            modifiedAt = testTimestamp,
            tags = listOf("test", "markdown")
        )

        val result = renderer.renderTask(task, emptyList())

        // Check frontmatter
        assertTrue(result.startsWith("---"))
        assertTrue(result.contains("id: $testTaskId"))
        assertTrue(result.contains("type: task"))
        assertTrue(result.contains("title: Test Task"))
        assertTrue(result.contains("status: in-progress"))
        assertTrue(result.contains("priority: high"))
        assertTrue(result.contains("complexity: 7"))
        assertTrue(result.contains("tags:"))
        assertTrue(result.contains("  - test"))
        assertTrue(result.contains("  - markdown"))

        // Check title and summary
        assertTrue(result.contains("# Test Task"))
        assertTrue(result.contains("This is a test task summary"))
    }

    @Test
    fun `renderTask with markdown sections`() {
        val task = Task(
            id = testTaskId,
            title = "Task with Sections",
            summary = "Task summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Requirements",
                usageDescription = "Key requirements",
                content = "## Requirement 1\n- Must do X\n- Should do Y",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Implementation",
                usageDescription = "Implementation details",
                content = "Implementation notes here",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1
            )
        )

        val result = renderer.renderTask(task, sections)

        // Check sections are rendered in order
        assertTrue(result.contains("## Requirements"))
        assertTrue(result.contains("## Requirement 1"))
        assertTrue(result.contains("## Implementation"))

        // Check ordinal ordering
        val reqIndex = result.indexOf("## Requirements")
        val implIndex = result.indexOf("## Implementation")
        assertTrue(reqIndex < implIndex, "Sections should be ordered by ordinal")
    }

    @Test
    fun `renderTask with JSON content format`() {
        val task = Task(
            id = testTaskId,
            title = "Task with JSON",
            summary = "Task summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "API Specification",
                usageDescription = "API details",
                content = """{"endpoint": "/api/test", "method": "POST"}""",
                contentFormat = ContentFormat.JSON,
                ordinal = 0
            )
        )

        val result = renderer.renderTask(task, sections)

        // JSON content should be wrapped in code fence
        assertTrue(result.contains("```json"))
        assertTrue(result.contains(""""endpoint": "/api/test""""))
        assertTrue(result.contains("```"))
    }

    @Test
    fun `renderTask with CODE content format`() {
        val task = Task(
            id = testTaskId,
            title = "Task with Code",
            summary = "Task summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Implementation",
                usageDescription = "Code example",
                content = "fun example() {\n    println(\"Hello\")\n}",
                contentFormat = ContentFormat.CODE,
                ordinal = 0
            )
        )

        val result = renderer.renderTask(task, sections)

        // Code content should be wrapped in code fence with default language
        assertTrue(result.contains("```text"))
        assertTrue(result.contains("fun example()"))
    }

    @Test
    fun `renderTask with PLAIN_TEXT content format`() {
        val task = Task(
            id = testTaskId,
            title = "Task with Plain Text",
            summary = "Task summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Notes",
                usageDescription = "Plain notes",
                content = "Just plain text content\nNo special formatting",
                contentFormat = ContentFormat.PLAIN_TEXT,
                ordinal = 0
            )
        )

        val result = renderer.renderTask(task, sections)

        // Plain text should be rendered as-is
        assertTrue(result.contains("Just plain text content"))
        assertTrue(result.contains("No special formatting"))
        assertFalse(result.contains("```"))
    }

    @Test
    fun `renderTask with optional fields populated`() {
        val task = Task(
            id = testTaskId,
            featureId = testFeatureId,
            projectId = testProjectId,
            title = "Task with References",
            summary = "Task summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Check optional fields in frontmatter
        assertTrue(result.contains("featureId: $testFeatureId"))
        assertTrue(result.contains("projectId: $testProjectId"))
    }

    @Test
    fun `renderFeature creates markdown with frontmatter`() {
        val feature = Feature(
            id = testFeatureId,
            name = "Test Feature",
            summary = "Feature summary",
            status = FeatureStatus.IN_DEVELOPMENT,
            priority = Priority.MEDIUM,
            createdAt = testTimestamp,
            modifiedAt = testTimestamp,
            tags = listOf("feature", "test")
        )

        val result = renderer.renderFeature(feature, emptyList())

        // Check frontmatter
        assertTrue(result.startsWith("---"))
        assertTrue(result.contains("id: $testFeatureId"))
        assertTrue(result.contains("type: feature"))
        assertTrue(result.contains("name: Test Feature"))
        assertTrue(result.contains("status: in-development"))
        assertTrue(result.contains("priority: medium"))

        // Check title and summary
        assertTrue(result.contains("# Test Feature"))
        assertTrue(result.contains("Feature summary"))
    }

    @Test
    fun `renderProject creates markdown with frontmatter`() {
        val project = Project(
            id = testProjectId,
            name = "Test Project",
            summary = "Project summary",
            status = ProjectStatus.PLANNING,
            createdAt = testTimestamp,
            modifiedAt = testTimestamp,
            tags = listOf("project", "test")
        )

        val result = renderer.renderProject(project, emptyList())

        // Check frontmatter
        assertTrue(result.startsWith("---"))
        assertTrue(result.contains("id: $testProjectId"))
        assertTrue(result.contains("type: project"))
        assertTrue(result.contains("name: Test Project"))
        assertTrue(result.contains("status: planning"))

        // Check title and summary
        assertTrue(result.contains("# Test Project"))
        assertTrue(result.contains("Project summary"))
    }

    @Test
    fun `renderTask escapes YAML special characters in title`() {
        val task = Task(
            id = testTaskId,
            title = "Task: With Special #Characters",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Title with special chars should be quoted in frontmatter
        assertTrue(result.contains("""title: "Task: With Special #Characters""""))
    }

    @Test
    fun `renderTask escapes YAML special characters in tags`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp,
            tags = listOf("tag:with:colons", "tag#with#hash")
        )

        val result = renderer.renderTask(task, emptyList())

        // Tags with special chars should be quoted
        assertTrue(result.contains("""  - "tag:with:colons""""))
        assertTrue(result.contains("""  - "tag#with#hash""""))
    }

    @Test
    fun `renderTask without frontmatter option`() {
        val options = MarkdownOptions(includeFrontmatter = false)
        val renderer = MarkdownRenderer(options)

        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Should not contain frontmatter
        assertFalse(result.startsWith("---"))
        assertFalse(result.contains("id: $testTaskId"))
        assertTrue(result.startsWith("# Test Task"))
    }

    @Test
    fun `renderTask with custom code language`() {
        val options = MarkdownOptions(defaultCodeLanguage = "kotlin")
        val renderer = MarkdownRenderer(options)

        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Code Example",
                usageDescription = "Kotlin code",
                content = "fun test() = 42",
                contentFormat = ContentFormat.CODE,
                ordinal = 0
            )
        )

        val result = renderer.renderTask(task, sections)

        // Code fence should use custom language
        assertTrue(result.contains("```kotlin"))
    }

    @Test
    fun `renderTask with heading level offset`() {
        val options = MarkdownOptions(headingLevelOffset = 1)
        val renderer = MarkdownRenderer(options)

        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Section Title",
                usageDescription = "Test section",
                content = "Content",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            )
        )

        val result = renderer.renderTask(task, sections)

        // Sections should use ### instead of ##
        assertTrue(result.contains("### Section Title"))
    }

    @Test
    fun `renderTask with custom line ending`() {
        val options = MarkdownOptions(lineEnding = "\r\n")
        val renderer = MarkdownRenderer(options)

        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Should use Windows-style line endings
        assertTrue(result.contains("\r\n"))
    }

    @Test
    fun `renderTask with empty tags list`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp,
            tags = emptyList()
        )

        val result = renderer.renderTask(task, emptyList())

        // Should not include tags section in frontmatter
        assertFalse(result.contains("tags:"))
    }

    @Test
    fun `renderTask sections are sorted by ordinal`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        // Create sections out of order
        val sections = listOf(
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Third Section",
                usageDescription = "Third",
                content = "Content 3",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 2
            ),
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "First Section",
                usageDescription = "First",
                content = "Content 1",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 0
            ),
            Section(
                entityType = EntityType.TASK,
                entityId = testTaskId,
                title = "Second Section",
                usageDescription = "Second",
                content = "Content 2",
                contentFormat = ContentFormat.MARKDOWN,
                ordinal = 1
            )
        )

        val result = renderer.renderTask(task, sections)

        // Check sections appear in correct order
        val firstIndex = result.indexOf("## First Section")
        val secondIndex = result.indexOf("## Second Section")
        val thirdIndex = result.indexOf("## Third Section")

        assertTrue(firstIndex < secondIndex)
        assertTrue(secondIndex < thirdIndex)
    }

    @Test
    fun `escapeYamlString handles quotes and backslashes`() {
        val task = Task(
            id = testTaskId,
            title = "Task with \"quotes\" and \\backslashes\\",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Should escape quotes and backslashes
        // Input: Task with "quotes" and \backslashes\
        // Output: "Task with \"quotes\" and \\backslashes\\"
        assertTrue(result.contains("title: \"Task with \\\"quotes\\\" and \\\\backslashes\\\\\""))
    }

    @Test
    fun `renderTask handles multiline summary correctly`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Line 1\nLine 2\nLine 3",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Summary should be in the body, not frontmatter
        assertTrue(result.contains("Line 1\nLine 2\nLine 3"))
    }

    @Test
    fun `renderFeature with projectId in frontmatter`() {
        val feature = Feature(
            id = testFeatureId,
            projectId = testProjectId,
            name = "Feature in Project",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderFeature(feature, emptyList())

        assertTrue(result.contains("projectId: $testProjectId"))
    }

    @Test
    fun `renderTask with all status types`() {
        val statuses = TaskStatus.values()

        statuses.forEach { status ->
            val task = Task(
                id = testTaskId,
                title = "Test Task",
                summary = "Summary",
                status = status,
                createdAt = testTimestamp,
                modifiedAt = testTimestamp
            )

            val result = renderer.renderTask(task, emptyList())

            val expectedStatus = status.name.lowercase().replace('_', '-')
            assertTrue(
                result.contains("status: $expectedStatus"),
                "Status $status should be rendered as $expectedStatus"
            )
        }
    }

    @Test
    fun `renderFeature with all status types`() {
        val statuses = FeatureStatus.values()

        statuses.forEach { status ->
            val feature = Feature(
                id = testFeatureId,
                name = "Test Feature",
                summary = "Summary",
                status = status,
                createdAt = testTimestamp,
                modifiedAt = testTimestamp
            )

            val result = renderer.renderFeature(feature, emptyList())

            val expectedStatus = status.name.lowercase().replace('_', '-')
            assertTrue(
                result.contains("status: $expectedStatus"),
                "Status $status should be rendered as $expectedStatus"
            )
        }
    }

    @Test
    fun `renderProject with all status types`() {
        val statuses = ProjectStatus.values()

        statuses.forEach { status ->
            val project = Project(
                id = testProjectId,
                name = "Test Project",
                summary = "Summary",
                status = status,
                createdAt = testTimestamp,
                modifiedAt = testTimestamp
            )

            val result = renderer.renderProject(project, emptyList())

            val expectedStatus = status.name.lowercase().replace('_', '-')
            assertTrue(
                result.contains("status: $expectedStatus"),
                "Status $status should be rendered as $expectedStatus"
            )
        }
    }

    @Test
    fun `renderTask trims trailing whitespace`() {
        val task = Task(
            id = testTaskId,
            title = "Test Task",
            summary = "Summary",
            createdAt = testTimestamp,
            modifiedAt = testTimestamp
        )

        val result = renderer.renderTask(task, emptyList())

        // Result should not end with whitespace
        assertEquals(result, result.trimEnd())
    }
}
