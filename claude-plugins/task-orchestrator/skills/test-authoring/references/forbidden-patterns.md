# Forbidden patterns

Each pattern below produces a passing result without verifying behaviour. Each is forbidden
without a stated justification. The examples use language-neutral pseudo-code.

## 1. Skip guard on a behavioural condition

Never use an environment-skip mechanism to guard a precondition that describes the exact state
under test. Skip mechanisms are for genuine environment limits only: an operating-system-specific
behaviour, or an external service that is not provisioned.

    before:  skip_unless(order.expires_at is not empty)
             check order expiry
    after:   require(order.expires_at is not empty)   # fails loudly if setup regressed
             check order expiry

If the setup helper regresses and stops producing an expiry, the first form silently stops running
the test. Keep a real skip for a real platform guard, clearly apart from the behavioural test.

## 2. Disjunctive escape

Never write an assertion with an alternative branch that accepts "did nothing" as equal to "did
the right thing".

    before:  require(results is not empty OR results is empty)     # true of everything
    after:   require(results == [a, b])

If "produces nothing" needs coverage, give it its own scenario and its own oracle.

## 3. Oracle from the implementation

Never derive an expected value by recomputing the code's own formula, or by running the code once
and pasting what it returned.

    before:  expected = score_formula(inputs)          # the function's own formula
             require(score(inputs) == expected)
    after:   require(score(inputs) == 0.75)            # computed by hand from the published rule

Tells, each a sign and not a proof: a real oracle can be written without opening the code under
test, and a tautological assertion cannot fail whatever the code does, so it is caught by asking
what code change would turn it red.

## 4. Presence-only assertion

Never let a not-null or non-empty check stand in for a check of the value.

    before:  require(result is not null); require(result.items is not empty)
    after:   require(result.items == ["a", "b"])

## 5. Order-only verification of mocks

Never let the only assertion be that mocked calls happened in some order.

    before:  verify_order(store.read, store.write)
    after:   verify_order(store.read, store.write)
             require(returned == expected)
             require(store.write was called with expected_argument)

## 6. Assertion swallowed by an error handler

Never place an assertion inside a block whose handler logs or ignores the failure.

    before:  try { require(total == 3) } catch (any error) { log("skipping") }
    after:   require(precondition)                       # outside any handler
             require(total == 3)                         # nothing wraps it
             expect_error(SpecificError) { call() }      # when an error is the expected outcome

A bare handler treats an assertion failure and a legitimate error alike as acceptable.

## Vacuous negative assertions

- **Name the fixture condition that would fail the assertion.** For every assertion that nothing
  happened, nothing was created or no error appeared, name the fixture condition that would make
  it fail if the fix were absent. If none exists, the fixture could never have produced the
  outcome: fix the fixture, not the assertion.
- **Check what the harness delivers.** When a scenario depends on the exact raw shape of its input
  (repeated fields, casing, whitespace, encoding), prove the test harness delivers that shape
  unmodified. A harness that merges, normalises or reformats the variation makes the test vacuous.
- **A rejection test asserts the reason.** Assert the specific error code or message, not only that
  the side effect did not happen. "Nothing was created" fits a correct rejection and an unrelated
  earlier failure equally well.
- **Ask whether it can fail.** For each assertion, name a change to the code that it would catch.
  If none comes to mind, strengthen it or mark the scenario as not covered, with the reason.

## Fixtures satisfy invariants by construction

Before writing a fixture, satisfy the validation rules of the type it builds by construction:
derive dependent fields from each other rather than setting them independently (for example,
compute a start time as the expiry time minus the duration instead of choosing both freely). If a
scenario cannot be built without violating an invariant, raise it. Never weaken the fixture or
bypass validation through a backdoor.
