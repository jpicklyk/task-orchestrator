import { getInvoice, putInvoice } from '../repos/invoiceRepo.js';

export function markInvoicePaid(invoiceId, paidAt) {
  const invoice = getInvoice(invoiceId);
  if (invoice === null) throw new Error(`invoice ${invoiceId} not found`);
  if (invoice.paidAt) throw new Error(`invoice ${invoiceId} is already paid`);
  return putInvoice({ ...invoice, paidAt });
}
