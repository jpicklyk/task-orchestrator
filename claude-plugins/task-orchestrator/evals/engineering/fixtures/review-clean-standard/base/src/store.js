const tables = new Map();

export const store = {
  get(table, id) { return tables.get(table)?.get(id) ?? null; },
  put(table, row) {
    if (!tables.has(table)) tables.set(table, new Map());
    tables.get(table).set(row.id, row);
    return row;
  },
};
