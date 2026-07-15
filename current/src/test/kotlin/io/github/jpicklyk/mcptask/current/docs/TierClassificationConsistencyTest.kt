package io.github.jpicklyk.mcptask.current.docs

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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

    // The in-repo consumer set is defined ONCE in this manifest, shared by generate.mjs and
    // current/build.gradle.kts inputs.files. Add a new consumer there, not here.
    private val consumersManifest =
        "claude-plugins/task-orchestrator/output-styles/_fragments/tier-classification.consumers.txt"

    @Test
    fun `every in-repo consumer mirrors the canonical fragment`() {
        val root = repoRoot()
        val fragment =
            root
                .resolve("claude-plugins/task-orchestrator/output-styles/_fragments/tier-classification.md")
                .let { Files.readString(it) }
                .normalize()

        val consumers = readManifest(root)
        assertTrue(
            consumers.isNotEmpty(),
            "consumer manifest $consumersManifest is empty — the guard would be vacuously green",
        )

        for (relativePath in consumers) {
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
        if (beginLineEnd == -1 || end <= beginLineEnd) {
            fail("malformed tier-classification markers in $source (END must follow BEGIN on a later line)")
        }
        return text.substring(beginLineEnd + 1, end)
    }

    private fun String.normalize(): String = replace("\r\n", "\n")

    private fun readManifest(root: Path): List<String> =
        Files
            .readAllLines(root.resolve(consumersManifest))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

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
