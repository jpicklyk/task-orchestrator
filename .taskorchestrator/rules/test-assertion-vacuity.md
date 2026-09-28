# test-assertion-vacuity

A clean-looking assertion can still pass whether or not the feature under test works, because the
failure is hidden in the fixture or the test harness rather than in the assertion's wording. This
rule is the check for that class of vacuous test, distinct from (and a mirror image of) the
forbidden-pattern list in `forbidden-test-patterns`.

Recorded instances that motivated this rule, all caught only in review after being authored
blind: a "no item was created" assertion following a request whose malformed body could never have
created an item regardless of the fix; a duplicate-header rejection case that never reached its
intended branch because the test harness silently merged the repeated header into one value before
the code under test ever saw it; a cycle-rejection test that asserted a zero-count outcome but
never checked that the rejection reason was actually "circular" rather than some unrelated error.

1. **Vacuity check.** For every negative or absence assertion (asserting that nothing happened,
   nothing was created, no error appeared), name the specific fixture condition that would make
   the assertion fail if the fix were absent. If no such condition exists -- the fixture could
   never have produced the asserted outcome regardless of whether the fix works -- the fixture is
   broken, not the assertion; fix the fixture before trusting the assertion.
2. **Harness-transformation check.** When a scenario depends on the exact raw shape of its input
   (repeated or multi-valued fields, casing, whitespace, encoding), verify that the test harness
   actually delivers that shape unmodified to the code under test, rather than normalizing,
   merging, or reformatting it first. A harness that quietly collapses the very input variation a
   test claims to exercise makes that test vacuous no matter how carefully the assertion reads.
3. **Rejection tests assert the reason, not just the outcome.** When a scenario expects a request
   or operation to be rejected, assert the specific rejection reason (an error code or message),
   not only that the expected side effect didn't happen. "Nothing was created" is equally
   consistent with the correct rejection and with an unrelated, wrong failure earlier in the path.
4. **Ask "can this assertion fail?" before trusting it.** For any assertion you are about to write
   or are reviewing, construct the mental counterexample: what change to the implementation, if
   introduced right now, would this assertion actually catch? If you cannot name one, the assertion
   is not adding coverage -- strengthen it, or mark the scenario as not covered with a stated
   reason, rather than leaving an assertion that would pass against almost anything.

## claude:

- This check runs alongside the forbidden-pattern declaration in `test-manifest`: for every
  negative/absence assertion, the manifest names the fixture condition that would falsify it (per
  point 1); an assertion with no such condition is a blocking finding under independent review,
  not a pass.
- Known concrete harness gap in this codebase: the Ktor `testApplication` harness merges repeated
  HTTP headers into one comma-joined value and sends no default `Host` header -- any scenario
  depending on raw multi-valued or missing-header behavior needs a harness that preserves that
  shape (a real engine over a raw socket, or an equivalent), not `testApplication` as-is.
