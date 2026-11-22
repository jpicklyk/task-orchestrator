RANK 1 | src/checkout.js | Act now | It is the most-changed file (touched in nearly every commit), has no tests, and builds a "purchase" where GLOSSARY.md says Order, with an id from Date.now() that can collide.
RANK 2 | src/purchaseLog.js | Plan it | It keeps an in-memory, non-persistent list that loses all data on restart, is also named "purchase" instead of Order, and changes in step with checkout.js.
RANK 3 | src/carts.js | Watch | It is small and rarely changed, with only a minor risk because addLine does no validation of qty or price.
