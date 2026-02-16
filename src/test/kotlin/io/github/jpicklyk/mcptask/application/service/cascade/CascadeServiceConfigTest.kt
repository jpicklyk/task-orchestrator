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
                  max_depth: 10
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertTrue(config.enabled, "Expected enabled=true from custom config")
            assertEquals(10, config.maxDepth)
        }

        @Test
        fun `should load enabled=false from custom config`() {
            // Setup: write YAML with enabled=false
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: false
                  max_depth: 10
                """.trimIndent()
            )

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert
            assertFalse(config.enabled, "Expected enabled=false from custom config")
            assertEquals(10, config.maxDepth)
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
            assertEquals(10, config.maxDepth, "Expected max_depth=3 (default)")
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
            assertEquals(10, config.maxDepth, "Expected max_depth=3 (default after malformed YAML)")
        }

        @Test
        fun `should load from bundled default when no config file exists`() {
            // Setup: no config file created (tempDir is empty)

            // Execute
            val config = service.loadAutoCascadeConfig()

            // Assert: loads from bundled default-config.yaml (enabled=true, max_depth=3)
            assertTrue(config.enabled, "Expected enabled=true from bundled default-config.yaml")
            assertEquals(10, config.maxDepth, "Expected max_depth=3 from bundled default-config.yaml")
        }
    }

    @Nested
    inner class LoadAggregationRulesTests {

        @Test
        fun `should parse valid aggregation rules from config`() {
            // Setup: write YAML with role aggregation rules
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                  role_aggregation:
                    enabled: true
                    rules:
                      - role_threshold: work
                        percentage: 0.5
                        target_feature_status: in-development
                      - role_threshold: review
                        percentage: 0.8
                        target_feature_status: in-review
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert
            assertEquals(2, rules.size, "Expected 2 rules")
            assertEquals("work", rules[0].roleThreshold)
            assertEquals(0.5, rules[0].percentage)
            assertEquals("in-development", rules[0].targetFeatureStatus)
            assertEquals("review", rules[1].roleThreshold)
            assertEquals(0.8, rules[1].percentage)
            assertEquals("in-review", rules[1].targetFeatureStatus)
        }

        @Test
        fun `should return empty list when role_aggregation is disabled`() {
            // Setup: write YAML with role aggregation disabled
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                  role_aggregation:
                    enabled: false
                    rules:
                      - role_threshold: work
                        percentage: 0.5
                        target_feature_status: in-development
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert
            assertTrue(rules.isEmpty(), "Expected empty list when disabled")
        }

        @Test
        fun `should return empty list when no rules defined`() {
            // Setup: write YAML with role aggregation enabled but no rules
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                  role_aggregation:
                    enabled: true
                    rules: []
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert
            assertTrue(rules.isEmpty(), "Expected empty list when no rules")
        }

        @Test
        fun `should return empty list when role_aggregation section is missing`() {
            // Setup: write YAML without role_aggregation section
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert
            assertTrue(rules.isEmpty(), "Expected empty list when section missing")
        }

        @Test
        fun `should handle malformed rule entries gracefully`() {
            // Setup: write YAML with some malformed entries
            writeConfig(
                """
                version: "2.0.0"
                auto_cascade:
                  enabled: true
                  max_depth: 3
                  role_aggregation:
                    enabled: true
                    rules:
                      - role_threshold: work
                        percentage: 0.5
                        target_feature_status: in-development
                      - role_threshold: review
                        # missing percentage
                        target_feature_status: in-review
                      - role_threshold: terminal
                        percentage: 1.0
                        target_feature_status: completed
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert: should skip malformed entry, load valid ones
            assertEquals(2, rules.size, "Expected 2 valid rules (1 malformed skipped)")
            assertEquals("work", rules[0].roleThreshold)
            assertEquals("terminal", rules[1].roleThreshold)
        }

        @Test
        fun `should handle malformed YAML gracefully`() {
            // Setup: write invalid YAML content
            writeConfig(
                """
                this is not valid yaml: {{{
                  broken: [unclosed
                """.trimIndent()
            )

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert: should return empty list on exception
            assertTrue(rules.isEmpty(), "Expected empty list on malformed YAML")
        }

        @Test
        fun `should load from bundled default when no config file exists`() {
            // Setup: no config file created (tempDir is empty)

            // Execute
            val rules = CascadeServiceImpl.loadAggregationRules()

            // Assert: bundled default has role_aggregation disabled, so empty list
            assertTrue(rules.isEmpty(), "Expected empty list from bundled default (disabled)")
        }

        @Test
        fun `should validate percentage bounds in RoleAggregationConfig`() {
            // Test that RoleAggregationConfig validates percentage
            assertThrows<IllegalArgumentException> {
                RoleAggregationConfig(
                    roleThreshold = "work",
                    percentage = 1.5, // Invalid: > 1.0
                    targetFeatureStatus = "in-development"
                )
            }

            assertThrows<IllegalArgumentException> {
                RoleAggregationConfig(
                    roleThreshold = "work",
                    percentage = -0.1, // Invalid: < 0.0
                    targetFeatureStatus = "in-development"
                )
            }

            // Valid ranges should not throw
            assertDoesNotThrow {
                RoleAggregationConfig(
                    roleThreshold = "work",
                    percentage = 0.0,
                    targetFeatureStatus = "in-development"
                )
            }

            assertDoesNotThrow {
                RoleAggregationConfig(
                    roleThreshold = "work",
                    percentage = 1.0,
                    targetFeatureStatus = "in-development"
                )
            }
        }
    }
}
