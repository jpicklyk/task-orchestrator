package io.github.jpicklyk.mcptask.current.docs

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Guards the single-source-of-truth contract for the tier-classification block.
 *
 * The block is authored once in
 * `claude-plugins/task-orchestrator/output-styles/_fragments/tier-classification.md`
 * and mirrored (verbatim) into each in-repo consumer between
 * `<!-- BEGIN GENERATED:tier-classification -->` / `<!-- END GENERATED:tier-classification -->`
 * markers. The mirror is produced by `output-styles/generate.mjs`; this test fails the
 * build if any consumer drifts from the fragment, so the copies can never silently diverge
 * (which is exactly what happened to the pre-refactor manual mirror).
 *
 * The out-of-repo project-level style (`~/.claude/output-styles/workflow-analyst.md`) is
 * intentionally NOT guarded here — it is not part of the repository and CI cannot see it.
 */
class TierClassificationConsistencyTest {
    private val marker = "tier-classification"
    private val beginPrefix = "<!-- BEGIN GENERATED:$marker"
    private val endPrefix = "<!-- END GENERATED:$marker"

    private val inRepoConsumers =
        listOf(
            "claude-plugins/task-orchestrator/output-styles/workflow-orchestrator.md",
            ".claude/skills/implement/SKILL.md",
        )

    @Test
    fun `every in-repo consumer mirrors the canonical fragment`() {
        val root = repoRoot()
        val fragment =
            root
                .resolve("claude-plugins/task-orchestrator/output-styles/_fragments/tier-classification.md")
                .let { Files.readString(it) }
                .normalize()

        for (relativePath in inRepoConsumers) {
            val text = Files.readString(root.resolve(relativePath)).normalize()
            val region = regionBetweenMarkers(text, relativePath)
            assertEquals(
                fragment.trim(),
                region.trim(),
                "tier-classification block in $relativePath drifted from the canonical fragment. " +
                    "Run: node claude-plugins/task-orchestrator/output-styles/generate.mjs",
            )
        }
    }

    private fun regionBetweenMarkers(
        text: String,
        source: String
    ): String {
        val begin = text.indexOf(beginPrefix)
        val end = text.indexOf(endPrefix)
        if (begin == -1 || end == -1) {
            fail("tier-classification markers not found in $source (expected $beginPrefix ... $endPrefix)")
        }
        val beginLineEnd = text.indexOf('\n', begin)
        return text.substring(beginLineEnd + 1, end)
    }

    private fun String.normalize(): String = replace("\r\n", "\n")

    /** Walk up from the test working directory until the repo root (contains both marker dirs). */
    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        fail("could not locate repo root (looking for a dir containing both 'claude-plugins/' and 'current/')")
    }
}
