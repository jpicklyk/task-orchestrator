# Adversarial probes

Beyond scenario-derived tests, probe the surface under test with every probe that applies.

- **Boundaries**: values at, just below and just above every stated limit, including suffix and
  off-by-one values.
- **Alternate spellings of one logical input**, paths and identifiers included: different
  separators, casing and encodings. Tokens worth trying: a backslash versus a forward slash, a percent-encoded dot-dot with an encoded
  slash, and a Windows extended-length path prefix.
- **Case sensitivity mismatches** between layers that each normalise differently.
- **Empty, absent and null** as three distinct states, each tested.
- **Duplicates and order**: repeated entries, and the same entries in a different order.
- **Replay**: the same write or transition applied twice.
- **Platform-bound outcomes**: path separators, trailing separators, drive letters, case
  sensitivity of the file system, line endings, clock resolution. Write the expectation
  platform-neutrally (build paths with the platform's own joining facility, or assert both outcomes
  keyed by platform), or mark the probe as platform-bound and name the environment on which it must
  be proven.

## Accounting

List every probe attempted, including those that found nothing. Mark each inapplicable probe as
inapplicable, with the reason, rather than omitting it. List the platform-bound probes together,
or state that there are none.
