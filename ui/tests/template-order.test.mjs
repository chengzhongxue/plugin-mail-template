import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

function setup() {
  const calls = [];
  const exports = {};
  const client = { configMap: {
    createConfigMap: async input => calls.push(input),
    updateConfigMap: async input => calls.push(input),
  } };
  const code = ts.transpileModule(readFileSync(new URL('../src/utils/template-order.ts', import.meta.url), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  vm.runInNewContext(code, { exports, require: () => ({ coreApiClient: client }) });
  return { api: exports, calls };
}
const approval = 'reason:mail-template-link-application-approved';
const rejection = 'reason:mail-template-link-application-rejected';
const application = 'reason:plugin-links-new-link-application';
const plain = value => JSON.parse(JSON.stringify(value));

test('default order groups application, approval and rejection without disturbing other templates', () => {
  const { api } = setup();
  assert.deepEqual(plain(api.defaultOrder([approval, rejection, 'other', application, 'last'])),
    ['other', application, approval, rejection, 'last']);
  assert.deepEqual(plain(api.defaultOrder([approval, 'other'])), ['other', approval]);
});

test('dragging creates a separate draft and supports moving in both directions', () => {
  const { api, calls } = setup();
  const saved = ['a', 'b', 'c'];
  const draft = api.moveTemplate(saved, 'a', 'c', true);
  assert.deepEqual(saved, ['a', 'b', 'c']);
  assert.deepEqual(plain(draft), ['b', 'c', 'a']);
  assert.deepEqual(plain(api.moveTemplate(draft, 'a', 'b', false)), saved);
  assert.equal(calls.length, 0);
});

test('saved preferences survive source removal and new templates are appended once', () => {
  const { api } = setup();
  assert.deepEqual(plain(api.mergeOrder(['custom:x', 'missing', application], [application, approval, rejection, 'new'])),
    ['custom:x', 'missing', application, approval, rejection, 'new']);
});

test('only explicit save writes order and preserves version for concurrent-edit protection', async () => {
  const { api, calls } = setup();
  await api.saveOrder(['b', 'a'], { order: ['a', 'b'], config: { apiVersion: 'v1alpha1', kind: 'ConfigMap', metadata: { name: 'mail-template-sidebar-order', version: 4 }, data: { extra: 'keep' } } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].configMap.metadata.version, 4);
  assert.equal(calls[0].configMap.data.extra, 'keep');
  assert.equal(calls[0].configMap.data.order, '["b","a"]');
});
