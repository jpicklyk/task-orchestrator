# Forbidden Test Patterns — Before/After Examples

Companion reference for `SKILL.md` §7. Each pattern below is shown as it actually recurred in
this codebase's history (paraphrased to the general shape, not copy-pasted from a specific commit)
alongside the fix. Use these as the concrete shape to watch for when writing or reviewing tests
under the `needs-test-author` trait — declaring a pattern in `test-manifest` means recognizing it
looks like this, not just recognizing the pattern's name.

All examples are Kotlin/JUnit5, matching this codebase's test conventions
(`current/src/test/kotlin/...`).

---

## 1. `assumeTrue` on Non-Platform Conditions

`assumeTrue` (and `org.junit.jupiter.api.Assumptions`) exists to skip a test on an environment it
structurally cannot run in — a Windows-only path test on CI running Linux, a test requiring a
service that isn't provisioned locally. Using it to guard a *behavioral* precondition converts a
would-be failure into a silent, permanently-green skip. Three real production bugs in this
codebase's history shipped wrapped exactly this way — the test that would have caught each one
never ran red, because it never ran at all.

**Before — behavioral condition disguised as an environment guard:**

```kotlin
@Test
fun `claim rejects when TTL already expired`() {
    val item = createClaimedItem(ttlExpiresAt = Instant.now().minusSeconds(5))

    // "only meaningful once the claim table is populated" — but this is the exact
    // state under test, not an environment precondition.
    assumeTrue(item.claim != null)

    val result = claimTool.execute(ClaimRequest(item.id, actor = "agent-2"))

    assertTrue(result is ClaimResult.Rejected)
}
```

If `createClaimedItem` ever regresses and stops populating `claim`, this test silently stops
running instead of failing — the exact failure mode that let a claim-expiry bug ship unnoticed.

**After — the precondition is asserted, not assumed away; a genuine platform guard (if any) is
separate and clearly labeled:**

```kotlin
@Test
fun `claim rejects when TTL already expired`() {
    val item = createClaimedItem(ttlExpiresAt = Instant.now().minusSeconds(5))
    check(item.claim != null) { "test setup invariant: createClaimedItem must populate claim" }

    val result = claimTool.execute(ClaimRequest(item.id, actor = "agent-2"))

    assertTrue(result is ClaimResult.Rejected, "expected expired claim to be rejected, got $result")
}

@Test
@EnabledOnOs(OS.WINDOWS)
fun `UNC path claim resolves against configured share`() {
    // A genuine platform guard — this scenario only exists on Windows.
    ...
}
```

`check()` fails loudly if the setup invariant breaks, instead of quietly removing the test from
the suite. The platform-specific test uses `@EnabledOnOs`, JUnit5's actual mechanism for
environment gating, so it's visually distinct from a behavioral assumption.

---

## 2. Disjunctive Escapes

An assertion with an `||` branch that treats "the operation did nothing" as an acceptable
alternative to "the operation produced the intended result" passes identically whether the
feature works or is a no-op.

**Before:**

```kotlin
@Test
fun `search returns FTS-ranked results for a matching query`() {
    val results = queryItemsTool.execute(QueryRequest(search = "authentication"))

    // Accepts either a real ranked result set OR an empty one — passes either way.
    assertTrue(results.hits.isNotEmpty() || results.hits.isEmpty())
}
```

That assertion is a tautology — `isNotEmpty() || isEmpty()` is true of every possible list. Even
a narrower version, `results.hits.any { it.title.contains("auth") } || results.hits.isEmpty()`,
still passes if the search returns nothing at all, which is precisely the failure mode a search
feature test needs to catch.

**After — the oracle states what must be true, with no no-op escape hatch:**

```kotlin
@Test
fun `search returns FTS-ranked results for a matching query`() {
    seedItem(title = "Implement authentication flow")
    seedItem(title = "Unrelated documentation update")

    val results = queryItemsTool.execute(QueryRequest(search = "authentication"))

    assertEquals(1, results.hits.size, "expected exactly the seeded matching item")
    assertEquals("Implement authentication flow", results.hits.first().title)
}
```

If a genuine "no results" scenario needs coverage, it gets its own scenario with its own oracle
(`assertTrue(results.hits.isEmpty())` against a query with no matching seed data) — never folded
into the positive case as an alternate acceptable outcome.

---

## 3. Oracle-From-Implementation

The test's expected value was read from the implementation under test — either by literally
calling into the same formula, or by running the code once and pasting whatever it returned.
Either way, the test now verifies "the code agrees with itself," which stays green even if the
formula or the pasted value is wrong.

**Before — the test recomputes the same formula the implementation uses:**

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

If `RrfFusion.score`'s formula were wrong — say, `k - rank` instead of `k + rank` — this test
would still pass, because the test derived its expectation from the same (wrong) formula rather
than from the Reciprocal Rank Fusion definition the formula is supposed to implement.

**After — the oracle is the documented algorithm (Reciprocal Rank Fusion, standard IR technique:
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
implementation) — not by calling `RrfFusion.score` and copying its output.

**A second shape of the same failure — tautological self-computation of a derived value**, seen
in ETag-style ("compute a value from a timestamp/version, then assert the computed value equals
itself") tests:

```kotlin
// Before — asserts the function agrees with itself
@Test
fun `etagFor produces a versioned tag`() {
    val modifiedAt = Instant.parse("2026-07-31T12:00:00Z")
    val expected = "\"v1-${modifiedAt.toEpochMilli()}\""   // re-derives etagFor's own body
    assertEquals(expected, etagFor(modifiedAt))
}

// After — the oracle is the documented format contract, with a value computed independently
@Test
fun `etagFor produces a versioned tag`() {
    // Oracle: documented contract is "v1-<modifiedAtMillis>"; 2026-07-31T12:00:00Z is
    // epoch millis 1785499200000 (computed independently via `date -d ... +%s%3N`).
    val modifiedAt = Instant.parse("2026-07-31T12:00:00Z")
    assertEquals("\"v1-1785499200000\"", etagFor(modifiedAt))
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
fun `get_context returns resolved schema for item with traits`() {
    val context = getContextTool.execute(GetContextRequest(itemId = itemWithTrait.id))

    assertNotNull(context)
    assertTrue(context.expectedNotes.isNotEmpty())
}
```

This passes if `expectedNotes` contains the wrong notes, the wrong roles, or the base schema
notes with the trait notes silently missing — anything non-empty satisfies it.

**After:**

```kotlin
@Test
fun `get_context returns resolved schema for item with traits`() {
    val context = getContextTool.execute(GetContextRequest(itemId = itemWithTrait.id))

    val noteKeys = context.expectedNotes.map { it.key }.toSet()
    assertEquals(
        setOf("task-scope", "test-plan"),  // base schema key + trait-merged key
        noteKeys,
        "expected base schema note merged with needs-test-author's queue-phase note"
    )
    assertEquals("queue", context.expectedNotes.first { it.key == "test-plan" }.role)
}
```

---

## 5. Mock-Order-Only Verification

Every collaborator mocked, and the only assertion is that calls happened in some order — nothing
is asserted about the unit's actual output or resulting state. This confirms the code path was
taken, not that it produced a correct result.

**Before:**

```kotlin
@Test
fun `advanceItem triggers cascade check before persisting transition`() {
    val cascadeDetector = mockk<CascadeDetector>(relaxed = true)
    val repository = mockk<WorkItemRepository>(relaxed = true)
    val handler = RoleTransitionHandler(repository, cascadeDetector)

    handler.advance(itemId, trigger = "start")

    verifyOrder {
        cascadeDetector.check(itemId)
        repository.updateRole(itemId, any())
    }
}
```

If `updateRole` were called with the wrong target role, or `check`'s result were ignored
entirely, this test would still pass — it never looks at what was actually passed or returned.

**After — order is verified where it matters, plus a real assertion on the resulting state:**

```kotlin
@Test
fun `advanceItem triggers cascade check before persisting transition`() {
    val cascadeDetector = mockk<CascadeDetector>()
    every { cascadeDetector.check(itemId) } returns CascadeResult.Clear
    val repository = mockk<WorkItemRepository>(relaxed = true)
    val handler = RoleTransitionHandler(repository, cascadeDetector)

    val result = handler.advance(itemId, trigger = "start")

    verifyOrder {
        cascadeDetector.check(itemId)
        repository.updateRole(itemId, Role.WORK)
    }
    assertEquals(Role.WORK, result.newRole)
    assertTrue(result.applied)
}
```

`repository.updateRole(itemId, Role.WORK)` asserts the *argument*, not just that the call
happened, and the return value is checked independently of the mock interactions.

---

## 6. Catch-Swallowed Assertions

An assertion placed inside a `try` block whose `catch` logs or silently ignores the exception
means an assertion failure (which JUnit5 raises as an `AssertionError`, a `Throwable`) and a
legitimately caught exception both read as "test passed" — because nothing outside the `try`
block observed the difference.

**Before:**

```kotlin
@Test
fun `manage_notes upsert accepts a note within maxLength`() {
    try {
        val result = manageNotesTool.execute(upsertRequest)
        assertEquals(NOTE_LIMITS_OK, result.warning)
        assertTrue(result.success)
    } catch (e: Exception) {
        // "some environments don't have the schema loaded yet"
        println("skipping: ${e.message}")
    }
}
```

If `manageNotesTool.execute` throws for any reason — including a real regression — the
`AssertionError` from a failed `assertEquals` is caught by the same `catch (e: Exception)` as a
genuine setup problem, logged, and the test reports green.

**After — no `catch` around the assertions; a genuine setup precondition is checked before the
`try`, not used to swallow failures after it:**

```kotlin
@Test
fun `manage_notes upsert accepts a note within maxLength`() {
    check(schemaService.isLoaded()) { "test setup invariant: schema must be loaded before this test" }

    val result = manageNotesTool.execute(upsertRequest)

    assertEquals(NOTE_LIMITS_OK, result.warning)
    assertTrue(result.success)
}
```

If the tool call itself is expected to throw under some condition, assert that directly with
`assertThrows<SpecificExceptionType> { ... }` — never a bare `catch` that treats any exception,
assertion failures included, as an acceptable outcome.
