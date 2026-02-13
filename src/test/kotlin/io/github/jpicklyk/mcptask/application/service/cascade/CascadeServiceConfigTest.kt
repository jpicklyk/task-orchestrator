package io.github.jpicklyk.mcptask.application.service.cascade

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.domain.repository.*
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CascadeServiceConfigTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var originalUserDir: String
    private lateinit var service: CascadeServiceImpl

    @BeforeEach
    fun setUp() {
        originalUserDir = System.getProperty("user.dir")
        System.setProperty("user.dir", tempDir.toAbsolutePath().toString())

        service = CascadeServiceImpl(
            statusProgressionService = mockk(relaxed = true),
            statusValidator = mockk(relaxed = true),
            taskRepository = mockk(relaxed = true),
            featureRepository = mockk(relaxed = true),
            projectRepository = mockk(relaxed = true),
            dependencyRepository = mockk(relaxed = true),
            sectionRepository = mockk(relaxed = true)
        )
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.dir", originalUserDir)
    }

    private fun writeConfig(content: String) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.writeString(configDir.resolve("config.yaml"), content)
    }

    @Nested
    inner class LoadAutoCascadeConfigTests {

        @Test
        fun `should load enabled=true from custom config`() {
            // Setup: write YAML with enabled=true
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertTrue(config.enabled, "Expected enabled=true from custom config")
            assertEquals(3, config.maxDepth)
        }

        @Test
        fun `should load enabled=false from custom config`() {
            // Setup: write YAML with enabled=false
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: false
                  max_depth: 3
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertFalse(config.enabled, "Expected enabled=false from custom config")
            assertEquals(3, config.maxDepth)
        }

        @Test
        fun `should load max_depth=1 from custom config`() {
            // Setup: write YAML with max_depth=1
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 1
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertTrue(config.enabled)
            assertEquals(1, config.maxDepth, "Expected max_depth=1 from custom config")
        }

        @Test
        fun `should load max_depth=5 from custom config`() {
            // Setup: write YAML with max_depth=5
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 5
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertTrue(config.enabled)
            assertEquals(5, config.maxDepth, "Expected max_depth=5 from custom config")
        }

        @Test
        fun `should fallback to bundled defaults when auto_cascade section missing`() {
            // Setup: write YAML without auto_cascade section
            writeConfig(
                """
                version: "2.0.0"
                status_progression:
                  features:
                    default_flow: [planning, in-development, completed]
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert: falls back to AutoCascadeConfig() defaults
            assertTrue(config.enabled, "Expected enabled=true (default)")
            assertEquals(3, config.maxDepth, "Expected max_depth=3 (default)")
        }

        @Test
        fun `should fallback to defaults when YAML is malformed`() {
            // Setup: write invalid YAML content
            writeConfig(
                """
                this is not valid yaml: {{{
                  broken: [unclosed
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert: falls back to AutoCascadeConfig() defaults on exception
            assertTrue(config.enabled, "Expected enabled=true (default after malformed YAML)")
            assertEquals(3, config.maxDepth, "Expected max_depth=3 (default after malformed YAML)")
        }

        @Test
        fun `should load from bundled default when no config file exists`() {
            // Setup: no config file created (tempDir is empty)

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert: loads from bundled default-config.yaml (enabled=true, max_depth=3)
            assertTrue(config.enabled, "Expected enabled=true from bundled default-config.yaml")
            assertEquals(3, config.maxDepth, "Expected max_depth=3 from bundled default-config.yaml")
        }
    }
}
