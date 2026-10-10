import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/utils/sku.ts', import.meta.url), 'utf8');
const javascript = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText;
const { initialSku, matchSkuValue, isSkuAvailable, skuValues } = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString('base64')}`);
const properties = [{ propertyId: 'color' }, { propertyId: 'protocol' }];
const skus = [
  { skuId: '101', propertyValueIds: 'white-zigbee', stock: 5, price: 129 },
  { skuId: '102', propertyValueIds: 'black-wifi', stock: 8, price: 199 },
  { skuId: '103', propertyValueIds: 'white-wifi', stock: 2, price: 189 },
  { skuId: '104', propertyValueIds: 'silver-zigbee', stock: 0, price: 229 }
];

test('route SKU remains selected even when sold out instead of silently selling a different SKU', () => {
  assert.equal(initialSku(skus, '104').skuId, '104');
});
test('missing route SKU falls back to an available existing SKU', () => {
  assert.equal(initialSku(skus, 'unknown').skuId, '101');
});
test('sparse combination changes another dimension to reach an existing SKU', () => {
  const match = matchSkuValue(skus, properties, { color: 'white', protocol: 'zigbee' }, 'color', 'black');
  assert.equal(match.skuId, '102');
  assert.deepEqual(skuValues(match, properties), { color: 'black', protocol: 'wifi' });
});
test('changing a value preserves compatible other selections', () => {
  assert.equal(matchSkuValue(skus, properties, { color: 'white', protocol: 'zigbee' }, 'protocol', 'wifi').skuId, '103');
});
test('no purchasable SKU means unavailable value, never a fabricated combination', () => {
  assert.equal(matchSkuValue(skus, properties, { color: 'black', protocol: 'wifi' }, 'color', 'silver'), undefined);
  assert.equal(matchSkuValue(skus, properties, {}, 'color', 'red'), undefined);
});
test('unknown price and invalid stock cannot be purchased', () => {
  for (const invalid of [{ price: null, stock: 5 }, { stock: 5 }, { price: '', stock: 5 }, { price: 0, stock: 5 }, { price: -1, stock: 5 }, { price: 199, stock: -1 }, { price: 199, stock: NaN }, { price: 199, stock: null }]) assert.equal(isSkuAvailable(invalid), false);
  assert.equal(isSkuAvailable({ price: 199, stock: 1 }), true);
});
test('explicit attributes override transport combination strings', () => {
  assert.deepEqual(skuValues({ attributes: { color: 'black', protocol: 'wifi' }, propertyValueIds: 'white-zigbee' }, properties), { color: 'black', protocol: 'wifi' });
});
