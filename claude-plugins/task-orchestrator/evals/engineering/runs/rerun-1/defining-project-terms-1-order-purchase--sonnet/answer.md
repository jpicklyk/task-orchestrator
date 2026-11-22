I added `orderHistory(customerId)` and `orderTotal(order)` in `src/orders/orders.js`, plus tests, but I haven't run the tests. The Bash tool asked for approval to run `npm test` and this session is non-interactive, so you'll need to run it yourself.

- **Naming:** `GLOSSARY.md` says not to call these "purchases", so I used "order" in the code. It's built on the existing `ordersForCustomer`.
- **History:** `orderHistory` returns `{ id, date, total }` for each of the customer's orders, newest first. If two orders have the same timestamp, the one created later comes first.
- **Assumption:** Order lines had no defined shape, so `orderTotal` assumes each line is `{ unitPrice, quantity }`. If your checkout uses different field names, `orderTotal` is the one place to change.
- **Tests:** The new tests in `test/orders.test.js` cover totals, the newest-first ordering, filtering to one customer, and a customer with no orders.

I haven't committed anything.
