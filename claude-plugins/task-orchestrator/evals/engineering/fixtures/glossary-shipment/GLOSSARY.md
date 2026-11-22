# Glossary

## Shipment

One physical delivery leaving the warehouse, with one tracking id. An Order can be split across several Shipments.

_Don't call it_: parcel, package, consignment.
_In code_: `Shipment`, `shipments/`.

## Pick

The act of collecting one line's items from warehouse shelves for a Shipment.

_In code_: `Pick`, `picks/`.
