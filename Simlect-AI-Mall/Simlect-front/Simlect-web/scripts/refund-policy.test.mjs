import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const source = await readFile(new URL('../src/utils/refundPolicy.ts', import.meta.url), 'utf8');
const javascript = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText;
const { shippingRefundPolicyLabel } = await import(`data:text/javascript;base64,${Buffer.from(javascript).toString('base64')}`);

test('server refund policy labels include historical original-price refunds', () => {
  assert.equal(shippingRefundPolicyLabel('UNSHIPPED_ONLY'), '未发货退运费，已发货不退');
  assert.equal(shippingRefundPolicyLabel('PROPORTIONAL'), '按退货件数退分摊运费');
  assert.equal(shippingRefundPolicyLabel('LEGACY_ORIGINAL_PRICE'), '按历史商品原价退款');
});

test('missing and unknown server policy never imply a refund rule', () => {
  for (const policy of [undefined, null, '', 'FUTURE_POLICY', 0, {}]) {
    assert.equal(shippingRefundPolicyLabel(policy), '未提供政策');
  }
});
