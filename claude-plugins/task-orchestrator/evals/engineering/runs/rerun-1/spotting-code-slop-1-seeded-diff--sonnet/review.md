FINDING src/orders/retry.js:9 | After all attempts fail, withRetry returns null instead of throwing, so the carrier error is lost and callers get no failure signal.
FINDING src/orders/ship.js:10 | `receipt` can now be null (from withRetry), so `receipt.trackingId` throws an unrelated TypeError ("cannot read properties of null") whenever the carrier is down. Before this commit the real carrier error propagated. Fix by rethrowing the last error in withRetry.
FINDING src/orders/retry.js:5 | The catch block swallows every error, including non-transient ones such as validation errors or bad addresses. It also has no backoff or delay, so the carrier gets three immediate calls. Retry only errors known to be transient, and log or keep the last error.
FINDING src/orders/ship.js:8 | Retrying carrier.book is not safe unless booking is idempotent. If a booking succeeds but the response is lost, the retry creates a duplicate booking or charge. Use an idempotency key, or confirm the carrier supports idempotent booking.
FINDING src/orders/labels.js:2 | Variable renames unrelated to retry are mixed into this commit. They add diff noise and have nothing to do with the commit message. Split them into a separate commit.
FINDING src/orders/shippingFacade.js:1 | New `ship` wrapper only forwards to shipOrder, adds no behavior, and is unused. It is unrelated to the commit's purpose, so remove it.
FINDING src/orders/ship.js:5 | The added comments ("build the label", "return the result") only restate the code and add noise.
FINDING src/orders/retry.js:1 | No tests were added for the new retry behavior (success after failure, exhaustion, attempt count), and `node --test` coverage is the project's convention.
VERDICT: changes
