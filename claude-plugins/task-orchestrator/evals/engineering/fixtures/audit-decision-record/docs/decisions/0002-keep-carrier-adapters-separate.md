# 0002: Keep the carrier adapters separate

Status: accepted

The UPS and FedEx adapters look alike and share several lines. We keep them as two separate modules and do not
merge them into one parametrized adapter. Each carrier changes its API on its own schedule and a merged module
caused a double outage when both changed in one week; a separate module limits a carrier change to one file.
Accepted cost: duplicated lines between the two files.
