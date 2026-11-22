const shipments = new Map();

export function createShipment(orderId, trackingId) {
  const shipment = { id: `shp_${shipments.size + 1}`, orderId, trackingId, status: 'created' };
  shipments.set(shipment.id, shipment);
  return shipment;
}

export function shipmentsForOrder(orderId) {
  return [...shipments.values()].filter((s) => s.orderId === orderId);
}
