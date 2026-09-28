# forbidden-test-patterns

These patterns produce a green test suite without verifying real behavior. Every instance found in
authored tests must be declared with a justification in the test record; an undeclared instance
found in review is a blocking finding regardless of intent. Each pattern below states the MUST
rule and a short before/after shape; full worked examples live in the test-authoring skill's
reference material where the executor has one.

## 1. Skip-guards on behavioral conditions

MUST NOT use an environment-skip mechanism (e.g. `assumeTrue`, `Assumptions`) to guard a
*behavioral* precondition -- one that describes the exact state under test rather than a platform
or infrastructure limitation the test structurally cannot run under. Skip mechanisms exist only for
genuine environment gates (OS-specific behavior, an unprovisioned external service).

- Before: `assumeTrue(item.claim != null)` guarding a claim-expiry test -- if the setup helper ever
  regresses and stops populating the claim, the test silently stops running instead of failing.
- After: assert the setup precondition directly (fail loudly if it's false), and reserve the real
  skip mechanism for a genuine platform guard, clearly separated from the behavioral test.

Rationale: three real production bugs in this codebase's history shipped exactly this way -- the
test that would have caught each one never ran red, because it never ran at all.

## 2. Disjunctive escapes

MUST NOT write an assertion with an `||` branch that accepts "the operation did nothing" as
equally valid to "the operation produced the intended result." Such an assertion is a tautology or
close to one -- it passes whether the feature works or is a no-op.

- Before: `assertTrue(results.isNotEmpty() || results.isEmpty())` -- true of every possible
  result.
- After: assert the specific expected outcome with no no-op escape hatch; if a genuine "produces
  nothing" case needs coverage, give it its own scenario with its own oracle, never folded into
  the positive case as an alternate acceptable outcome.

## 3. Oracle-from-implementation

MUST NOT derive an assertion's expected value from the implementation under test -- by
recomputing its own formula, or by running the code once and pasting whatever it returned. Either
way the test verifies "the code agrees with itself," which stays green even when the formula or
the pasted value is wrong.

- Before: a test recomputes the function's own scoring formula to produce its "expected" value.
- After: the oracle is the documented external definition (a spec, an algorithm's published
  formula, an independently run computation), computed by hand or by a second implementation, with
  the literal expected values written in and never derived by calling the function under test.

Tell: if you can write the assertion without ever opening the file under test, it's a real oracle;
if writing it requires reading the function's own source, it's this pattern.

## 4. Assert-not-null-only

MUST NOT let a not-null or non-empty check stand in for a check of the actual value, size, or
contents. It leaves a wrong-but-present result indistinguishable from a correct one.

- Before: `assertNotNull(result); assertTrue(result.items.isNotEmpty())`.
- After: assert the specific expected set/value -- exact keys, exact count, exact content -- not
  merely that something non-null or non-empty came back.

## 5. Mock-order-only verification

MUST NOT let a test's only assertion be that mocked calls happened in some order, with nothing
asserted about the unit's actual output or resulting state. This confirms a code path was taken,
not that it produced a correct result.

- Before: every collaborator mocked (relaxed), the only check is `verifyOrder { ... }`.
- After: verify order where it genuinely matters, plus a real assertion on the returned value or
  resulting state, and assert the *arguments* a mock was called with, not just that it was called.

## 6. Catch-swallowed assertions

MUST NOT place an assertion inside a `try` block whose `catch` logs or silently ignores the
exception -- an assertion failure and a legitimately caught exception then both read as "test
passed," because nothing outside the block distinguishes them.

- Before: assertions live inside `try { ... } catch (e: Exception) { println("skipping") }`.
- After: check any genuine setup precondition before the `try`/assertions, with no `catch` wrapped
  around the assertions themselves; if the code under test is expected to throw under some
  condition, assert that directly with a typed exception-expectation construct, never a bare catch
  that treats any exception -- assertion failures included -- as acceptable.

## claude:

Full before/after Kotlin/JUnit5 examples for all six patterns, drawn from this codebase's actual
history, are in `.claude/skills/test-author/references/forbidden-patterns.md`. Kotlin/JUnit5
specifics: pattern 1's genuine platform guard is `@EnabledOnOs`; pattern 6's typed
exception-expectation construct is `assertThrows<SpecificExceptionType> { ... }`.
