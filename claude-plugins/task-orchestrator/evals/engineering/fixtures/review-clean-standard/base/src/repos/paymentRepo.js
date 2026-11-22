import { store } from '../store.js';

export const getPayment = (id) => store.get('payments', id);
export const putPayment = (payment) => store.put('payments', payment);
