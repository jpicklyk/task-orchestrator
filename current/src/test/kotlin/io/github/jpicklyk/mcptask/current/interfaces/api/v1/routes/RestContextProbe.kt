package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * `POST /api/v1/ops/items_read` with `select: context` for one item, the REST read of the gate state that replaced the removed
 * `GET /items/{id}/gate` (Q22). The body is the 4.0 envelope: the gate is `data.gateStatus`.
 */
internal suspend fun HttpClient.restContext(
    itemId: Any,
    token: String
): HttpResponse =
    post("/api/v1/ops/items_read") {
        header("Authorization", "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("{\"select\":\"context\",\"itemId\":\"$itemId\"}")
    }

/** The `data` object of an envelope body. */
internal fun String.envelopeData(): JsonObject = Json.parseToJsonElement(this).jsonObject["data"]!!.jsonObject

/** The `data.gateStatus` object of a `context` envelope body. */
internal fun String.contextGate(): JsonObject = envelopeData()["gateStatus"]!!.jsonObject
