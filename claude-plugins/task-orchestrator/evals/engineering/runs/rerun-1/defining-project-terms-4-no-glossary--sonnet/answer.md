I added `orderHistory(customerId)` to `src/orders/orders.js` and three tests to `test/orders.test.js`. I couldn't run the tests: the shell refused `npm test` for needing approval, so none of it has been run. Please run `npm test` yourself.

- **What it returns:** `orderHistory` gives the customer's orders newest first, each as `{ id, date, total }`. `date` is the order's `createdAt`. Orders created in the same millisecond come back with the later-created one first.
- **Total:** a new `orderTotal(order)` helper computes it. The codebase never defined the shape of an order's `lines`, so I assumed each line is `{ unitPrice, quantity }`, with `quantity` defaulting to 1. If your lines use different field names, `orderTotal` is the one place to change.
- **Tests:** they cover `orderTotal`, newest-first ordering with the right totals and only that customer's orders, and an empty list for an unknown customer.
