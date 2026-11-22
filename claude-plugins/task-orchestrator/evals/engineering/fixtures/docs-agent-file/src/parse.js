export function parseRow(line) {
  const [date, account, amount, memo] = line.split(',');
  return { date, account, cents: Math.round(Number(amount) * 100), memo };
}
