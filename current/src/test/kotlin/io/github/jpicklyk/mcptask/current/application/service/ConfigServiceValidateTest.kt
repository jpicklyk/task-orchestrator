package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.delete
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ConfigService.validate], scenario S12 service half.
 *
 * Oracle P (v4 plan s3.6, task-scope build item 6): validate is the push pipeline minus the write. An invalid document is
 * reported with errors and nothing is stored; a valid one returns no errors plus a diff against the root's head version
 * ({types, traits, settings} x {added, removed, changed}); with no head, or a reset head, every proposed entry is added.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ConfigServiceValidateTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val provider get() = sqlite.repositoryProvider()
    private val store: ConfigStore get() = provider.configStore()
    private lateinit var service: ConfigService

    @BeforeEach
    fun setUp() {
        service =
            ConfigService(
                provider,
                YamlConfigDocumentParser,
                sqlite.unitOfWork(),
                ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })
            )
    }

    private suspend fun newRoot(title: String = "Root"): UUID =
        provider.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)).id

    private val headYaml =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n" +
            "  bug-fix:\n    notes:\n      - key: diagnosis\n        role: queue\n        required: true\n" +
            "traits:\n" +
            "  needs-review:\n    notes:\n      - key: review-note\n        role: review\n        required: false\n" +
            "  needs-docs:\n    notes:\n      - key: docs-note\n        role: work\n        required: false\n"

    // feature-task: required flipped (changed); bug-fix identical; chore added; needs-docs removed; needs-review identical.
    private val proposedYaml =
        "work_item_schemas:\n" +
            "  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: false\n" +
            "  bug-fix:\n    notes:\n      - key: diagnosis\n        role: queue\n        required: true\n" +
            "  chore:\n    notes: []\n" +
            "traits:\n" +
            "  needs-review:\n    notes:\n      - key: review-note\n        role: review\n        required: false\n"

    private val invalidYaml = "orchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML

    @Test
    fun `S12 an invalid document is reported with errors and no diff and leaves history and head byte-identical`(): Unit =
        runBlocking {
            val root = newRoot()
            assertIs<ConfigPushResult.Success>(service.push(root, headYaml))
            val historyBefore = store.history(ConfigScope.ROOT, root, 100)
            val headBefore = assertNotNull(store.head(ConfigScope.ROOT, root))

            val result = service.validate(root, invalidYaml)

            val checked = assertIs<ConfigValidateResult.Checked>(result, "unexpected: $result")
            assertTrue(checked.errors.any { it.path == "/orchestration" }, "errors: ${checked.errors}")
            assertFalse(checked.isValid)
            assertNull(checked.diff, "an invalid document carries no diff")
            assertEquals(historyBefore, store.history(ConfigScope.ROOT, root, 100))
            assertEquals(headBefore, store.head(ConfigScope.ROOT, root))
            // positive control: the repaired document validates clean against the same state
            val repaired = assertIs<ConfigValidateResult.Checked>(service.validate(root, StrictConfigCases.VALID_ROOT_YAML))
            assertTrue(repaired.errors.isEmpty(), "errors: ${repaired.errors}")
            assertTrue(repaired.isValid)
        }

    @Test
    fun `S12 an invalid document on a root with no history stores nothing`(): Unit =
        runBlocking {
            val root = newRoot()

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, invalidYaml))

            assertTrue(checked.errors.isNotEmpty())
            assertNull(checked.diff)
            assertNull(store.head(ConfigScope.ROOT, root))
            assertEquals(0, store.history(ConfigScope.ROOT, root, 100).size)
        }

    @Test
    fun `S12 a valid document against an existing head reports exactly the added, removed and changed names`(): Unit =
        runBlocking {
            val root = newRoot()
            assertIs<ConfigPushResult.Success>(service.push(root, headYaml))

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, proposedYaml))

            assertEquals(emptyList(), checked.errors)
            assertTrue(checked.isValid)
            val diff = assertNotNull(checked.diff, "a valid document carries a diff")
            assertEquals(listOf("chore"), diff.types.added)
            assertEquals(emptyList(), diff.types.removed)
            assertEquals(listOf("feature-task"), diff.types.changed, "only the type whose required flag flipped")
            assertEquals(emptyList(), diff.traits.added)
            assertEquals(listOf("needs-docs"), diff.traits.removed)
            assertEquals(emptyList(), diff.traits.changed)
            assertFalse(diff.isEmpty)
        }

    @Test
    fun `S12 re-validating the head document yields a valid empty diff`(): Unit =
        runBlocking {
            val root = newRoot()
            assertIs<ConfigPushResult.Success>(service.push(root, headYaml))

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, headYaml))

            assertTrue(checked.isValid)
            assertTrue(assertNotNull(checked.diff).isEmpty, "identical to head means no delta")
        }

    @Test
    fun `S12 with no head every proposed entry is added`(): Unit =
        runBlocking {
            val root = newRoot()

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, headYaml))

            assertTrue(checked.isValid)
            val diff = assertNotNull(checked.diff)
            assertEquals(listOf("bug-fix", "feature-task"), diff.types.added.sorted())
            assertEquals(listOf("needs-docs", "needs-review"), diff.traits.added.sorted())
            assertEquals(emptyList(), diff.types.removed)
            assertEquals(emptyList(), diff.types.changed)
            assertEquals(emptyList(), diff.traits.removed)
            assertEquals(emptyList(), diff.traits.changed)
        }

    @Test
    fun `S12 a reset head counts as empty so everything is added`(): Unit =
        runBlocking {
            val root = newRoot()
            assertIs<ConfigPushResult.Success>(service.push(root, headYaml))
            assertNotNull(store.delete(root), "fixture: the head is now a reset version")
            val historyBefore = store.history(ConfigScope.ROOT, root, 100)

            val checked = assertIs<ConfigValidateResult.Checked>(service.validate(root, headYaml))

            val diff = assertNotNull(checked.diff)
            assertEquals(listOf("bug-fix", "feature-task"), diff.types.added.sorted(), "pre-reset content is not the baseline")
            assertEquals(listOf("needs-docs", "needs-review"), diff.traits.added.sorted())
            assertEquals(emptyList(), diff.types.changed)
            assertEquals(emptyList(), diff.types.removed)
            assertEquals(historyBefore, store.history(ConfigScope.ROOT, root, 100))
        }

    @Test
    fun `S12 validate never appends a version, even for a valid document`(): Unit =
        runBlocking {
            val root = newRoot()
            assertIs<ConfigPushResult.Success>(service.push(root, headYaml))
            val before = store.history(ConfigScope.ROOT, root, 100).size
            val headBefore = assertNotNull(store.head(ConfigScope.ROOT, root))
            assertEquals(1, before)

            assertIs<ConfigValidateResult.Checked>(service.validate(root, proposedYaml))
            assertIs<ConfigValidateResult.Checked>(service.validate(root, proposedYaml))

            assertEquals(before, store.history(ConfigScope.ROOT, root, 100).size)
            assertEquals(headBefore, store.head(ConfigScope.ROOT, root))
            // positive control: a real push of the same document does append
            assertIs<ConfigPushResult.Success>(service.push(root, proposedYaml))
            assertEquals(before + 1, store.history(ConfigScope.ROOT, root, 100).size)
        }

    @Test
    fun `S12 a document over the size cap is TooLarge and one at the cap is checked`(): Unit =
        runBlocking {
            val root = newRoot()
            val prefix = "# "
            val atCap = prefix + "a".repeat(131_072 - prefix.length - 1) + "\n"
            val overCap = prefix + "a".repeat(131_072 - prefix.length) + "\n"

            val over = assertIs<ConfigValidateResult.TooLarge>(service.validate(root, overCap))
            val at = service.validate(root, atCap)

            assertEquals(131_073, over.sizeBytes)
            assertEquals(131_072, over.maxBytes)
            assertIs<ConfigValidateResult.Checked>(at)
        }

    @Test
    fun `S12 an unknown root is NotFound and a non-root item is NotDepthZero`(): Unit =
        runBlocking {
            val root = newRoot()
            val child = provider.workItemRepository().create(WorkItem(title = "Child", parentId = root, depth = 1)).id
            val unknown = UUID.randomUUID()

            val missing = assertIs<ConfigValidateResult.NotFound>(service.validate(unknown, headYaml))
            val deep = assertIs<ConfigValidateResult.NotDepthZero>(service.validate(child, headYaml))
            val ok = service.validate(root, headYaml)

            assertEquals(unknown, missing.rootItemId)
            assertEquals(child, deep.rootItemId)
            assertEquals(1, deep.depth)
            assertIs<ConfigValidateResult.Checked>(ok)
        }

    @Test
    fun `S12 a YAML syntax error is a ParseError and stores nothing`(): Unit =
        runBlocking {
            val root = newRoot()

            val result = service.validate(root, "work_item_schemas: [this is not, a valid: map")

            assertIs<ConfigValidateResult.ParseError>(result)
            assertNull(store.head(ConfigScope.ROOT, root))
        }
}
