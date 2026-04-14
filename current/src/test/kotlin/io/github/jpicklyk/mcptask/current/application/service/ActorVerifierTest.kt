package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActorVerifierTest {

    @Test
    fun `NoOpActorVerifier returns UNVERIFIED`() = runTest {
        val claim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT)
        val result = NoOpActorVerifier.verify(claim)
        assertEquals(VerificationStatus.UNVERIFIED, result.status)
        assertEquals("noop", result.verifier)
        assertNull(result.reason)
    }
}
