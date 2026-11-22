I added `refundOrder(orderId, amountCents)` in `src/refunds/refunds.js`, with tests in `test/refunds.test.js`. All 6 tests pass, including the existing order test.

It throws a `DomainError`, like the rest of the codebase, and logs `order.refunded` on success.

| Check | Error code |
|---|---|
| Amount isn't a positive integer | `REFUND_AMOUNT_INVALID` |
| Order doesn't exist | `ORDER_NOT_FOUND` (from `getOrder`) |
| Order isn't paid | `ORDER_NOT_PAID` |
| Amount is more than the paid amount minus earlier refunds | `REFUND_EXCEEDS_PAID` |

A valid refund is pushed onto `order.refunds` as `{ amountCents }`, and the function returns the order. The amount check mirrors the one in `capturePayment`, which you didn't ask for.

The tests cover each error case, recording a refund, and the cumulative limit. The cumulative test refunds 300 of 500, then confirms 201 is rejected and 200 succeeds.

I haven't committed anything.
