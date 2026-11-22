FINDING src/invoices/markPaid.js:3 | Minor, non-blocking: `paidAt` is never checked. If a caller leaves it out or passes a falsy value, the function stores `paidAt: undefined`, reports success, and leaves the invoice looking unpaid, so the "already paid" check on line 6 would let it be paid again. Consider rejecting a missing `paidAt` (and adding a test for it).
VERDICT: approve
