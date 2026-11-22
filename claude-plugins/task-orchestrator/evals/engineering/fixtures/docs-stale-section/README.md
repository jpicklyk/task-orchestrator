# report-export

Turns report rows into files other tools read.

## Usage

```js
import { exportJson } from 'report-export';

const text = exportJson(rows, { pretty: true });
```

`exportJson` returns the serialised rows as a string. Pass `pretty: true` for indented output.

## Exporting to CSV

Call `exportCsv(rows, { delimiter })` to get CSV text.

The first line of the CSV output is the header, built from the keys of the first row object.
Values that contain the delimiter or a quote are wrapped in quotes.
The default delimiter is a comma; pass `delimiter: ';'` for locales that use a semicolon.
CSV output is always UTF-8 with a byte order mark so that spreadsheet programs open it correctly.

## Configuration

`pretty` is the only option. It defaults to `false`.
