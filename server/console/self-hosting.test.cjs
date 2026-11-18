// Offline interaction tests for the embedded SH-2 console. No packages/network.
// Run: node --test server/console/self-hosting.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.events = {}; this.dataset = {}; this.classList = { toggle() {}, add() {}, remove() {} }; }
  setAttribute(key, value) { this[key] = value; }
  getAttribute(key) { return this[key]; }
  addEventListener(key, fn) { this.events[key] = fn; }
  append(...children) { this.children.push(...children); }
  get firstChild() { return this.children[0]; }
  removeChild(child) { this.children.splice(this.children.indexOf(child), 1); }
  focus() {}
}
const all = (node) => [node, ...(node.children || []).flatMap((child) => typeof child === 'object' ? all(child) : [])];
const settle = () => new Promise((resolve) => setImmediate(resolve));

function fixture(language, role = 'owner') {
  const elements = new Map();
  const calls = [], copied = [], shared = [];
  const document = {
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element('div')); return elements.get(id); },
    createElement: (tag) => new Element(tag), createElementNS: (_, tag) => new Element(tag),
    addEventListener() {}, body: new Element('body'),
  };
  const context = vm.createContext({ document, navigator: { language, clipboard: { writeText: async (text) => copied.push(text) }, share: async (data) => shared.push(data) },
    window: { addEventListener() {}, matchMedia: () => ({ matches: false, addEventListener() {} }) },
    location: { origin: 'https://fixture.example.test' }, setTimeout: () => 1, clearTimeout() {}, URL, console,
  });
  // Keep definitions and listeners; suppress only the networked boot sequence.
  vm.runInContext(source.replace(/\bboot\(\);\s*$/, ''), context);
  const api = async (url, options = {}) => {
    calls.push({ url, ...options });
    if (url === '/admin/registration') return { registration_mode: 'open', registration_open: true };
    if (url === '/admin/invites' && options.method === 'POST') return { code: 'fixture-id.ABCD-EFGH-JKLM-NPQR' };
    if (url.startsWith('/admin/invites') && options.method !== 'POST') return { items: [{ id: 'fixture-id', creator: '<img onerror=bad>', created_by: 'creator-id', status: 'active', uses: 0, max_uses: 1, expires_at: '2026-12-01T00:00:00Z' }], next_before: null };
    if (url === '/admin/self-check') return {
      setup: { available: false }, public_url: { url: 'https://fixture.example.test' }, turn: { sane: false }, rooms: { status: 'not_configured' },
      push: { status: 'offline', active_providers: [] }, storage: { reachable: false, client_uploads_ready: false },
      disk: { available_bytes: null, low_space: null }, server: { version: '0.1.0', migration_level: 83 },
    };
    return { ok: true };
  };
  context.fixtureApi = api;
  context.fixtureRole = role;
  vm.runInContext('api = fixtureApi; state.role = fixtureRole; globalThis.root = SECTIONS.selfhosting();', context);
  return { context, root: context.root, calls, copied, shared };
}

test('all SH-2 strings exist in English, Russian and Ukrainian', () => {
  const { context } = fixture('en');
  const sets = JSON.parse(vm.runInContext('JSON.stringify(Object.fromEntries(Object.entries(SH2_TEXT).map(([locale, strings]) => [locale, Object.keys(strings).sort()])))', context));
  assert.deepEqual(sets.en, sets.ru); assert.deepEqual(sets.en, sets.uk);
});

for (const language of ['en', 'ru', 'uk']) test(`owner controls, copy/share and literal server text (${language})`, async () => {
  const f = fixture(language); await settle();
  const label = (key) => vm.runInContext(`sh2('${key}')`, f.context);
  const click = async (key) => {
    const button = all(f.root).find((node) => node.tag === 'button' && node.textContent === label(key));
    assert.ok(button, `missing ${key}`); button.events.click(); await settle(); await settle();
  };
  const select = all(f.root).find((node) => node.tag === 'select');
  select.value = 'invite_only'; await click('save');
  assert.ok(f.calls.some((call) => call.url === '/admin/registration' && call.body?.registration_mode === 'invite_only'));
  await click('create');
  const creation = f.calls.find((call) => call.url === '/admin/invites' && call.method === 'POST');
  assert.equal(creation.body.max_uses, 1); assert.equal(creation.body.expires_in_seconds, 604800);
  await click('copy'); await click('copyLink'); await click('share');
  assert.equal(f.copied[0], 'fixture-id.ABCD-EFGH-JKLM-NPQR');
  const link = new URL(f.copied[1]);
  assert.equal(link.search, ''); assert.ok(link.hash.includes('invite_code='));
  assert.equal(new URLSearchParams(link.hash.slice(1)).get('server'), 'https://fixture.example.test');
  assert.equal(f.shared[0].url, f.copied[1]);
  await click('revoke'); assert.ok(f.calls.some((call) => call.url === '/admin/invites/fixture-id/revoke'));
  assert.ok(all(f.root).some((node) => node.textContent?.includes('<img onerror=bad>')));
  assert.ok(!all(f.root).some((node) => node.tag === 'img'));
  assert.ok(all(f.root).some((node) => node.textContent === label('retired')));
  assert.ok(all(f.root).some((node) => node.textContent === label('unknown')));
});

test('moderator can manage invites without loading owner diagnostics or policy', async () => {
  const f = fixture('en', 'moderator'); await settle();
  assert.deepEqual(f.calls.map((call) => call.url), ['/admin/invites']);
  assert.ok(!all(f.root).some((node) => node.tag === 'select'));
});
