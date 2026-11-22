import { test } from 'node:test';
import assert from 'node:assert/strict';
import { putInvoice } from '../src/repos/invoiceRepo.js';
import { markInvoicePaid } from '../src/invoices/markPaid.js';

test('markInvoicePaid stamps the payment time', () => {
  putInvoice({ id: 'i1', totalCents: 500 });
  assert.equal(markInvoicePaid('i1', '2026-01-02').paidAt, '2026-01-02');
});

test('markInvoicePaid refuses an unknown invoice', () => {
  assert.throws(() => markInvoicePaid('missing', '2026-01-02'), /invoice missing not found/);
});

test('markInvoicePaid refuses a second payment', () => {
  putInvoice({ id: 'i2', totalCents: 500, paidAt: '2026-01-01' });
  assert.throws(() => markInvoicePaid('i2', '2026-01-02'), /already paid/);
});
