# cart

Shopping cart pricing. Plain Node ES modules, tests with `node --test`, no dependencies.

Cart lines are validated once at the API boundary (`src/api/validate.js`); code behind it receives well-formed lines.
