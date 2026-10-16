package io.github.jpicklyk.mcptask.current.infrastructure.security

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.docs.DocHandlers
import io.github.jpicklyk.mcptask.current.application.api.docs.DocKind
import io.github.jpicklyk.mcptask.current.application.api.docs.DocUpsertRequest
import io.github.jpicklyk.mcptask.current.application.api.docs.DocWriteOutcome
import io.github.jpicklyk.mcptask.current.application.port.BodySource
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 0b2d5199) for the `BodySource` port and its `FileBodySource` adapter, plus the
 * composition (S14) and the `manage_plan_documents` consumer (S15): scenarios S11 (port half), S12, S13, S14 and S15 of the
 * frozen test-plan (all NEW-SURFACE except S15).
 *
 * Oracles (task-scope 1.5, written before the implementation existed): a caller-relative path is read to RAW text (CRLF
 * normalization stays in the services); containment: absolute paths, `..` escapes, symlink escapes, missing files,
 * directories, NUL / bad characters and malformed UTF-8 are `invalid_request`, never `internal`; the error text names only
 * the caller's relative path, never the root, the real path or an IOException message; one cap `MAX_FILE_BYTES = 65536`
 * counted in BYTES, over it `payload_too_large{max, actual}`; `BodySource.DISABLED` rejects every path with
 * `invalid_request`; the production root is `AppConfig.resolveConfigBaseDir(agentConfigDir)` built once by the composition
 * (the rig's AGENT_CONFIG_DIR is its temp directory) with no `System.getenv` on the path. Envelope section 4 gives the
 * `payload_too_large` detail `{max, actual}`.
 *
 * A `..` segment that stays inside the root (`a/../f.txt`) is NOT asserted either way: task-scope 1.5 only says
 * "containment" and the existing `PathContainmentTest` only covers escapes, so there is no oracle (ambiguity recorded in
 * the manifest, not resolved by reading the implementation).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class FileBodySourceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var root: Path
    private lateinit var outside: Path

    @BeforeEach
    fun setUp(
        @TempDir dir: Path
    ) {
        root = dir.resolve("root")
        Files.createDirectories(root)
        outside = dir.resolve("outside.txt")
        Files.write(outside, "OUTSIDE-SECRET".toByteArray(Charsets.UTF_8))
    }

    private fun write(
        rel: String,
        bytes: ByteArray
    ): Path {
        val target = root.resolve(rel)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        return target
    }

    private fun write(
        rel: String,
        text: String
    ) = write(rel, text.toByteArray(Charsets.UTF_8))

    private fun source() = FileBodySource(root)

    private fun Outcome<String>.ok(): String =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was ${error.code.wire}: ${error.message}")
        }

    private fun Outcome<String>.err(): DomainError =
        when (this) {
            is Outcome.Ok -> fail("expected Err but was Ok(${value.take(40)})")
            is Outcome.Err -> error
        }

    /** The texts an error must not leak: the root, its real path, the outside directory (both separator styles). */
    private fun leaks(): List<String> {
        val roots = listOf(root, root.toRealPath(), outside.parent)
        return roots.flatMap { listOf(it.toString(), it.toString().replace('\\', '/')) }.distinct()
    }

    private fun assertInvalidRequestWithoutLeak(
        label: String,
        path: String,
        callerSuppliedRoot: Boolean = false
    ) {
        val error = source().read(path).err()
        assertEquals("invalid_request", error.code.wire, "$label ($path)")
        val text = error.message + " " + error.detail
        // An absolute path the caller typed itself is echoed back as the caller's own input; only server-side paths are leaks.
        if (!callerSuppliedRoot) {
            for (leak in leaks()) assertFalse(leak in text, "$label: the error must not name $leak but was: $text")
        }
        assertFalse("OUTSIDE-SECRET" in text, "$label: the error must not carry file text: $text")
    }

    // ------------------------------------------------------------------ S11 raw text, S12 containment

    @Test
    fun `S11 the port returns raw text and leaves CRLF normalization to the services`() {
        write("crlf.txt", "a\r\nb\r\nc\rd")
        assertEquals("a\r\nb\r\nc\rd", source().read("crlf.txt").ok())
    }

    @Test
    fun `S12 a plain relative path, a nested one and an empty file read fine (controls for the rejections below)`() {
        write("f.txt", "plain")
        write("sub/dir/n.txt", "nested")
        write("empty.txt", ByteArray(0))
        assertEquals("plain", source().read("f.txt").ok())
        assertEquals("nested", source().read("sub/dir/n.txt").ok())
        assertEquals("", source().read("empty.txt").ok())
    }

    @Test
    fun `S12 absolute paths are invalid_request even when the file exists inside the root`() {
        write("f.txt", "inside")
        assertEquals("inside", source().read("f.txt").ok(), "control: the same file by its relative path reads")
        assertInvalidRequestWithoutLeak("absolute path of an in-root file", root.resolve("f.txt").toString(), callerSuppliedRoot = true)
        assertInvalidRequestWithoutLeak("absolute path outside the root", outside.toString(), callerSuppliedRoot = true)
        assertInvalidRequestWithoutLeak("rooted path", "/f.txt")
    }

    @Test
    fun `S12 a dot-dot escape is invalid_request and the outside file text never comes back`() {
        assertEquals("OUTSIDE-SECRET", String(Files.readAllBytes(outside)), "control: the outside file exists")
        assertInvalidRequestWithoutLeak("parent escape", "../outside.txt")
        write("sub/keep.txt", "k")
        assertInvalidRequestWithoutLeak("nested escape", "sub/../../outside.txt")
        assertInvalidRequestWithoutLeak("deep escape", "sub/x/../../../outside.txt")
        assertInvalidRequestWithoutLeak("backslash escape", "sub\\..\\..\\outside.txt")
    }

    @Test
    fun `S12 drive-relative, UNC, missing, directory and the empty path are invalid_request`() {
        write("sub/inner.txt", "i")
        assertInvalidRequestWithoutLeak("drive-relative", "C:outside.txt")
        assertInvalidRequestWithoutLeak("UNC", "\\\\host\\share\\f.txt")
        assertInvalidRequestWithoutLeak("missing file", "missing.txt")
        assertInvalidRequestWithoutLeak("directory", "sub")
        assertInvalidRequestWithoutLeak("empty path is the root directory", "")
    }

    @Test
    fun `S12 a NUL character is invalid_request and never an internal error`() {
        write("f.txt", "inside")
        val error = source().read("f\u0000.txt").err()
        assertEquals("invalid_request", error.code.wire)
        assertInvalidRequestWithoutLeak("NUL then an escape", "f.txt\u0000../outside.txt")
    }

    @Test
    fun `S12 probe percent-encoded dots are not decoded, so a literal directory named like them reads`() {
        write("%2e%2e/x.txt", "literal")
        assertEquals("literal", source().read("%2e%2e/x.txt").ok(), "no URL decoding: the literal directory is read")
        assertInvalidRequestWithoutLeak("encoded escape with no such directory", "%2e%2e/outside.txt")
    }

    @Test
    fun `S12 a symlink inside the root pointing outside is rejected`() {
        val link = root.resolve("link.txt")
        val created =
            try {
                Files.createSymbolicLink(link, outside)
                true
            } catch (_: UnsupportedOperationException) {
                false
            } catch (_: java.io.IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        // Platform guard only: creating a symbolic link needs a privilege some Windows accounts lack.
        assumeTrue(created, "symbolic links cannot be created on this platform/account")
        assertInvalidRequestWithoutLeak("symlink escape", "link.txt")
    }

    @Test
    fun `S12 malformed UTF-8 is invalid_request and is not replaced with U+FFFD`() {
        write("bad.txt", byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 0x28))
        write("truncated.txt", byteArrayOf('a'.code.toByte(), 0xE2.toByte(), 0x82.toByte()))
        write("good.txt", "café")
        assertEquals("café", source().read("good.txt").ok(), "control: valid multi-byte UTF-8 reads")
        assertInvalidRequestWithoutLeak("invalid continuation", "bad.txt")
        assertInvalidRequestWithoutLeak("truncated sequence at EOF", "truncated.txt")
    }

    // ------------------------------------------------------------------ S13 cap

    @Test
    fun `S13 the cap is 65536 bytes - exactly the cap reads and one more byte is payload_too_large with max and actual`() {
        assertEquals(65536, BodySource.MAX_FILE_BYTES)
        write("at-cap.txt", "a".repeat(65536))
        write("over-cap.txt", "a".repeat(65537))
        assertEquals(65536, source().read("at-cap.txt").ok().length)
        val error = source().read("over-cap.txt").err()
        assertEquals("payload_too_large", error.code.wire)
        val detail = error.detail.toString()
        assertTrue("65536" in detail && "65537" in detail, "detail carries max 65536 and actual 65537: $detail")
    }

    @Test
    fun `S13 the cap counts bytes, not characters`() {
        // 32768 two-byte characters are exactly 65536 bytes; 32769 are 65538 bytes but fewer than 65536 characters.
        write("two-byte-at-cap.txt", "é".repeat(32768))
        write("two-byte-over.txt", "é".repeat(32769))
        assertEquals(32768, source().read("two-byte-at-cap.txt").ok().length)
        assertEquals(
            "payload_too_large",
            source()
                .read("two-byte-over.txt")
                .err()
                .code.wire
        )
    }

    @Test
    fun `S13 a file far over the cap is payload_too_large`() {
        write("huge.txt", "z".repeat(300_000))
        assertEquals(
            "payload_too_large",
            source()
                .read("huge.txt")
                .err()
                .code.wire
        )
    }

    // ------------------------------------------------------------------ S14 DISABLED and composition

    @Test
    fun `S14 DISABLED rejects every path with invalid_request, existing files included`() {
        write("f.txt", "inside")
        assertEquals("inside", source().read("f.txt").ok(), "control: an enabled source reads the same path")
        for (path in listOf("f.txt", root.resolve("f.txt").toString(), "", "../outside.txt", "anything")) {
            val error = BodySource.DISABLED.read(path).err()
            assertEquals("invalid_request", error.code.wire, "DISABLED must reject '$path'")
        }
    }

    @Test
    fun `S14 a ToolExecutionContext built without a bodySource carries DISABLED`() {
        val ctx = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
        assertSame(BodySource.DISABLED, ctx.bodySource)
    }

    @Test
    fun `S14 the production composition reads relative to AGENT_CONFIG_DIR and refuses to leave it`(
        @TempDir dir: Path
    ) {
        val agentDir = dir.resolve("agent-config")
        Files.createDirectories(agentDir)
        Files.write(agentDir.resolve("f"), "composed".toByteArray(Charsets.UTF_8))
        val secret = dir.resolve("secret.txt")
        Files.write(secret, "OUTSIDE-SECRET".toByteArray(Charsets.UTF_8))

        val rig = EventLogRig.build(db.db, agentDir)
        val source = rig.ctx.bodySource

        assertFalse(source === BodySource.DISABLED, "the composition wires a real source")
        assertEquals("composed", source.read("f").ok())
        val escape = source.read("../secret.txt").err()
        assertEquals("invalid_request", escape.code.wire)
        assertFalse("OUTSIDE-SECRET" in escape.message, escape.message)
    }

    // ------------------------------------------------------------------ S15 docs_write consumer

    private suspend fun stash(
        context: ToolExecutionContext,
        rootId: String,
        slug: String,
        file: String
    ): Outcome<DocWriteOutcome> =
        context.unitOfWork.write("FileBodySourceTest.stash") {
            DocHandlers.upsert(
                this,
                context,
                CallContext(reqId = "test", surface = CallLogRecord.SURFACE_MCP, principal = LOCAL_PRINCIPAL.toCallPrincipal()),
                DocUpsertRequest(rootId = IdRef(rootId), kind = DocKind.PLAN, slug = slug, bodyFromFile = file)
            )
        }

    private fun Outcome<DocWriteOutcome>.succeeded(): Boolean = this is Outcome.Ok

    private suspend fun planBody(
        rootId: java.util.UUID,
        slug: String
    ): String? =
        db
            .repositoryProvider()
            .documentStore()
            .get(rootId, DocumentKind.PLAN, slug)
            ?.body

    @Test
    fun `S15 the docs_write upsert reads bodyFromFile via the context bodySource with CRLF, containment and the cap`(): Unit =
        runBlocking {
            val context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork(), bodySource = FileBodySource(root))
            val planRoot = db.repositoryProvider().workItemRepository().create(WorkItem(title = "Plan root", type = "project"))
            write("plan.md", "# P\r\nline\r\n")
            write("plan-at-cap.md", "p".repeat(65536))
            write("plan-over-cap.md", "p".repeat(65537))

            val ok = stash(context, planRoot.id.toString(), "via-file", "plan.md")
            assertTrue(ok.succeeded(), "the upsert must succeed: $ok")
            assertEquals("# P\nline\n", planBody(planRoot.id, "via-file"))

            val atCap = stash(context, planRoot.id.toString(), "at-cap", "plan-at-cap.md")
            assertTrue(atCap.succeeded(), "control: exactly the cap is accepted: $atCap")
            assertEquals(65536, planBody(planRoot.id, "at-cap")?.length)

            val escape = stash(context, planRoot.id.toString(), "escape", "../outside.txt")
            assertFalse(escape.succeeded(), "an escape must fail: $escape")
            assertNull(planBody(planRoot.id, "escape"), "nothing is stored for an escape")
            assertFalse("OUTSIDE-SECRET" in escape.toString(), "the outside text never comes back: $escape")
            assertFalse(
                root.toString().replace('\\', '/') in escape.toString().replace('\\', '/'),
                "the root path never comes back: $escape"
            )

            val over = stash(context, planRoot.id.toString(), "over", "plan-over-cap.md")
            assertFalse(over.succeeded(), "a file over the cap must fail: $over")
            assertNull(planBody(planRoot.id, "over"), "nothing is stored for an over-cap file")
        }

    @Test
    fun `S15 a context without a bodySource refuses bodyFromFile for documents`(): Unit =
        runBlocking {
            val enabled = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork(), bodySource = FileBodySource(root))
            val disabled = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
            val planRoot = db.repositoryProvider().workItemRepository().create(WorkItem(title = "Plan root", type = "project"))
            write("plan.md", "# plan\n")

            val refused = stash(disabled, planRoot.id.toString(), "refused", "plan.md")
            assertFalse(refused.succeeded(), "DISABLED must refuse: $refused")
            assertNull(planBody(planRoot.id, "refused"))

            val control = stash(enabled, planRoot.id.toString(), "accepted", "plan.md")
            assertTrue(control.succeeded(), "control: the same call through an enabled context stores the document: $control")
            assertEquals("# plan\n", planBody(planRoot.id, "accepted"))
        }
}
