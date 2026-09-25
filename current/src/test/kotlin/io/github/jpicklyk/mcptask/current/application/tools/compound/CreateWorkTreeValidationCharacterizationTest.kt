package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Independent, characterization-first test authorship for item `fecf7035` (needs-test-author),
 * stream WT, decompose-complexity-hotspots wave: `CreateWorkTreeTool.validateParams` — S1, S2, S3
 * of the frozen `test-plan` note `042a127d` (queue phase, read before this file was written).
 *
 * This wave is behavior-preserving: `validateParams` is about to be decomposed into per-concern
 * validators, and this file pins its CURRENT public behavior (exact error message strings and
 * check ORDER) as a regression guard. Every scenario is EXISTING-SURFACE — no new declaration is
 * introduced by the upcoming refactor, so red-proof is a plain revert of any regressing hunk.
 *
 * Oracles: [C] the exact strings frozen in the item's `task-scope` planning-seat corrections
 * (declarations §E, base commit dd26e9e2) — these are the literal text `validateParams` throws
 * today; they are pinned as the contract the decomposition must reproduce byte-identically, not
 * derived from reading `CreateWorkTreeTool.kt` (never opened — see BLINDNESS below).
 *
 * Check ORDER (task-scope correction #1, itself derived from the frozen declarations, not from
 * `src/main`): requestId, object, root, root priority, root nested children, docRef, root
 * anchors, children (per child: ref, reserved ref, title, priority, nested, anchors), parentRef,
 * cycle, deps, notes, then anchors-need-docRef LAST. S2's precedence tests exercise this order
 * directly; every other scenario is built with all OTHER concerns valid so only the scenario's own
 * violation can fire.
 *
 * BLINDNESS: authored from `task-scope`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions read from `src/test` (`CreateWorkTreeToolTest.kt`, `CreateWorkTreePriorityValidationTest.kt`)
 * for JSON field-shape conventions only. No `src/main` file, diff, or commit was read.
 */
class CreateWorkTreeValidationCharacterizationTest {
    private val tool = CreateWorkTreeTool()

    private fun validRoot(title: String = "Root Task") = buildJsonObject { put("title", JsonPrimitive(title)) }

    private fun assertRejected(
        expectedMessage: String,
        block: () -> Unit
    ) {
        val ex = assertFailsWith<ToolValidationException>(block = block)
        assertEquals(expectedMessage, ex.message, "actual message: ${ex.message}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // S1 — exact message per row (declarations §E a..v, + anchors-without-docRef)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S1a params must be a JSON object`() {
        assertRejected("Parameters must be a JSON object") {
            tool.validateParams(buildJsonArray { })
        }
    }

    @Test
    fun `S1b root must be a JSON object`() {
        assertRejected("'root' must be a JSON object") {
            tool.validateParams(buildJsonObject { put("root", JsonPrimitive("x")) })
        }
    }

    @Test
    fun `S1c root title required and must be non-blank`() {
        assertRejected("'root.title' is required and must be a non-blank string") {
            tool.validateParams(buildJsonObject { put("root", buildJsonObject { put("title", JsonPrimitive("  ")) }) })
        }
    }

    @Test
    fun `S1d root id and parentId cannot both be provided`() {
        val rootId = UUID.randomUUID().toString()
        val parentId = UUID.randomUUID().toString()
        assertRejected(
            "'root.id' and 'parentId' cannot both be provided: 'root.id' attaches to an existing item " +
                "(which already has its own parent), while 'parentId' creates a new root under a parent."
        ) {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("id", JsonPrimitive(rootId))
                            put("title", JsonPrimitive("Root"))
                        }
                    )
                    put("parentId", JsonPrimitive(parentId))
                }
            )
        }
    }

    @Test
    fun `S1e children must be a JSON array`() {
        assertRejected("'children' must be a JSON array") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("children", JsonPrimitive("x"))
                }
            )
        }
    }

    @Test
    fun `S1f children element must be a JSON object`() {
        assertRejected("children[0] must be a JSON object") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("children", buildJsonArray { add(JsonPrimitive("x")) })
                }
            )
        }
    }

    @Test
    fun `S1g children ref root is reserved`() {
        assertRejected("children[0]: ref 'root' is reserved for the root item") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("root"))
                                    put("title", JsonPrimitive("X"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1h children title is required`() {
        assertRejected("children[1]: 'title' is required") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c0"))
                                    put("title", JsonPrimitive("C0 Title"))
                                }
                            )
                            add(buildJsonObject { put("ref", JsonPrimitive("c1")) })
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1i children ref is required`() {
        assertRejected("children[0]: 'ref' is required") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("children", buildJsonArray { add(buildJsonObject { put("title", JsonPrimitive("NoRef")) }) })
                }
            )
        }
    }

    @Test
    fun `S1j parentRef not defined`() {
        assertRejected("children[0]: 'parentRef' 'zz' is not defined. Valid refs: root, a") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("a"))
                                    put("title", JsonPrimitive("A"))
                                    put("parentRef", JsonPrimitive("zz"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1k cycle detected in parentRef chain`() {
        assertRejected("children: cycle detected in parentRef chain involving 'a'") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("a"))
                                    put("title", JsonPrimitive("A"))
                                    put("parentRef", JsonPrimitive("b"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("b"))
                                    put("title", JsonPrimitive("B"))
                                    put("parentRef", JsonPrimitive("a"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1l deps must be a JSON array`() {
        assertRejected("'deps' must be a JSON array") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("deps", JsonPrimitive("x"))
                }
            )
        }
    }

    @Test
    fun `S1m deps element must be a JSON object`() {
        assertRejected("deps[0] must be a JSON object") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("deps", buildJsonArray { add(JsonPrimitive("x")) })
                }
            )
        }
    }

    @Test
    fun `S1n deps to is required`() {
        assertRejected("deps[0]: 'to' is required") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("deps", buildJsonArray { add(buildJsonObject { put("from", JsonPrimitive("root")) }) })
                }
            )
        }
    }

    @Test
    fun `S1o notes must be a JSON array`() {
        assertRejected("'notes' must be a JSON array") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("notes", buildJsonObject { })
                }
            )
        }
    }

    @Test
    fun `S1p notes element must be a JSON object`() {
        assertRejected("notes[0] must be a JSON object") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("notes", buildJsonArray { add(JsonPrimitive(1)) })
                }
            )
        }
    }

    @Test
    fun `S1q notes role required and must be non-blank`() {
        assertRejected("notes[0]: 'role' is required and must be a non-blank string") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive(""))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1r notes invalid role`() {
        assertRejected("notes[0]: invalid role 'done'. Valid: queue, work, review") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("done"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1s root noteAnchors must be a JSON array`() {
        assertRejected("root: 'noteAnchors' must be a JSON array") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Root"))
                            put("noteAnchors", JsonPrimitive("x"))
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1t children noteAnchors element must be a JSON object`() {
        assertRejected("children[0]: noteAnchors[0] must be a JSON object") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("C1"))
                                    put("noteAnchors", buildJsonArray { add(JsonPrimitive("x")) })
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1u root noteAnchors noteKey required and must be non-blank`() {
        assertRejected("root: noteAnchors[0]: 'noteKey' is required and must be a non-blank string") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Root"))
                            put(
                                "noteAnchors",
                                buildJsonArray { add(buildJsonObject { put("role", JsonPrimitive("queue")) }) }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1v docRef rootId must be a non-blank string when provided`() {
        assertRejected("'docRef.rootId' must be a non-blank string when provided") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put(
                        "docRef",
                        buildJsonObject {
                            put("slug", JsonPrimitive("s"))
                            put("rootId", JsonPrimitive(5))
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S1 anchors without docRef requires top-level docRef`() {
        assertRejected("'noteAnchors' requires top-level 'docRef' to be provided") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Root"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("k"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("a"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S2 — precedence: the first failing concern (in check ORDER) wins
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2a root priority invalid wins over a missing child title`() {
        assertRejected("root: invalid priority 'urgent'. Valid: high, medium, low") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Root"))
                            put("priority", JsonPrimitive("urgent"))
                        }
                    )
                    put("children", buildJsonArray { add(buildJsonObject { put("ref", JsonPrimitive("c1")) }) })
                }
            )
        }
    }

    @Test
    fun `S2b missing child title wins over a missing dep to and an invalid note role`() {
        assertRejected("children[0]: 'title' is required") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot())
                    put("children", buildJsonArray { add(buildJsonObject { put("ref", JsonPrimitive("c1")) }) })
                    put("deps", buildJsonArray { add(buildJsonObject { put("from", JsonPrimitive("root")) }) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("done"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `S2c missing dep to wins over anchors-without-docRef`() {
        assertRejected("deps[0]: 'to' is required") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Root"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("k"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("a"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("deps", buildJsonArray { add(buildJsonObject { put("from", JsonPrimitive("root")) }) })
                }
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // S3 — no-throw edge cases
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 root id with blank parentId does not throw — blank parentId counts as absent`() {
        val rootId = UUID.randomUUID().toString()
        tool.validateParams(
            buildJsonObject {
                put("root", buildJsonObject { put("id", JsonPrimitive(rootId)) })
                put("parentId", JsonPrimitive("  "))
            }
        )
    }

    @Test
    fun `S3 empty noteAnchors array without docRef does not throw`() {
        tool.validateParams(
            buildJsonObject {
                put(
                    "root",
                    buildJsonObject {
                        put("title", JsonPrimitive("Root"))
                        put("noteAnchors", buildJsonArray { })
                    }
                )
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // F6 — review follow-up: additional validation-order pairs (check ORDER per task-scope
    // correction #1: root checks -> docRef -> root.noteAnchors -> children -> parentRef/cycle ->
    // deps -> notes -> anchors-require-docRef).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `F6a docRef rootId invalid wins over root noteAnchors type error`() {
        assertRejected("'docRef.rootId' must be a non-blank string when provided") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("R"))
                            put("noteAnchors", JsonPrimitive("x"))
                        }
                    )
                    put(
                        "docRef",
                        buildJsonObject {
                            put("slug", JsonPrimitive("s"))
                            put("rootId", JsonPrimitive(5))
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `F6b root noteAnchors type error wins over children type error`() {
        assertRejected("root: 'noteAnchors' must be a JSON array") {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("R"))
                            put("noteAnchors", JsonPrimitive("x"))
                        }
                    )
                    put("children", JsonPrimitive("x"))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("s")) })
                }
            )
        }
    }

    @Test
    fun `F6c children cycle detection wins over deps type error`() {
        assertRejected("children: cycle detected in parentRef chain involving 'a'") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot("R"))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("a"))
                                    put("title", JsonPrimitive("A"))
                                    put("parentRef", JsonPrimitive("b"))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("b"))
                                    put("title", JsonPrimitive("B"))
                                    put("parentRef", JsonPrimitive("a"))
                                }
                            )
                        }
                    )
                    put("deps", JsonPrimitive("x"))
                }
            )
        }
    }

    @Test
    fun `F6d deps to required wins over notes type error`() {
        assertRejected("deps[0]: 'to' is required") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot("R"))
                    put("deps", buildJsonArray { add(buildJsonObject { put("from", JsonPrimitive("root")) }) })
                    put("notes", JsonPrimitive("x"))
                }
            )
        }
    }

    @Test
    fun `F6e children parentRef not defined wins over deps type error`() {
        assertRejected("children[0]: 'parentRef' 'zz' is not defined. Valid refs: root, a") {
            tool.validateParams(
                buildJsonObject {
                    put("root", validRoot("R"))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("a"))
                                    put("title", JsonPrimitive("A"))
                                    put("parentRef", JsonPrimitive("zz"))
                                }
                            )
                        }
                    )
                    put("deps", JsonPrimitive("x"))
                }
            )
        }
    }
}
