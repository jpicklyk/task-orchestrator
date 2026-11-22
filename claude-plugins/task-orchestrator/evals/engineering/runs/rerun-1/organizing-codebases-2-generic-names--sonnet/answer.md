I added `shippingCost(weightKg)` and its tests, but I haven't run them. Both attempts to run `npm test` were blocked for approval, so the tests are unverified.

- **Function:** `src/shipping/cost.js` returns 500 cents up to and including 1 kg, and 900 cents up to and including 5 kg. Above 5 kg it returns 900 plus 200 for each further whole or partial kg (6.5 kg gives 1300).
- **Invalid input:** I added one thing you didn't ask for. It throws a `RangeError` for negative, `NaN`, infinite or non-number weights.
- **Tests:** `test/cost.test.js` covers the boundaries at 1 kg and 5 kg, partial kilograms above 5 kg, and the invalid-input errors.

Please run `npm test` yourself, or approve the command and I'll run it.
