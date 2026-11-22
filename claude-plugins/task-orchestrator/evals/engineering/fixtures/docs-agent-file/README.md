# ledger-import

Reads bank statement exports, normalises them and writes ledger entries.

## Overview

The importer reads a statement file, parses each row into a transaction, groups transactions by account and writes
one ledger entry per transaction. Parsing lives in `src/parse.js`, grouping in `src/group.js` and the writer in
`src/write.js`.

## Development

Run `npm test` for the tests.
