type Property = { propertyId: string };
type Sku = Record<string, any>;

export function skuValues(sku: Sku, properties: Property[]): Record<string, string> {
  const ids = String(sku.propertyValueIds || '').split('-');
  return Object.fromEntries(properties.map((property, index) => [
    property.propertyId, String(sku.attributes?.[property.propertyId] ?? ids[index] ?? '')
  ]));
}

export function isSkuAvailable(sku: Sku | undefined): boolean {
  if (!sku) return false;
  if (sku.price == null || sku.price === '' || sku.stock == null || sku.stock === '') return false;
  const stock = Number(sku.stock);
  const price = Number(sku.price);
  return Number.isFinite(stock) && stock > 0 && Number.isFinite(price) && price > 0;
}

export function initialSku(skus: Sku[], requestedId: string): Sku | undefined {
  return skus.find(sku => String(sku.skuId) === requestedId)
    ?? skus.find(isSkuAvailable) ?? skus[0];
}

// Sparse combinations keep as many other selections as possible, then choose
// an existing, available SKU containing the requested value.
export function matchSkuValue(skus: Sku[], properties: Property[], selected: Record<string, string>, propertyId: string, valueId: string): Sku | undefined {
  return skus.filter(isSkuAvailable)
    .filter(sku => skuValues(sku, properties)[propertyId] === valueId)
    .map(sku => ({ sku, score: properties.reduce((sum, property) => sum + Number(
      property.propertyId !== propertyId && skuValues(sku, properties)[property.propertyId] === selected[property.propertyId]
    ), 0) }))
    .sort((a, b) => b.score - a.score)[0]?.sku;
}
