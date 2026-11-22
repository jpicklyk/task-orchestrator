import { store } from '../store.js';

export const getInvoice = (id) => store.get('invoices', id);
export const putInvoice = (invoice) => store.put('invoices', invoice);
