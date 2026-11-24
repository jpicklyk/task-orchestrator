package io.github.jpicklyk.mcptask.current.platform.rest.auth

import io.github.jpicklyk.mcptask.current.platform.call.Principal
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.HostName
import io.github.jpicklyk.mcptask.current.platform.rest.correlation.CLIENT_HOST_HEADER
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.util.AttributeKey

/**
 * AttributeKey used to attach the resolved [Principal] to the request pipeline.
 *
 * Routes and plugins downstream of `ApiBearerAuth` retrieve the principal via:
 * ```kotlin
 * val principal = call.attributes[ApiPrincipalKey]
 * ```
 * or via the convenience extension [ApplicationCall.apiPrincipal].
 */
val ApiPrincipalKey: AttributeKey<Principal> = AttributeKey("ApiPrincipal")

/**
 * Retrieves the authenticated [Principal] attached to this call by `ApiBearerAuth`.
 *
 * @throws IllegalStateException if the plugin has not been installed or the call was
 *   not authenticated (i.e., the plugin responded with 401 before this is called).
 */
fun ApplicationCall.apiPrincipal(): Principal = attributes[ApiPrincipalKey]

/** This principal with [Principal.host] set from the request: `X-Client-Host`, else the `User-Agent` product. */
internal fun Principal.withHostOf(call: ApplicationCall): Principal =
    copy(host = HostName.forRest(call.request.header(CLIENT_HOST_HEADER), call.request.header(HttpHeaders.UserAgent)))
