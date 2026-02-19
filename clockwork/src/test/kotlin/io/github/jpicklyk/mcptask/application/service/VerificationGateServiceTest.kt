package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class VerificationGateServiceTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val entityId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockSectionRepository = mockk()
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository

        context = ToolExecutionContext(mockRepositoryProvider)
    }

    private fun createSection(
        title: String,
        content: String,
        entityType: EntityType = EntityType.TASK,
        entityId: UUID = this.entityId,
        contentFormat: ContentFormat = ContentFormat.JSON
    ): Section {
        return Section(
            id = UUID.randomUUID(),
            entityType = entityType,
            entityId = entityId,
            title = title,
            usageDescription = "Test usage",
            content = content,
            contentFormat = contentFormat,
            ordinal = 0,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class GateTests {

        @Test
        fun `gate 1 - no verification section returns Failed`() = runBlocking {
            // Entity has sections but none titled "Verification"
            val otherSection = createSection(
                title = "Implementation Notes",
                content = "Some notes"
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(otherSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("No section titled 'Verification'"))
        }

        @Test
        fun `gate 1 - empty sections list returns Failed`() = runBlocking {
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(emptyList())

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("No section titled 'Verification'"))
        }

        @Test
        fun `gate 2 - verification section with blank content returns Failed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = "   "
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("has no content"))
        }

        @Test
        fun `gate 3 - verification section with invalid JSON returns Failed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = "not valid json at all"
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("not valid JSON"))
        }

        @Test
        fun `gate 4 - verification section with empty JSON array returns Failed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = "[]"
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("no criteria"))
        }

        @Test
        fun `gate 5 - all criteria pass returns Passed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[
                    {"criteria": "Unit tests pass", "pass": true},
                    {"criteria": "Integration tests pass", "pass": true},
                    {"criteria": "Code reviewed", "pass": true}
                ]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }

        @Test
        fun `gate 5 - one criterion fails returns Failed with failing criteria`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[
                    {"criteria": "Unit tests pass", "pass": true},
                    {"criteria": "Integration tests pass", "pass": false},
                    {"criteria": "Code reviewed", "pass": true}
                ]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("1 of 3"))
            assertEquals(1, failed.failingCriteria.size)
            assertEquals("Integration tests pass", failed.failingCriteria[0])
        }

        @Test
        fun `gate 5 - mixed pass and fail returns Failed with all failing criteria listed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[
                    {"criteria": "Unit tests pass", "pass": false},
                    {"criteria": "Integration tests pass", "pass": false},
                    {"criteria": "Code reviewed", "pass": true}
                ]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("2 of 3"))
            assertEquals(2, failed.failingCriteria.size)
            assertTrue(failed.failingCriteria.contains("Unit tests pass"))
            assertTrue(failed.failingCriteria.contains("Integration tests pass"))
        }
    }

    @Nested
    inner class CaseInsensitiveTitleMatching {

        @Test
        fun `lowercase verification title is found`() = runBlocking {
            val verificationSection = createSection(
                title = "verification",
                content = """[{"criteria": "Test", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }

        @Test
        fun `uppercase VERIFICATION title is found`() = runBlocking {
            val verificationSection = createSection(
                title = "VERIFICATION",
                content = """[{"criteria": "Test", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }

        @Test
        fun `mixed case Verification title is found`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[{"criteria": "Test", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }
    }

    @Nested
    inner class JsonParsingEdgeCases {

        @Test
        fun `missing criteria field returns Failed with parse error`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[{"pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("not valid JSON"))
        }

        @Test
        fun `missing pass field returns Failed with parse error`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[{"criteria": "Unit tests pass"}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("not valid JSON"))
        }

        @Test
        fun `extra fields in JSON entries are forward-compatible - passes if criteria and pass present`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[
                    {"criteria": "Unit tests pass", "pass": true, "notes": "All 50 tests green", "reviewer": "agent-1"}
                ]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }
    }

    @Nested
    inner class FeatureEntityTests {

        @Test
        fun `feature with no verification section returns Failed`() = runBlocking {
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, entityId)
            } returns Result.Success(emptyList())

            val result = VerificationGateService.checkVerificationSection(entityId, "feature", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("feature"))
        }

        @Test
        fun `feature with all criteria passing returns Passed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[{"criteria": "Feature tests pass", "pass": true}]""",
                entityType = EntityType.FEATURE
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "feature", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }

        @Test
        fun `feature with failing criteria returns Failed`() = runBlocking {
            val verificationSection = createSection(
                title = "Verification",
                content = """[
                    {"criteria": "Feature tests pass", "pass": true},
                    {"criteria": "Performance benchmarks met", "pass": false}
                ]""",
                entityType = EntityType.FEATURE
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, entityId)
            } returns Result.Success(listOf(verificationSection))

            val result = VerificationGateService.checkVerificationSection(entityId, "feature", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("1 of 2"))
            assertEquals(listOf("Performance benchmarks met"), failed.failingCriteria)
        }
    }

    @Nested
    inner class UnsupportedContainerTypes {

        @Test
        fun `project container type passes without check`() = runBlocking {
            // Projects are not gated by verification
            val result = VerificationGateService.checkVerificationSection(entityId, "project", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }

        @Test
        fun `unknown container type passes without check`() = runBlocking {
            val result = VerificationGateService.checkVerificationSection(entityId, "unknown", context)

            assertTrue(result is VerificationGateService.VerificationCheckResult.Passed)
        }
    }

    @Nested
    inner class RepositoryErrorHandling {

        @Test
        fun `repository error when fetching sections returns Failed`() = runBlocking {
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, entityId)
            } returns Result.Error(io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError("DB connection failed"))

            val result = VerificationGateService.checkVerificationSection(entityId, "task", context)

            // When section repository returns error, verification section is null -> gate 1 fails
            assertTrue(result is VerificationGateService.VerificationCheckResult.Failed)
            val failed = result as VerificationGateService.VerificationCheckResult.Failed
            assertTrue(failed.reason.contains("No section titled 'Verification'"))
        }
    }

    @Nested
    inner class ParseVerificationCriteriaTests {

        @Test
        fun `parseVerificationCriteria with valid JSON returns criteria list`() {
            val content = """[
                {"criteria": "Test 1", "pass": true},
                {"criteria": "Test 2", "pass": false}
            ]"""

            val criteria = VerificationGateService.parseVerificationCriteria(content)

            assertEquals(2, criteria.size)
            assertEquals("Test 1", criteria[0].criteria)
            assertTrue(criteria[0].pass)
            assertEquals("Test 2", criteria[1].criteria)
            assertFalse(criteria[1].pass)
        }

        @Test
        fun `parseVerificationCriteria with empty array returns empty list`() {
            val criteria = VerificationGateService.parseVerificationCriteria("[]")

            assertTrue(criteria.isEmpty())
        }

        @Test
        fun `parseVerificationCriteria with missing criteria field throws exception`() {
            assertThrows(IllegalArgumentException::class.java) {
                VerificationGateService.parseVerificationCriteria("""[{"pass": true}]""")
            }
        }

        @Test
        fun `parseVerificationCriteria with missing pass field throws exception`() {
            assertThrows(IllegalArgumentException::class.java) {
                VerificationGateService.parseVerificationCriteria("""[{"criteria": "Test"}]""")
            }
        }

        @Test
        fun `parseVerificationCriteria with extra fields succeeds`() {
            val content = """[{"criteria": "Test", "pass": true, "notes": "extra", "priority": 1}]"""
            val criteria = VerificationGateService.parseVerificationCriteria(content)

            assertEquals(1, criteria.size)
            assertEquals("Test", criteria[0].criteria)
            assertTrue(criteria[0].pass)
        }
    }
}
