# Order service guide

The service exposes 4 endpoints under `/orders`: create, read, cancel and delete. Clients call it with the helper in
`src/client.js`.

## Calling the service

Calls made with `call` time out after 30 seconds by default; pass a different timeout as the third argument.
Failed calls are retried with `retryWithBackoff`, which waits longer between each attempt.

## Deleting an order

Deleting an order archives it instead of removing it, because accounting reconciles every billed order at month end
and needs the record to still exist. Cancelling is different: it keeps the order visible to the customer.

## Files

- `src/server.js` - the HTTP server and its routes
- `src/client.js` - the client helper
- `src/orders.js` - the order store
