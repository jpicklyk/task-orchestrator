Placing an order now earns the customer one point per whole 100 cents of the order total, and `getPoints(customerId)` on the app returns their balance. The full suite passes (5 tests).

- **Points rule:** `src/domain/loyalty.js` has `pointsFor(totalCents)`, which rounds down. A 250-cent order earns 2 points, and anything under 100 cents earns none.
- **Storage:** `src/infra/memoryPointsStore.js` is a new in-memory store that keeps a running balance per customer. A customer with no orders has a balance of 0.
- **Awarding points:** `placeOrder` in `src/domain/placeOrder.js` now takes a `pointsStore` alongside `orderStore`. It adds the points right after saving the order.
- **Wiring:** `src/app.js` creates the points store and exposes `getPoints`.
- **Tests:** `test/loyalty.test.js` checks the rounding, a zero balance for an unknown customer, earning on an order, and balances adding up across orders and staying separate per customer.

I haven't committed anything.
