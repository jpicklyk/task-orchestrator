package io.github.jpicklyk.mcptask.current.application.tools.config

import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentStashResult
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocument
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentSummary
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.security.PathContainment
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP tool for stashing, reading back, and listing per-root plan documents — the transport-agnostic
 * sync path for [PlanDocumentService], mirroring [ManageProjectConfigTool]'s push/get shape.
 *
 * Supports three operations:
 * - **stash**: upsert the document at a `rootId`+`slug`, validating the target root (must
 *   exist, must be depth 0) via [PlanDocumentService]. A slug already `adopted` is rejected — see
 *   [PlanDocumentStashResult.AdoptedConflict].
 * - **get**: reads back the full stored document (including body) for a `rootId`+`slug`.
 * - **list**: reads back metadata-only summaries (no body) for every document under a `rootId`.
 *
 * @param agentConfigBaseDir The trusted root that `bodyFromFile` paths are resolved strictly
 *   relative to (see [PathContainment]) — same `AGENT_CONFIG_DIR` -> `user.dir` resolution as
 *   [io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool]. Overridable for tests.
 */
class ManagePlanDocumentsTool(
    private val agentConfigBaseDir: Path =
        Paths.get(AppConfig.resolveConfigBaseDir(System.getenv("AGENT_CONFIG_DIR")))
) : BaseToolDefinition() {
    override val name = "manage_plan_documents"

    override val description =
        """
Stash, read back, or list per-root plan documents — free-floating planning docs an agent stashes
ahead of adoption into a real work item.

**stash** — upserts the document at `rootId`+`slug`; the body comes from `body` (inline) or
`bodyFromFile` (server-side path), mutually exclusive — providing both, or neither, fails.
Re-stashing an existing `pending` slug overwrites it in place. A slug already `adopted` is rejected
with a CONFLICT_ERROR: adoption is a one-way transition and cannot be undone by stashing over it.

**get** — returns the full stored document (including body) for `rootId`+`slug`, or a
not-found error when no document exists at that slug.

**list** — returns metadata-only summaries (never the body) for every document under `rootId`,
optionally filtered to a single `status` (pending or adopted).
        """.trimIndent()

    override val category = ToolCategory.SYSTEM

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "operation",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Operation: stash, get, list"))
                            put("enum", JsonArray(listOf("stash", "get", "list").map { JsonPrimitive(it) }))
                        }
                    )
                    put(
                        "rootId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Project root WorkItem UUID or hex prefix (4+ chars) — must be a depth-0 item for stash"
                                )
                            )
                        }
                    )
                    put(
                        "slug",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("Document identifier, unique within rootId (stash, get only)")
                            )
                        }
                    )
                    put(
                        "body",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Inline document text to stash; mutually exclusive with bodyFromFile; max 64 KiB"
                                )
                            )
                        }
                    )
                    put(
                        "bodyFromFile",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Server-side path to stash instead of body; mutually exclusive with body; " +
                                        "resolved strictly relative to the agent config root; rejects absolute " +
                                        "paths, '..', and symlink escapes; file must exist, <=65536 bytes; " +
                                        "CRLF normalized to LF"
                                )
                            )
                        }
                    )
                    put(
                        "status",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("list only: filter to a single status"))
                            put("enum", JsonArray(listOf("pending", "adopted").map { JsonPrimitive(it) }))
                        }
                    )
                },
            required = listOf("operation", "rootId")
        )

    override fun validateParams(params: JsonElement) {
        val operation = requireString(params, "operation")
        when (operation) {
            "stash" -> {
                validateIdOrPrefix(params, "rootId", required = true)
                requireString(params, "slug")
                val body = optionalString(params, "body")
                val bodyFromFile = optionalString(params, "bodyFromFile")
                if (body != null && bodyFromFile != null) {
                    throw ToolValidationException("'body' and 'bodyFromFile' are mutually exclusive — provide only one")
                }
                if (body == null && bodyFromFile == null) {
                    throw ToolValidationException("stash requires either 'body' or 'bodyFromFile'")
                }
            }
            "get" -> {
                validateIdOrPrefix(params, "rootId", required = true)
                requireString(params, "slug")
            }
            "list" -> {
                validateIdOrPrefix(params, "rootId", required = true)
                optionalString(params, "status")?.let { value ->
                    if (value != "pending" && value != "adopted") {
                        throw ToolValidationException("Invalid status: $value. Must be pending or adopted")
                    }
                }
            }
            else -> throw ToolValidationException("Invalid operation: $operation. Must be stash, get, or list")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement =
        when (val operation = requireString(params, "operation")) {
            "stash" -> executeStash(params, context)
            "get" -> executeGet(params, context)
            "list" -> executeList(params, context)
            else -> errorResponse("Invalid operation: $operation. Must be stash, get, or list", ErrorCodes.VALIDATION_ERROR)
        }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        val op = (params as? JsonObject)?.get("operation")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        if (isError) return "manage_plan_documents($op) failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val rootId = data?.get("rootId")?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
        return when (op) {
            "stash" -> "Stashed plan document for root $rootId"
            "get" -> "Retrieved plan document for root $rootId"
            "list" -> "Listed plan documents for root $rootId"
            else -> super.userSummary(params, result, isError)
        }
    }

    // ──────────────────────────────────────────────
    // stash
    // ──────────────────────────────────────────────

    private suspend fun executeStash(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootId, idError) = resolveItemId(params, "rootId", context)
        if (idError != null) return idError
        val slug = requireString(params, "slug")

        val bodyInline = optionalString(params, "body")
        val bodyFromFilePath = optionalString(params, "bodyFromFile")
        val body =
            try {
                if (bodyFromFilePath != null) readBodyFromFile(bodyFromFilePath) else (bodyInline ?: "")
            } catch (e: ToolValidationException) {
                return errorResponse(e.message ?: "bodyFromFile could not be read", ErrorCodes.VALIDATION_ERROR)
            }

        val service = PlanDocumentService(context.repositoryProvider)
        return when (val result = service.stash(rootId!!, slug, body)) {
            is PlanDocumentStashResult.Success -> successResponse(documentToJson(result.document, includeBody = false))
            is PlanDocumentStashResult.NotFound ->
                errorResponse(
                    "Root WorkItem not found: ${result.rootItemId}",
                    ErrorCodes.RESOURCE_NOT_FOUND
                )
            is PlanDocumentStashResult.NotDepthZero ->
                errorResponse(
                    "rootId must reference a depth-0 (root) WorkItem; '${result.rootItemId}' has depth ${result.depth}",
                    ErrorCodes.VALIDATION_ERROR
                )
            is PlanDocumentStashResult.TooLarge ->
                errorResponse(
                    "body is ${result.sizeBytes} bytes, exceeds the ${result.maxBytes} byte " +
                        "(${result.maxBytes / 1024} KiB) limit",
                    ErrorCodes.VALIDATION_ERROR
                )
            is PlanDocumentStashResult.AdoptedConflict ->
                errorResponse(
                    "slug '$slug' has already been adopted" +
                        (result.existing.adoptedByItemId?.let { " by item $it" } ?: "") +
                        "; adoption is one-way and cannot be overwritten",
                    ErrorCodes.CONFLICT_ERROR
                )
            is PlanDocumentStashResult.RepositoryError ->
                errorResponse(
                    "Failed to store plan document: ${result.message}",
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    // ──────────────────────────────────────────────
    // get
    // ──────────────────────────────────────────────

    private suspend fun executeGet(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootId, idError) = resolveItemId(params, "rootId", context)
        if (idError != null) return idError
        val slug = requireString(params, "slug")

        val service = PlanDocumentService(context.repositoryProvider)
        return when (val result = service.get(rootId!!, slug)) {
            is Result.Success -> {
                val document =
                    result.data ?: return errorResponse(
                        "No plan document found for root $rootId, slug $slug",
                        ErrorCodes.RESOURCE_NOT_FOUND
                    )
                successResponse(documentToJson(document, includeBody = true))
            }
            is Result.Error ->
                errorResponse(
                    "Failed to read plan document: ${result.error.message}",
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    // ──────────────────────────────────────────────
    // list
    // ──────────────────────────────────────────────

    private suspend fun executeList(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val (rootId, idError) = resolveItemId(params, "rootId", context)
        if (idError != null) return idError
        val statusFilter = optionalString(params, "status")?.let { PlanDocumentStatus.fromDbValue(it) }

        val service = PlanDocumentService(context.repositoryProvider)
        return when (val result = service.list(rootId!!, statusFilter)) {
            is Result.Success ->
                successResponse(
                    buildJsonObject {
                        put("rootId", JsonPrimitive(rootId.toString()))
                        put("plans", JsonArray(result.data.map { summaryToJson(it) }))
                    }
                )
            is Result.Error ->
                errorResponse(
                    "Failed to list plan documents: ${result.error.message}",
                    ErrorCodes.DATABASE_ERROR
                )
        }
    }

    // ──────────────────────────────────────────────
    // JSON shaping
    // ──────────────────────────────────────────────

    private fun documentToJson(
        document: PlanDocument,
        includeBody: Boolean
    ): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(document.id.toString()))
            put("rootId", JsonPrimitive(document.rootItemId.toString()))
            put("slug", JsonPrimitive(document.slug))
            put("contentHash", JsonPrimitive(document.contentHash))
            put("status", JsonPrimitive(document.status.toDbValue()))
            document.adoptedByItemId?.let { put("adoptedByItemId", JsonPrimitive(it.toString())) }
            put("createdAt", JsonPrimitive(document.createdAt.toString()))
            put("updatedAt", JsonPrimitive(document.modifiedAt.toString()))
            if (includeBody) put("body", JsonPrimitive(document.body))
        }

    private fun summaryToJson(summary: PlanDocumentSummary): JsonObject =
        buildJsonObject {
            put("id", JsonPrimitive(summary.id.toString()))
            put("rootId", JsonPrimitive(summary.rootItemId.toString()))
            put("slug", JsonPrimitive(summary.slug))
            put("contentHash", JsonPrimitive(summary.contentHash))
            put("status", JsonPrimitive(summary.status.toDbValue()))
            summary.adoptedByItemId?.let { put("adoptedByItemId", JsonPrimitive(it.toString())) }
            put("createdAt", JsonPrimitive(summary.createdAt.toString()))
            put("updatedAt", JsonPrimitive(summary.modifiedAt.toString()))
        }

    // ──────────────────────────────────────────────
    // bodyFromFile resolution
    // ──────────────────────────────────────────────

    /**
     * Reads and validates a `bodyFromFile` path via [PathContainment], enforcing the same 64KB cap
     * as [PlanDocumentService.MAX_BODY_BYTES] and normalizing CRLF to LF — mirrors
     * [io.github.jpicklyk.mcptask.current.application.tools.notes.ManageNotesTool.readBodyFromFile]
     * exactly, so a file stashed via REST PUT and via this path land byte-identical content.
     */
    private fun readBodyFromFile(relativePath: String): String {
        when (val result = PathContainment.resolveWithinBase(agentConfigBaseDir, relativePath)) {
            is PathContainment.Result.Rejected ->
                throw ToolValidationException("bodyFromFile rejected — ${result.reason}")
            is PathContainment.Result.Allowed -> {
                val file = result.realPath.toFile()
                val size = file.length()
                if (size > PlanDocumentService.MAX_BODY_BYTES) {
                    throw ToolValidationException(
                        "bodyFromFile '$relativePath' is $size bytes, exceeds the " +
                            "${PlanDocumentService.MAX_BODY_BYTES} byte cap"
                    )
                }
                val raw = file.readText(Charsets.UTF_8)
                // Normalize CRLF line endings to LF, matching ManageNotesTool's bodyFromFile
                // behavior — files authored or edited on Windows commonly contain CRLF.
                return raw.replace("\r\n", "\n")
            }
        }
    }
}
