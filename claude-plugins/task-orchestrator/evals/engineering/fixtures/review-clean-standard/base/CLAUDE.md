# ledger

Invoices and payments. Plain Node ES modules, tests with `node --test`, no dependencies.

## Standards

- Every entity has a repository module in `src/repos/`. A repository is a thin pass-through to the store, one
  function per store operation. This is deliberate: tests replace the store through the repositories, and callers
  never import the store directly. Do not inline a repository into its callers.
- The store returns `null` for a missing row. Callers check for `null` explicitly and throw `Error` with a message
  that names the missing id.
