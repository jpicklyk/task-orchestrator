I added `trackPackage(trackingId)` and two tests, but I couldn't run them. The test command (`npm test`) needed approval that this session couldn't give, so nothing has been run.

- **Function:** `trackPackage` is in `src/shipments/shipments.js`. It finds the shipment with that tracking id and returns its status (for example `'created'`). It returns `null` if no shipment has that id.
- **Tests:** in `test/shipments.test.js`, one checks a known tracking id and one checks an unknown id.
- **Naming:** `GLOSSARY.md` says not to call a Shipment a "package". I kept `trackPackage` because you named it that. The comment and code use "Shipment" everywhere else. If you'd rather follow the glossary, `trackShipment` would be the matching name, and renaming is a one-line change.

You can check it with `npm test`.
