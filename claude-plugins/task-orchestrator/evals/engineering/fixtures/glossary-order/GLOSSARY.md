# Glossary

## Order

A customer's confirmed request to buy one or more items, created at checkout and kept for the life of the account.

_Distinct from_: Cart (not yet confirmed).
_Don't call it_: purchase, transaction, sale.
_In code_: `Order`, `orders/`.

## Cart

The items a customer has gathered before checkout. Becomes an Order at checkout and is then discarded.

_In code_: `Cart`, `carts/`.
