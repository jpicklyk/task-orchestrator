# Forbidden Test Patterns - Kotlin Examples for This Codebase

The pattern definitions are in the shipped `test-authoring` skill's `references/forbidden-patterns.md`
(sections 1-6 there carry the same numbers as below) and in the served rule `forbidden-test-patterns`.
This file holds only the Kotlin/JUnit 5/MockK before and after blocks for this codebase's test
conventions (`current/src/test/kotlin/...`). Every type, function and constant named is declared in
`current/src`; local helper names (`seedItem`, `advanceService`) stand for the test's own setup.
Use the declarations block for exact signatures.

---

## 1. Skip guard on a behavioural condition

Definition: shipped `references/forbidden-patterns.md` section 1.

**Before:**

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

**After:**

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

---

## 2. Disjunctive escape

Definition: shipped `references/forbidden-patterns.md` section 2.

**Before:**

```kotlin
@Test
fun `items_read search returns the matching item for a matching query`() {
    val hits = searchTitles(query = "authentication")   // titles from an items_read search call

    // Accepts either a real result set OR an empty one - passes either way.
    assertTrue(hits.isNotEmpty() || hits.isEmpty())
}
```

**After:**

```kotlin
@Test
fun `items_read search returns the matching item for a matching query`() {
    seedItem(title = "Implement authentication flow")
    seedItem(title = "Unrelated documentation update")

    val hits = searchTitles(query = "authentication")

    assertEquals(listOf("Implement authentication flow"), hits, "expected exactly the seeded matching item")
}
```

---

## 3. Oracle from the implementation

Definition: shipped `references/forbidden-patterns.md` section 3.

**Before:**

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

**After:**

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

**Second shape, before and after:**

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

---

## 4. Presence-only assertion

Definition: shipped `references/forbidden-patterns.md` section 4.

**Before:**

```kotlin
@Test
fun `items_read context returns the resolved schema for an item with traits`() {
    val context = readContext(itemWithTrait.id)   // the items_read context select, as JSON

    assertNotNull(context)
    assertTrue(context.getValue("schema").jsonArray.isNotEmpty())
}
```

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

---

## 5. Order-only verification of mocks

Definition: shipped `references/forbidden-patterns.md` section 5.

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

**After:**

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

---

## 6. Assertion swallowed by an error handler

Definition: shipped `references/forbidden-patterns.md` section 6.

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

**After:**

```kotlin
@Test
fun `advance start moves a queue item to work`() = runTest {
    check(item.role == Role.QUEUE) { "test setup invariant: the item must start in queue" }

    val outcome = advanceService.advance(item, trigger = "start", summary = null, actorClaim = null, verification = null)

    assertEquals(Role.WORK, (outcome as AdvanceOutcome.Success).result.newRole)
}
```
