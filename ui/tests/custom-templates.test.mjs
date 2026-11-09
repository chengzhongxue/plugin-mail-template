import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import vm from 'node:vm';
import ts from 'typescript';

function setup(existing = []) {
  const calls = [];
  const client = {
    notification: { notificationTemplate: {
      listNotificationTemplate: async () => ({ data: { items: existing } }),
      createNotificationTemplate: async input => calls.push(['activate-create', input]),
      updateNotificationTemplate: async input => calls.push(['activate-update', input]),
    } },
    configMap: {
      createConfigMap: async ({ configMap }) => { calls.push(['draft', configMap]); return { data: configMap }; },
      getConfigMap: async () => ({ data: { metadata: { name: 'draft', version: 8 }, data: { reasonType: 'comment', displayName: 'My style', rawBody: 'plain' } } }),
      updateConfigMap: async input => { calls.push(['save', input]); return { data: input.configMap }; },
    },
  };
  const exports = {};
  const code = ts.transpileModule(readFileSync(new URL('../src/utils/custom-templates.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
  vm.runInNewContext(code, { exports, require: () => ({ coreApiClient: client }), crypto: { randomUUID } });
  return { api: exports, calls };
}

const base = { metadata: { name: 'base', version: 3 }, spec: { reasonSelector: { language: 'default' }, template: { title: 'Default', htmlBody: '<p>Default</p>' } } };
const active = { metadata: { name: 'template-one-comment', version: 7 }, spec: { template: { title: 'Active', htmlBody: '<p>Active</p>' } } };

test('new draft copies the active style without activating a notification', async () => {
  const { api, calls } = setup([base, active]);
  const draft = await api.createCustomTemplate(' My style ', 'comment');
  assert.equal(draft.data.title, 'Active');
  assert.equal(draft.data.displayName, 'My style');
  assert.equal(draft.kind, 'ConfigMap');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'draft');
});

test('standalone creation accepts arbitrary JSON and never activates an existing reason', async () => {
  const { api, calls } = setup();
  const draft = await api.createStandaloneTemplate(' Order mail ');
  assert.equal(draft.data.mode, 'standalone');
  assert.equal(draft.data.reasonType, undefined);
  assert.equal(draft.data.enabled, 'false');
  assert.equal(JSON.parse(draft.data.sampleParameters).order.number, '20261109001');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'draft');
});

test('standalone save preserves version and parameter documentation', async () => {
  const { api, calls } = setup();
  const draft = await api.saveStandaloneTemplate({ metadata: { name: 'draft' } }, {
    title: 'Order', parameterDescription: 'order.id: Order ID', sampleParameters: '{"order":{"id":1}}', enabled: 'true',
  });
  assert.equal(draft.metadata.version, 8);
  assert.equal(draft.data.parameterDescription, 'order.id: Order ID');
  assert.equal(draft.data.mode, 'standalone');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'save');
});

test('saving a draft preserves its source and uses the latest resource version', async () => {
  const { api, calls } = setup();
  const saved = await api.saveCustomTemplate({ metadata: { name: 'draft' } }, 'New subject', '<p>New</p>');
  assert.equal(saved.metadata.version, 8);
  assert.equal(saved.data.reasonType, 'comment');
  assert.equal(saved.data.rawBody, 'plain');
  assert.equal(saved.data.htmlBody, '<p>New</p>');
  assert.equal(calls.length, 1);
  assert.equal(calls[0][0], 'save');
});

test('applying creates clean activation metadata or updates the existing template', async () => {
  for (const existing of [[], [active]]) {
    const { api, calls } = setup(existing);
    await api.applyCustomTemplate({ metadata: { name: 'draft', version: 99 }, data: { reasonType: 'comment', title: 'Custom', htmlBody: '<p>Custom</p>', rawBody: '' } });
    const [operation, request] = calls[0];
    assert.equal(operation, existing.length ? 'activate-update' : 'activate-create');
    assert.equal(request.notificationTemplate.metadata.name, 'template-one-comment');
    assert.equal(request.notificationTemplate.metadata.version, existing.length ? 7 : undefined);
    assert.equal(request.notificationTemplate.spec.reasonSelector.reasonType, 'comment');
    assert.equal(request.notificationTemplate.spec.template.title, 'Custom');
  }
});
