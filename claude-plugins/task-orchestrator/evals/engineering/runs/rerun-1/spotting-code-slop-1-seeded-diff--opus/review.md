FINDING src/orders/retry.js:5 | The catch block throws away every error without logging or keeping it. When all attempts fail, the cause (auth error, bad address, carrier outage) is lost, and permanent errors are retried the same way as transient ones.
FINDING src/orders/retry.js:9 | When every attempt fails, withRetry returns null instead of rethrowing the last error. Callers can't tell this from a success, so a hard failure turns into a silent null.
FINDING src/orders/ship.js:10 | If booking fails every attempt, receipt is null and `receipt.trackingId` throws "Cannot read properties of null". Before this commit the caller got the carrier's real error; now it gets an unrelated TypeError, so this is a regression.
FINDING src/orders/ship.js:8 | carrier.book is retried with no idempotency key or check for an existing booking. If a call books the shipment but then fails (for example, the response times out), the next attempt books it again: duplicate shipments and charges.
FINDING src/orders/retry.js:2 | Retries run back to back with no delay or backoff, so a carrier that is rate-limiting or briefly down gets hit three times instantly and probably fails every time.
FINDING src/orders/shippingFacade.js:3 | This new "facade" just passes arguments through to shipOrder, and nothing imports it. It adds an unused layer of indirection with no purpose. Remove it.
FINDING src/orders/ship.js:6 | The added comments ("build the label", "return the result") only repeat what the code says. They add noise and should be removed.
FINDING src/orders/retry.js:1 | The commit adds retry behavior with no tests for success after retries, the all-attempts-fail path, or shipOrder when booking fails. A test of that last path would have caught the null-dereference above.
VERDICT: changes
