package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item b08a798a) for [ScopeGuard.checkTagWrite], scenario S12 of the frozen test-plan
 * (NEW-SURFACE; substitute verification is the decision-point mutations M1 to M3 of the plan).
 *
 * Oracle (frozen rule, diagnosis D1): a scope whose `tagsInclude` is empty admits any write. Otherwise the tags a write removes
 * or adds (symmetric difference of the before and after CSV tag sets, elements trimmed, empties dropped) must all be listed
 * tags, and the resulting tags must contain at least one listed tag; a violation is `forbidden{tag, required}` with
 * `required` = the listed tags sorted and joined by `,`. Pure function: no repository.
 */
class ScopeGuardTagWriteTest {
    private val alpha = AccessScope.principal(rootIds = null, tagsInclude = setOf("alpha"))
    private val alphaGamma = AccessScope.principal(rootIds = null, tagsInclude = setOf("gamma", "alpha"))

    private fun Outcome<Unit>.assertOk(label: String) = assertTrue(this is Outcome.Ok, "$label: expected Ok but was $this")

    private fun Outcome<Unit>.assertForbiddenTag(
        label: String,
        required: String
    ) {
        assertTrue(this is Outcome.Err, "$label: expected Err but was $this")
        val error = (this as Outcome.Err).error
        assertEquals(ErrorCode.FORBIDDEN, error.code, label)
        assertEquals(ErrorDetail.Forbidden(ForbiddenScope.TAG, required), error.detail, label)
    }

    @Test
    fun `S12 an unrestricted scope admits any tag write`() {
        val unrestricted = AccessScope.unrestricted()
        ScopeGuard.checkTagWrite(unrestricted, null, null).assertOk("null to null")
        ScopeGuard.checkTagWrite(unrestricted, "x", "y").assertOk("x to y")
        // a root-only restriction has no tag half, so it is not tag-restricted either
        val rootOnly = AccessScope.principal(rootIds = emptySet(), tagsInclude = emptySet())
        ScopeGuard.checkTagWrite(rootOnly, "x", "y").assertOk("root-only scope")
    }

    @Test
    fun `S12 a tag scope admits a first tagging that carries only the listed tag`() {
        ScopeGuard.checkTagWrite(alpha, null, "alpha").assertOk("null to alpha")
    }

    @Test
    fun `S12 a tag scope refuses a result without a listed tag, naming the listed tags`() {
        ScopeGuard.checkTagWrite(alpha, null, null).assertForbiddenTag("null to null", "alpha")
        ScopeGuard.checkTagWrite(alpha, null, "").assertForbiddenTag("null to empty", "alpha")
        ScopeGuard.checkTagWrite(alpha, null, " , ").assertForbiddenTag("null to blanks", "alpha")
        ScopeGuard.checkTagWrite(alpha, "alpha", null).assertForbiddenTag("dropping the last listed tag", "alpha")
    }

    @Test
    fun `S12 a tag scope refuses dropping or adding a foreign tag`() {
        ScopeGuard.checkTagWrite(alpha, "alpha,shared", "alpha").assertForbiddenTag("drops foreign shared", "alpha")
        ScopeGuard.checkTagWrite(alpha, "alpha", "alpha,beta").assertForbiddenTag("adds foreign beta", "alpha")
        ScopeGuard.checkTagWrite(alpha, null, "alpha,beta").assertForbiddenTag("first tagging with a foreign tag", "alpha")
        ScopeGuard.checkTagWrite(alpha, "beta", "alpha").assertForbiddenTag("swaps beta for alpha", "alpha")
    }

    @Test
    fun `S12 a tag scope admits a rewrite that leaves the foreign tags untouched`() {
        ScopeGuard.checkTagWrite(alpha, "alpha,shared", "alpha,shared").assertOk("unchanged")
        // order and spacing are not content
        ScopeGuard.checkTagWrite(alpha, "alpha,shared", "shared, alpha").assertOk("reordered")
        // a foreign tag kept while another listed one is added
        ScopeGuard.checkTagWrite(alphaGamma, "alpha,shared", "alpha,gamma,shared").assertOk("adds listed gamma, keeps shared")
    }

    @Test
    fun `S12 the listed tags are matched after trimming and the required value is sorted`() {
        ScopeGuard.checkTagWrite(alphaGamma, "alpha", " alpha , gamma ").assertOk("trimmed elements")
        ScopeGuard.checkTagWrite(alphaGamma, "alpha", "beta").assertForbiddenTag("two listed tags", "alpha,gamma")
    }

    @Test
    fun `S12 matching is exact and case-sensitive`() {
        ScopeGuard.checkTagWrite(alpha, null, "Alpha").assertForbiddenTag("capitalised listed tag is a different tag", "alpha")
        ScopeGuard.checkTagWrite(alpha, "alpha", "alpha,Alpha").assertForbiddenTag("adds a differently cased tag", "alpha")
    }
}
