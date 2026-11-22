I added backorders, but I couldn't run the tests. The sandbox refused every `npm test` call, so none of the new code has been run.

- **`createBackorder(orderId, sku, qty)`** is in `src/backorders/backorders.js`. It returns an open backorder with an id like `bo_1` and stores it in memory, the same way shipments do. It throws a `RangeError` if `qty` isn't a positive integer.
- **`backordersForOrder(orderId)`** lists an order's backorders. I named it after `shipmentsForOrder`.
- **Tests** are in `test/backorders.test.js`. They cover creating a backorder, listing by order and rejecting a bad quantity.
- **`GLOSSARY.md`** now defines "Backorder", since it's a new project term.

I didn't connect backorders to picks. You asked for `createBackorder` and a listing function, so nothing yet creates a backorder automatically when a pick comes up short. Nothing handles shipping a backorder when stock arrives, either.

To run the tests, use `npm test` in the repo.
