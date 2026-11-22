# 0001: Store money as integer cents

Status: accepted

## Context

Floating point sums of prices drift by fractions of a cent.

## Decision

Prices are stored and summed as integer cents. Basket totals are summed in integer cents and converted for display
only.

## Consequences

Display code formats cents; no arithmetic runs on formatted values.
