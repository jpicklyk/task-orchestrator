package io.github.jpicklyk.mcptask.current.infrastructure.identity.did

import kotlinx.serialization.json.JsonObject

data class DidDocument(
    val id: String,
    val verificationMethods: List<VerificationMethod>,
    val assertionMethod: List<String> = emptyList(),
    val authentication: List<String> = emptyList()
)

data class VerificationMethod(
    val id: String,
    val type: String,
    val controller: String,
    val publicKeyJwk: JsonObject? = null,
    val publicKeyMultibase: String? = null
)
