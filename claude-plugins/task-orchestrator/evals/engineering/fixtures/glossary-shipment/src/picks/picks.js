export function createPick(lineSku, qty) {
  return { lineSku, qty, done: false };
}

export function completePick(pick) {
  return { ...pick, done: true };
}
