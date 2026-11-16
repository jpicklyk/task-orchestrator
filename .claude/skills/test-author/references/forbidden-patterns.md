# Forbidden Test Patterns — Before/After Examples

Companion to the served rule `forbidden-test-patterns` (its claude section points here), alongside
`SKILL.md` section 7. Each pattern below is shown as it actually recurred in
this codebase's history (paraphrased to the general shape, not copy-pasted from a specific commit)
alongside the fix. Use these as the concrete shape to watch for when writing or reviewing tests
under the `needs-test-author` trait — declaring a pattern in `test-manifest` means recognizing it
looks like this, not just recognizing the pattern's name.

All examples are Kotlin/JUnit5, matching this codebase's test conventions
(`current/src/test/kotlin/...`).

Every type, function and constant named in these examples is declared in `current/src` at HEAD
(`WorkItem`, `ClaimState`, `RrfFusion`, `etagForVersion`, `AdvanceService`, `WorkItemRepository`,
`ItemsReadTool`); local helper names (`seedItem`, `advanceService`) stand for the test's own setup.
Use the declarations block for the exact signatures.

---

## 1. `assumeTrue` on Non-Platform Conditions

`assumeTrue` (and `org.junit.jupiter.api.Assumptions`) exists to skip a test on an environment it
structurally cannot run in - a Windows-only path test on CI running Linux, a test requiring a
service that isn't provisioned locally. Using it to guard a *behavioral* precondition converts a
would-be failure into a silent, permanently-green skip. Three real production bugs in this
codebase's history shipped wrapped exactly this way - the test that would have caught each one
never ran red, because it never ran at all.

**Before - behavioral condition disguised as an environment guard:**

```kotlin
@Test
fun `an expired claim is not active`() {
    val now = Instant.parse("2026-01-01T12:00:00Z")
    val item = claimedItem(claimedAt = now.minusSeconds(60), claimExpiresAt = now.minusSeconds(5))

    // "only meaningful once the claim is populated" - but this is the exact
    // state under test, not an environment precondition.
    assumeTrue(ClaimState.of(item) != null)

    assertFalse(ClaimState.of(item)!!.isActive(now))
}
```

If `claimedItem` ever regresses and stops populating the claim fields, this test silently stops
running instead of failing - the exact failure mode that let a claim-expiry bug ship unnoticed.

**After - the precondition is asserted, not assumed away; a genuine platform guard (if any) is
separate and clearly labeled:**

```kotlin
@Test
fun `an expired claim is not active`() {
    val now = Instant.parse("2026-01-01T12:00:00Z")
    val item = claimedItem(claimedAt = now.minusSeconds(60), claimExpiresAt = now.minusSeconds(5))
    val state = checkNotNull(ClaimState.of(item)) { "test setup invariant: claimedItem must populate the claim" }

    assertFalse(state.isActive(now), "expected an expired claim to be inactive, got $state")
}

@Test
@EnabledOnOs(OS.WINDOWS)
fun `UNC path resolves against the configured share`() {
    // A genuine platform guard - this scenario only exists on Windows.
    ...
}
```

`checkNotNull` fails loudly if the setup invariant breaks, instead of quietly removing the test
from the suite. The platform-specific test uses `@EnabledOnOs`, JUnit5's actual mechanism for
environment gating, so it's visually distinct from a behavioral assumption.

---

## 2. Disjunctive Escapes

An assertion with an `||` branch that treats "the operation did nothing" as an acceptable
alternative to "the operation produced the intended result" passes identically whether the
feature works or is a no-op.

**Before:**

```kotlin
@Test
fun `items_read search returns the matching item for a matching query`() {
    val hits = searchTitles(query = "authentication")   // titles from an items_read search call

    // Accepts either a real result set OR an empty one - passes either way.
    assertTrue(hits.isNotEmpty() || hits.isEmpty())
}
```

That assertion is a tautology - `isNotEmpty() || isEmpty()` is true of every possible list. Even
a narrower version, `hits.any { it.contains("auth") } || hits.isEmpty()`, still passes if the
search returns nothing at all, which is precisely the failure mode a search feature test needs to
catch.

**After - the oracle states what must be true, with no no-op escape hatch:**

```kotlin
@Test
fun `items_read search returns the matching item for a matching query`() {
    seedItem(title = "Implement authentication flow")
    seedItem(title = "Unrelated documentation update")

    val hits = searchTitles(query = "authentication")

    assertEquals(listOf("Implement authentication flow"), hits, "expected exactly the seeded matching item")
}
```

If a genuine "no results" scenario needs coverage, it gets its own scenario with its own oracle
(`assertTrue(searchTitles("zzz-no-such-term").isEmpty())` against seed data with no match) - never
folded into the positive case as an alternate acceptable outcome.

---

## 3. Oracle-From-Implementation

The test's expected value was read from the implementation under test - either by literally
calling into the same formula, or by running the code once and pasting whatever it returned.
Either way, the test now verifies "the code agrees with itself," which stays green even if the
formula or the pasted value is wrong.

**Before - the test recomputes the same formula the implementation uses:**

```kotlin
@Test
fun `RrfFusion scores rank 1 higher than rank 2`() {
    val k = RrfFusion.K
    val expectedRank1 = 1.0 / (k + 1)   // this is RrfFusion.score()'s own formula, restated
    val expectedRank2 = 1.0 / (k + 2)

    assertEquals(expectedRank1, RrfFusion.score(rank = 1))
    assertEquals(expectedRank2, RrfFusion.score(rank = 2))
}
```

If `RrfFusion.score`'s formula were wrong - say, `k - rank` instead of `k + rank` - this test
would still pass, because the test derived its expectation from the same (wrong) formula rather
than from the Reciprocal Rank Fusion definition the formula is supposed to implement.

**After - the oracle is the documented algorithm (Reciprocal Rank Fusion, standard IR technique:
`score = 1/(k + rank)`, cited independently of this codebase), computed by hand for a fixed input,
not read from the function under test:**

```kotlin
@Test
fun `RrfFusion scores rank 1 higher than rank 2`() {
    // Oracle: RRF definition, score = 1 / (k + rank), k = 60 per RrfFusion.K's documented
    // default. Computed independently: 1/(60+1) = 0.016393..., 1/(60+2) = 0.016129...
    val rank1Score = RrfFusion.score(rank = 1)
    val rank2Score = RrfFusion.score(rank = 2)

    assertEquals(0.016393442622950821, rank1Score, 1e-12)
    assertEquals(0.016129032258064516, rank2Score, 1e-12)
    assertTrue(rank1Score > rank2Score, "lower rank number must score higher under RRF")
}
```

The literal expected values were computed outside the codebase (a calculator, or a second
implementation) - not by calling `RrfFusion.score` and copying its output.

**A second shape of the same failure - tautological self-computation of a derived value**, seen
in ETag-style ("compute a value from a version, then assert the computed value equals itself")
tests:

```kotlin
// Before - asserts the function agrees with itself
@Test
fun `etagForVersion produces a quoted version tag`() {
    val version = 7L
    val expected = "\"v$version\""   // re-derives etagForVersion's own body
    assertEquals(expected, etagForVersion(version))
}

// After - the oracle is the documented format contract (RFC 9110 strong validator: a quoted
// opaque string, here "v<version>"), written as a literal
@Test
fun `etagForVersion produces a quoted version tag`() {
    assertEquals("\"v7\"", etagForVersion(7L))
    assertNotEquals(etagForVersion(7L), etagForVersion(8L), "every write bumps the version, so the tag must change")
}
```

The tell in both cases: if you can write the assertion without ever opening the file under test,
it's a real oracle. If writing the assertion requires reading the function's own source, it's
oracle-from-implementation.

---

## 4. Assert-Not-Null-Only

`assertNotNull(result)`, `result != null`, or `list.isNotEmpty()` standing in for a check of the
actual value, size, or content leaves a wrong-but-present result indistinguishable from a
correct one.

**Before:**

```kotlin
@Test
fun `items_read context returns the resolved schema for an item with traits`() {
    val context = readContext(itemWithTrait.id)   // the items_read context select, as JSON

    assertNotNull(context)
    assertTrue(context.getValue("schema").jsonArray.isNotEmpty())
}
```

This passes if `schema` contains the wrong notes, the wrong roles, or the base schema notes with
the trait notes silently missing - anything non-empty satisfies it.

**After:**

```kotlin
@Test
fun `items_read context returns the resolved schema for an item with traits`() {
    val context = readContext(itemWithTrait.id)

    val rows = context.getValue("schema").jsonArray.map { it.jsonObject }
    assertEquals(
        setOf("task-scope", "test-plan"),   // base schema key + trait-merged key
        rows.map { it.getValue("key").jsonPrimitive.content }.toSet(),
        "expected the base schema note merged with needs-test-author's queue-phase note"
    )
    val testPlan = rows.first { it.getValue("key").jsonPrimitive.content == "test-plan" }
    assertEquals("queue", testPlan.getValue("role").jsonPrimitive.content)
}
```

The tool under test is `ItemsReadTool` (`select` `context`); the wire shape is the tool's own
`describe: true` schema, not a guess.

---

## 5. Mock-Order-Only Verification

Every collaborator mocked, and the only assertion is that calls happened in some order - nothing
is asserted about the unit's actual output or resulting state. This confirms the code path was
taken, not that it produced a correct result.

**Before:**

```kotlin
@Test
fun `advance reads the item before persisting the transition`() = runTest {
    val repository = mockk<WorkItemRepository>(relaxed = true)
    val service = advanceService(repository)

    service.advance(item, trigger = "start", summary = null, actorClaim = null, verification = null)

    coVerifyOrder {
        repository.getById(item.id)
        repository.update(any())
    }
}
```

If `update` were called with the wrong target role, or the policy's decision were ignored
entirely, this test would still pass - it never looks at what was actually passed or returned.

**After - order is verified where it matters, plus a real assertion on the resulting state:**

```kotlin
@Test
fun `advance reads the item before persisting the transition`() = runTest {
    val repository = mockk<WorkItemRepository>()
    coEvery { repository.getById(item.id) } returns item
    coEvery { repository.update(any()) } answers { firstArg() }
    val service = advanceService(repository)

    val outcome = service.advance(item, trigger = "start", summary = null, actorClaim = null, verification = null)

    val result = (outcome as AdvanceOutcome.Success).result
    assertEquals(Role.WORK, result.newRole)
    assertTrue(result.applied)
    coVerifyOrder {
        repository.getById(item.id)
        repository.update(match { it.id == item.id && it.role == Role.WORK })
    }
}
```

`repository.update(match { ... Role.WORK })` asserts the *argument*, not just that the call
happened, and the return value is checked independently of the mock interactions.

---

## 6. Catch-Swallowed Assertions

An assertion placed inside a `try` block whose `catch` logs or silently ignores the exception
means an assertion failure (which JUnit5 raises as an `AssertionError`, a `Throwable`) and a
legitimately caught exception both read as "test passed" - because nothing outside the `try`
block observed the difference.

**Before:**

```kotlin
@Test
fun `advance start moves a queue item to work`() = runTest {
    try {
        val outcome = advanceService.advance(item, trigger = "start", summary = null, actorClaim = null, verification = null)
        assertEquals(Role.WORK, (outcome as AdvanceOutcome.Success).result.newRole)
    } catch (e: Exception) {
        // "some environments don't have the schema loaded yet"
        println("skipping: ${e.message}")
    }
}
```

If `advance` throws for any reason - including a real regression - the failure is caught by the
same `catch (e: Exception)` as a genuine setup problem, logged, and the test reports green.

**After - no `catch` around the assertions; a genuine setup precondition is checked before the
call, not used to swallow failures after it:**

```kotlin
@Test
fun `advance start moves a queue item to work`() = runTest {
    check(item.role == Role.QUEUE) { "test setup invariant: the item must start in queue" }

    val outcome = advanceService.advance(item, trigger = "start", summary = null, actorClaim = null, verification = null)

    assertEquals(Role.WORK, (outcome as AdvanceOutcome.Success).result.newRole)
}
```

If the call itself is expected to throw under some condition, assert that directly with
`assertThrows<SpecificExceptionType> { ... }` - never a bare `catch` that treats any exception,
assertion failures included, as an acceptable outcome.
