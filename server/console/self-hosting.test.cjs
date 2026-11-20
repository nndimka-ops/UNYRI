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

function fixture(language, role = 'owner', moderation = null) {
  const elements = new Map();
  const calls = [], copied = [], shared = [];
  const document = {
    getElementById(id) { if (!elements.has(id)) elements.set(id, new Element('div')); return elements.get(id); },
    createElement: (tag) => new Element(tag), createElementNS: (_, tag) => new Element(tag),
    addEventListener() {}, body: new Element('body'),
  };
  const context = vm.createContext({ document, navigator: { language, clipboard: { writeText: async (text) => copied.push(text) }, share: async (data) => shared.push(data) },
    window: { addEventListener() {}, matchMedia: () => ({ matches: false, addEventListener() {} }) },
    location: { origin: 'https://fixture.example.test' }, setTimeout: () => 1, clearTimeout() {}, URL, URLSearchParams, AbortController, console,
  });
  // Keep definitions and listeners; suppress only the networked boot sequence.
  vm.runInContext(source.replace(/\bboot\(\);\s*$/, ''), context);
  const api = async (url, options = {}) => {
    calls.push({ url, ...options });
    if (moderation && url === '/admin/users/fixture-user') return moderation;
    if (moderation && (url.includes('/timeline') || url.includes('/notes') || url.startsWith('/admin/reports'))) return { items: [], next_before: null };
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
  context.fixtureModeration = moderation;
  vm.runInContext('api = fixtureApi; state.role = fixtureRole; state.params = {userId: "fixture-user"}; globalThis.root = fixtureModeration ? SECTIONS.moderation() : SECTIONS.selfhosting();', context);
  return { context, root: context.root, calls, copied, shared, elements };
}

test('all SH-2 strings exist in English, Russian and Ukrainian', () => {
  const { context } = fixture('en');
  const sets = JSON.parse(vm.runInContext('JSON.stringify(Object.fromEntries(Object.entries(SH2_TEXT).map(([locale, strings]) => [locale, Object.keys(strings).sort()])))', context));
  assert.deepEqual(sets.en, sets.ru); assert.deepEqual(sets.en, sets.uk);
});

test('B-033 public captions ignore legacy private identity fields', () => {
  const { context } = fixture('en');
  context.legacyPrivate = { username: 'private_login_fixture', reason: 'private_login_fixture' };
  assert.equal(vm.runInContext('publicIdentity({...legacyPrivate, public_username:"alice", unyri_number:"123456789"})', context), '@alice');
  assert.equal(vm.runInContext('publicIdentity({...legacyPrivate, unyri_number:"123456789"})', context), '123\u202f456\u202f789');
  assert.equal(vm.runInContext('publicIdentity({...legacyPrivate, user_id:"account-uuid"})', context), 'account-uuid');
  assert.equal(vm.runInContext('publicIdentity(legacyPrivate)', context), 'Unknown user');
  assert.equal(vm.runInContext('sh2("publicIdentitySearch")', fixture('ru').context), 'Номер, публичное @имя, отображаемое имя или UUID');
  assert.equal(vm.runInContext('sh2("publicIdentitySearch")', fixture('uk').context), 'Номер, публічне @ім’я, відображуване ім’я або UUID');
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

const moderationDetail = (overrides = {}) => ({
  profile: { user_id: 'fixture-user', unyri_number: '123456789', display_name: '<script>fixture</script>' },
  moderation: { status: 'active', reports_total: 0, reports_open: 0 },
  suspend_allowed: true, moderation_allowed: true, is_administrator: false, devices: [], ...overrides,
});

test('permanent-delete confirmation uses the exact immutable number and a required reason', () => {
  const f = fixture('en');
  const evaluate = (expression) => vm.runInContext(expression, f.context);
  assert.equal(evaluate('accountDeletionIdentity({unyri_number:"123456789", public_username:"alice", username:"private", user_id:"uuid"})'), '123456789');
  assert.equal(evaluate('accountDeletionIdentity({user_id:"legacy-uuid"})'), 'legacy-uuid');
  for (const [confirmation, reason] of [['@alice', 'reason'], ['123456788', 'reason'], ['123456789', ' '], ['123456789', 'x'.repeat(2001)]]) {
    f.context.confirmation = confirmation; f.context.reason = reason;
    assert.throws(() => evaluate('accountDeletionPayload({unyri_number:"123456789"}, confirmation, reason)'), /exact public number/);
  }
  assert.deepEqual(JSON.parse(evaluate('JSON.stringify(accountDeletionPayload({unyri_number:"123456789"}, "123456789", "  abuse  "))')),
    { confirm_identity: '123456789', reason: 'abuse' });
});

for (const language of ['en', 'ru', 'uk']) test(`owner permanent-delete dialog requires number and reason (${language})`, async () => {
  const f = fixture(language, 'owner', moderationDetail()); await settle();
  const label = vm.runInContext('sh2("deleteAccount")', f.context);
  const button = all(f.root).find((node) => node.tag === 'button' && node.textContent === label);
  assert.ok(button); button.events.click(); await settle();
  const confirm = f.elements.get('modal-confirm');
  assert.equal(confirm.disabled, true);
  const fields = all(f.elements.get('modal-body'));
  const number = fields.find((node) => node.id === 'delete-account-number');
  const reason = fields.find((node) => node.id === 'delete-account-reason');
  number.value = '123456789'; number.events.input(); assert.equal(confirm.disabled, true);
  reason.value = 'Account abuse'; reason.events.input(); assert.equal(confirm.disabled, false);
  // Avoid loading another section after the completed action in this fixture.
  vm.runInContext('go = () => {}; closeModal(true)', f.context);
  await settle(); await settle();
  const requests = f.calls.filter((call) => call.url.endsWith('/delete'));
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/admin/users/fixture-user/delete');
  assert.equal(requests[0].method, 'POST');
  assert.deepEqual(JSON.parse(JSON.stringify(requests[0].body)), { confirm_identity: '123456789', reason: 'Account abuse' });
});

test('moderators, administrators and protected targets have no permanent-delete action', async () => {
  for (const [role, detail] of [['moderator', moderationDetail()], ['admin', moderationDetail()],
    ['owner', moderationDetail({ is_administrator: true })], ['owner', moderationDetail({ moderation_allowed: false })]]) {
    const f = fixture('en', role, detail); await settle();
    assert.ok(!all(f.root).some((node) => node.tag === 'button' && node.textContent === 'Delete account permanently'));
    assert.ok(!f.calls.some((call) => call.url.endsWith('/delete')));
  }
});

test('cancelling permanent deletion never submits a request', async () => {
  const f = fixture('en', 'owner', moderationDetail()); await settle();
  all(f.root).find((node) => node.textContent === 'Delete account permanently').events.click();
  vm.runInContext('closeModal(false)', f.context); await settle();
  assert.ok(!f.calls.some((call) => call.url.endsWith('/delete')));
});
