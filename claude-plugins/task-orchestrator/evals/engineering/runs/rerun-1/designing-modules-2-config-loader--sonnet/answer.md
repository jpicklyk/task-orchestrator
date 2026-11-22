The shell blocked that compound command, so I'm skipping the end-to-end run. I'll summarize what I did and didn't verify.

**Added**
- `src/config.js` has `loadConfig(file = 'config.json')`, `validateConfig`, `DEFAULTS` and `ConfigError`.
  - It reads `config.json` from the working directory and fills in any missing keys with the server's existing defaults: host `0.0.0.0`, port `8080`, logLevel `info`.
  - It checks that `port` is an integer from 1 to 65535, `host` is a non-empty string, and `logLevel` is one of debug, info, warn or error.
  - A bad file produces a `ConfigError` that names the file and the offending key. It covers malformed JSON, non-object JSON and unreadable files.
- `src/index.js` now calls `loadConfig()` and passes the result to `start()`. On a `ConfigError` it prints `Configuration error: …` to stderr and exits with code 1.
- `src/config.test.js` has 10 tests covering defaults, partial and full configs, each invalid field, malformed JSON, non-object JSON and the filename in error messages.

**Verification**
- `node --test` passes, 10 of 10.
- I did not run `index.js` against a bad `config.json`, because the shell refused the compound command. The exit-1 path is untested.

**Decision for you**
- A missing `config.json` falls back to defaults instead of erroring, since you said to default anything missing. If you'd rather a missing file be an error, it's a one-line change in `loadConfig`.

I haven't committed anything.
