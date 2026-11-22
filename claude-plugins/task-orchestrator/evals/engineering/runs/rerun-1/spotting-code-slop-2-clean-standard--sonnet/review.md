FINDING src/invoices/markPaid.js:3 | paidAt is not validated; calling markInvoicePaid(id) or with null/'' stores a falsy paidAt, so the invoice still looks unpaid and can be marked paid again (the already-paid check at line 6 is a truthiness test). Throw if paidAt is missing.
FINDING test/markPaid.test.js:7 | Tests share the module-level in-memory store and rely on fixed ids (i1, i2) without resetting it; fine today but fragile as tests are added. No test covers a missing/invalid paidAt.
VERDICT: approve
