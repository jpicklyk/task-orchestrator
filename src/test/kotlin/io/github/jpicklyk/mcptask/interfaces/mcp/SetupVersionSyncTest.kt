package io.github.jpicklyk.mcptask.interfaces.mcp

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Verifies that the `SETUP_INSTRUCTIONS_VERSION` constant stays in sync across three locations:
 *
 * 1. [TaskOrchestratorResources.SETUP_INSTRUCTIONS_VERSION] -- the Kotlin constant (source of truth)
 * 2. `claude-plugins/task-orchestrator/scripts/session-setup-check.mjs` -- the hook script that
 *    checks whether a project's agent instructions are up-to-date
 * 3. `claude-plugins/task-orchestrator/skills/setup-instructions/SKILL.md` -- the skill template
 *    that agents use to install the setup block into a project
 *
 * All three must agree. If any one drifts, the hook will either fail to detect stale instructions
 * or agents will install an outdated template. This test catches desync before publishing.
 */
@DisplayName("SETUP_INSTRUCTIONS_VERSION sync across Kotlin, hook script, and skill template")
class SetupVersionSyncTest {

    private val kotlinVersion: String = TaskOrchestratorResources.SETUP_INSTRUCTIONS_VERSION

    // Use Paths.get("").toAbsolutePath() instead of System.getProperty("user.dir")
    // because other tests mutate user.dir via System.setProperty(), which affects
    // all tests in the same JVM. The Paths approach returns the actual process CWD.
    private val projectRoot: String = Paths.get("").toAbsolutePath().toString()

    // ---- helpers ----

    private fun readVersionFromMjs(): String {
        val file = File(projectRoot, "claude-plugins/clockwork/scripts/session-setup-check.mjs")
        if (!file.exists()) {
            fail("Hook script not found at ${file.absolutePath}")
        }
        val content = file.readText()
        val match = Regex("""const EXPECTED_VERSION = '([^']+)';""").find(content)
            ?: fail("Could not find EXPECTED_VERSION assignment in ${file.absolutePath}")
        return match.groupValues[1]
    }

    private fun readVersionFromSkillMd(): String {
        val file = File(projectRoot, "claude-plugins/clockwork/skills/setup-instructions/SKILL.md")
        if (!file.exists()) {
            fail("Skill template not found at ${file.absolutePath}")
        }
        val content = file.readText()
        val match = Regex("""<!-- mcp-task-orchestrator-setup: (\S+) -->""").find(content)
            ?: fail("Could not find mcp-task-orchestrator-setup marker in ${file.absolutePath}")
        return match.groupValues[1]
    }

    // ---- tests ----

    @Test
    @DisplayName("session-setup-check.mjs EXPECTED_VERSION matches Kotlin constant")
    fun hookScriptVersionMatchesKotlinConstant() {
        val mjsVersion = readVersionFromMjs()
        assertEquals(
            kotlinVersion, mjsVersion,
            "session-setup-check.mjs EXPECTED_VERSION ('$mjsVersion') does not match " +
                    "SETUP_INSTRUCTIONS_VERSION ('$kotlinVersion')"
        )
    }

    @Test
    @DisplayName("SKILL.md setup marker version matches Kotlin constant")
    fun skillTemplateVersionMatchesKotlinConstant() {
        val skillVersion = readVersionFromSkillMd()
        assertEquals(
            kotlinVersion, skillVersion,
            "SKILL.md setup marker version ('$skillVersion') does not match " +
                    "SETUP_INSTRUCTIONS_VERSION ('$kotlinVersion')"
        )
    }

    @Test
    @DisplayName("All three version locations are in sync")
    fun allThreeVersionsMatch() {
        val mjsVersion = readVersionFromMjs()
        val skillVersion = readVersionFromSkillMd()

        assertEquals(
            kotlinVersion, mjsVersion,
            "session-setup-check.mjs EXPECTED_VERSION ('$mjsVersion') does not match " +
                    "SETUP_INSTRUCTIONS_VERSION ('$kotlinVersion')"
        )
        assertEquals(
            kotlinVersion, skillVersion,
            "SKILL.md setup marker version ('$skillVersion') does not match " +
                    "SETUP_INSTRUCTIONS_VERSION ('$kotlinVersion')"
        )
        assertEquals(
            mjsVersion, skillVersion,
            "session-setup-check.mjs EXPECTED_VERSION ('$mjsVersion') does not match " +
                    "SKILL.md setup marker version ('$skillVersion')"
        )
    }
}
