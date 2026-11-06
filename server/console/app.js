/* UNYRI administration console.
 *
 * Plain ES modules, no framework and no bundler: the whole console ships inside
 * the server binary, so there is no build step to keep in sync and no
 * third-party script to trust. See docs/COCO_ADMIN_CONSOLE.md for why.
 *
 * Authority never lives here. Every screen reads `/admin/*`, which still
 * requires the administrator identity and the same security model as the native
 * clients; this file only renders what those endpoints return.
 */

const SESSION_IDLE_MINUTES = 15;
const SESSION_COOKIE = 'coco_admin_console';
const CSRF_COOKIE = 'coco_console_csrf';
const PAGE_SIZE = 25;

const state = {
  role: null,
  csrf: null,
  section: 'dashboard',
  params: {},
  counts: {},
  backgroundAt: null,
  sessionEpoch: 0,
};
const activeLists = new Set();

/* ------------------------------------------------------------------ dom */

const $ = (id) => document.getElementById(id);

function el(tag, props = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    // There is deliberately no `html` branch: every string that reaches the DOM
    // is untrusted server data, so the console can only ever set text.
    else if (key === 'dataset') Object.assign(node.dataset, value);
    // Props use React-style camelCase event names (`onClick`, `onKeydown`), but DOM
    // event names are case-sensitive: `key.slice(2)` alone produced `Click`, which
    // never fires. Normalise to the native lowercase event name.
    else if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (value === true) node.setAttribute(key, '');
    else node.setAttribute(key, value);
  }
  for (const child of [].concat(children)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child);
  }
  return node;
}

function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

function text(value, fallback = '—') {
  if (value === null || value === undefined) return fallback;
  const trimmed = String(value).trim();
  return trimmed === '' || trimmed === 'null' ? fallback : trimmed;
}

function when(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return text(value);
  return date.toLocaleString(undefined, {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

function bytes(value) {
  if (value === null || value === undefined || value < 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let size = Number(value);
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit += 1; }
  const rounded = Math.round(size * 10) / 10;
  return `${rounded} ${units[unit]}`;
}

function shortId(value) {
  const raw = text(value, '');
  return raw.length >= 8 ? raw.slice(0, 8) : '—';
}

/* ---------------------------------------------------------------- labels */

/* Backend tokens are translated before they reach the interface. A raw machine
 * value is never the primary label; it stays inside the technical detail. */
const REPORT_STATUS = {
  open: 'New', in_progress: 'In progress', resolved: 'Resolved', dismissed: 'Dismissed',
};
const FEEDBACK_STATUS = { open: 'New', resolved: 'Resolved', dismissed: 'Dismissed' };
const ACCOUNT_STATUS = { active: 'Active', suspended: 'Suspended' };
const TIMELINE_KIND = {
  report_received: 'Report received',
  report_open: 'Report reopened',
  report_started: 'Review started',
  report_resolved: 'Report resolved',
  report_dismissed: 'Report dismissed',
  account_suspended: 'Account restricted',
  account_restored: 'Account restored',
  device_suspended: 'Device restricted',
  device_restored: 'Device restored',
  note_added: 'Internal note added',
  other: 'Other event',
};
const PLATFORM = { android: 'Android', ios: 'iOS' };

function statusChip(status, labels, tone = {}) {
  const label = labels[status] || 'Unknown';
  let cls = 'chip';
  if (tone[status]) cls += ` chip-${tone[status]}`;
  return el('span', { class: cls, text: label });
}

function humanizeAction(action) {
  const raw = text(action, '');
  if (raw === '') return 'Event';
  const known = {
    enroll: 'Administrator enrolled',
    unlock: 'Administration unlocked',
    'rotate-signing-key': 'Signing key replaced',
    suspend: 'Account restricted',
    unsuspend: 'Account restored',
    suspend_with_installations: 'Account and devices restricted',
    unsuspend_with_installations: 'Account and devices restored',
    device_suspend: 'Device restricted',
    device_unsuspend: 'Device restored',
    'note:add': 'Internal note added',
    'report:open': 'Report reopened',
    'report:in_progress': 'Review started',
    'report:resolved': 'Report resolved',
    'report:dismissed': 'Report dismissed',
    'feedback:open': 'Feedback reopened',
    'feedback:resolved': 'Feedback resolved',
    'feedback:dismissed': 'Feedback dismissed',
    'console:handoff-issued': 'Console handoff issued',
    'console:session-open': 'Console opened',
    'console:session-close': 'Console closed',
    'local:clear-message-call-history': 'Local history cleared',
  };
  if (known[raw]) return known[raw];
  const [prefix, rest] = raw.includes(':') ? raw.split(':', 2) : ['', raw];
  const friendly = rest.replace(/[-_]/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
  if (prefix === 'report') return `Report · ${friendly}`;
  if (prefix === 'feedback') return `Feedback · ${friendly}`;
  if (prefix === 'console') return `Console · ${friendly}`;
  if (prefix === 'local') return `Local · ${friendly}`;
  if (prefix === 'server') return `Server · ${friendly}`;
  return friendly;
}

/* --------------------------------------------------------------- errors */

/* A single translation point. SQL, Rust errors, stack traces and framework
 * messages are never rendered: only a localized sentence plus an optional
 * diagnostic code. */
function describe(error) {
  if (error instanceof ApiError) {
    if (error.status === 404) return { message: 'This item is no longer available, or your role cannot open it. Refresh the list and try again.', code: 'not_found' };
    if (error.status === 401) return { message: 'The session expired. Unlock administration in UNYRI again.', code: 'unauthorized' };
    if (error.status === 429) return { message: 'Too many attempts. Wait a few minutes and try again.', code: 'rate_limited' };
    if (error.status >= 500) return { message: 'The server could not complete the request. Try again or contact the server owner.', code: `server_${error.status}` };
    if (error.code === 'validation_error') return { message: error.message, code: 'validation_error' };
    return { message: 'The request was refused. Check the values and try again.', code: error.code || `http_${error.status}` };
  }
  return { message: 'Could not reach the server. Check the connection and try again.', code: 'network' };
}

class ApiError extends Error {
  constructor(status, code, message) { super(message || code || 'error'); this.status = status; this.code = code; this.message = message || ''; }
}

let missingSessionCheck = null;
async function checkSessionAfterMissing() {
  if (!state.role) return;
  if (!missingSessionCheck) {
    const epoch = state.sessionEpoch;
    missingSessionCheck = fetch('/administration/session', {
      credentials: 'same-origin', redirect: 'error', cache: 'no-store', headers: { Accept: 'application/json' },
    }).then(async (response) => {
      const session = response.ok ? await response.json() : null;
      if (epoch === state.sessionEpoch && (response.status === 401 || session?.authenticated === false)) expire();
    }).catch(() => { /* A lost network response does not revoke a valid session. */ })
      .finally(() => { missingSessionCheck = null; });
  }
  await missingSessionCheck;
}

async function api(path, { method = 'GET', body = null, signal = undefined } = {}) {
  const epoch = state.sessionEpoch;
  const headers = { Accept: 'application/json' };
  if (body !== null) headers['Content-Type'] = 'application/json';
  if (state.csrf && method !== 'GET' && method !== 'HEAD') headers['X-COCO-CSRF'] = state.csrf;
  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: 'same-origin',
      redirect: 'error',
      cache: 'no-store',
      body: body === null ? undefined : JSON.stringify(body),
      signal,
    });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new ApiError(0, 'network', 'network');
  }
  const payload = await response.json().catch(() => null);
  if (epoch !== state.sessionEpoch) throw new ApiError(0, 'session_changed', 'Session changed');
  if (!response.ok) {
    const detail = payload && payload.error ? payload.error : {};
    if (response.status === 401) expire();
    else if (response.status === 404 && state.role) await checkSessionAfterMissing();
    throw new ApiError(response.status, detail.code, detail.message);
  }
  return payload;
}

/* ------------------------------------------------------------ banner/modal */

let bannerTimer = null;
function banner(message, isError = false) {
  if (!state.role) return;
  const node = $('banner');
  node.textContent = message;
  node.classList.toggle('is-error', Boolean(isError));
  node.hidden = false;
  if (bannerTimer) clearTimeout(bannerTimer);
  bannerTimer = setTimeout(() => { node.hidden = true; }, 4200);
}

let modalResolve = null;
let modalFocus = null;
let lightboxFocus = null;
function updateOverlayState() {
  const open = !$('modal').hidden || !$('lightbox').hidden;
  $('app').inert = open;
  document.body.classList.toggle('overlay-open', open);
}
function confirmDialog({ title, body, confirmLabel, danger = false }) {
  if (!state.role) return Promise.resolve(false);
  if (modalResolve) closeModal(false);
  return new Promise((resolve) => {
    modalFocus = document.activeElement;
    modalResolve = resolve;
    $('modal-title').textContent = title;
    clear($('modal-body'));
    if (typeof body === 'string') $('modal-body').append(el('p', { text: body }));
    else if (body) $('modal-body').append(body);
    const confirm = $('modal-confirm');
    confirm.textContent = confirmLabel;
    confirm.className = danger ? 'btn btn-danger' : 'btn btn-primary';
    $('modal').hidden = false;
    updateOverlayState();
    // Move focus into the dialog and keep Tab inside it: a keyboard user must
    // not be able to reach the page behind a destructive confirmation.
    // Destructive actions must never become the default Enter target.
    (danger ? $('modal-cancel') : confirm).focus();
  });
}

function trapModalFocus(event) {
  if (event.key !== 'Tab') return;
  const overlay = !$('lightbox').hidden ? $('lightbox') : !$('modal').hidden ? $('modal') : null;
  if (!overlay) return;
  const focusable = overlay.querySelectorAll('button:not(:disabled), textarea:not(:disabled), input:not(:disabled), select:not(:disabled)');
  if (!focusable.length) return;
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
function closeModal(value) {
  $('modal').hidden = true;
  clear($('modal-body'));
  updateOverlayState();
  if (state.role && modalFocus?.isConnected) modalFocus.focus();
  modalFocus = null;
  if (modalResolve) { const resolve = modalResolve; modalResolve = null; resolve(value); }
}

/* --------------------------------------------------------------- sharing */

function stateBlock(kind, message, retry) {
  if (kind === 'loading') {
    return el('div', { class: 'skeleton' }, [
      el('div', { class: 'skeleton-row' }),
      el('div', { class: 'skeleton-row' }),
      el('div', { class: 'skeleton-row' }),
    ]);
  }
  if (kind === 'error') {
    return el('div', { class: 'state state-error' }, [
      el('p', { text: message, class: 'row-body' }),
      retry ? el('button', { class: 'btn btn-sm', text: 'Retry', type: 'button', onClick: retry }) : null,
    ]);
  }
  return el('p', { class: 'state', text: message });
}

function field(label, control, hint) {
  return el('div', { class: 'field' }, [
    el('label', { text: label, for: control.id || undefined }),
    control,
    hint ? el('span', { class: 'hint', text: hint }) : null,
  ]);
}

function input(id, props = {}) {
  const node = el('input', Object.assign({ type: 'text', id }, props));
  return node;
}

function select(id, options) {
  const node = el('select', { id });
  for (const [value, label] of options) node.append(el('option', { value, text: label }));
  return node;
}

function chipRow(options, current, onPick) {
  return el('div', { class: 'chips' }, options.map(([value, label]) =>
    el('button', {
      type: 'button',
      class: 'chip-btn',
      text: label,
      'aria-pressed': String(value === current),
      onClick: () => onPick(value),
    })));
}

function techDetail(value) {
  return el('details', { class: 'tech' }, [
    el('summary', { text: 'Technical detail' }),
    el('pre', { class: 'tech-body', text: JSON.stringify(value, null, 2) }),
  ]);
}

/* A cursor list: the server owns ordering and the page boundary, the client only
 * appends the page it was handed and never invents a boundary of its own. */
function createList({ path, query = () => '', render, key }) {
  const controller = {
    rows: [], cursor: null, hasMore: true, loading: false, failed: null, loaded: false,
    generation: 0, request: null, disposed: false,
  };
  controller.dispose = () => {
    controller.disposed = true;
    controller.generation += 1;
    controller.request?.abort();
    activeLists.delete(controller);
  };
  activeLists.add(controller);
  controller.load = async (reset = false) => {
    if (controller.disposed || (controller.loading && !reset)) return;
    if (reset) controller.request?.abort();
    if (reset) { controller.rows = []; controller.cursor = null; controller.hasMore = true; controller.loaded = false; }
    if (!controller.hasMore && !reset) return;
    const generation = ++controller.generation;
    const request = new AbortController();
    controller.request = request;
    controller.loading = true;
    controller.failed = null;
    render();
    const params = new URLSearchParams();
    params.set('limit', String(PAGE_SIZE));
    const extra = query();
    if (extra) for (const [k, v] of new URLSearchParams(extra)) params.set(k, v);
    if (controller.cursor) params.set('cursor', controller.cursor);
    try {
      const page = await api(`${path}?${params.toString()}`, { signal: request.signal });
      if (controller.disposed || generation !== controller.generation) return;
      const seen = new Set(controller.rows.map(key));
      for (const row of page.items || []) {
        const rowKey = key(row);
        if (!seen.has(rowKey)) { controller.rows.push(row); seen.add(rowKey); }
      }
      controller.cursor = page.next_cursor || null;
      controller.hasMore = Boolean(page.has_more) && Boolean(page.next_cursor);
      controller.loaded = true;
    } catch (error) {
      if (controller.disposed || generation !== controller.generation || error.name === 'AbortError') return;
      if (error instanceof ApiError && error.status !== 404 && error.status !== 401) controller.failed = describe(error);
      else if (error instanceof ApiError && state.role) controller.failed = describe(error);
      // Keep the cursor and hasMore on transient failure so Retry repeats
      // the failed page instead of silently refusing to make a request.
    } finally {
      if (!controller.disposed && generation === controller.generation) {
        controller.loading = false;
        controller.request = null;
        render();
      }
    }
  };
  return controller;
}

function pager(list, emptyMessage) {
  if (list.loading && list.rows.length === 0) return stateBlock('loading');
  if (list.failed && list.rows.length === 0) return stateBlock('error', list.failed.message, () => list.load(true));
  if (list.loaded && list.rows.length === 0) return stateBlock('empty', emptyMessage);
  const parts = [];
  if (list.rows.length) parts.push(el('p', { class: 'pager-status', text: `${list.rows.length} loaded` }));
  if (list.failed) {
    parts.push(el('span', { class: 'pager-status', text: list.failed.message }));
    parts.push(el('button', { class: 'btn btn-sm', type: 'button', text: 'Retry', onClick: () => list.load(false) }));
  } else if (list.hasMore) {
    parts.push(el('button', {
      class: 'btn btn-sm', type: 'button', text: 'Load more',
      disabled: list.loading === true,
      onClick: () => list.load(false),
    }));
    if (list.loading) parts.push(el('span', { class: 'pager-status', text: 'Loading…' }));
  } else if (list.loaded && list.rows.length) {
    parts.push(el('span', { class: 'pager-status', text: 'End of list' }));
  }
  return parts.length ? el('div', { class: 'pager' }, parts) : el('div');
}

function run(action) {
  Promise.resolve()
    .then(action)
    .catch((error) => {
      const info = describe(error);
      banner(info.message, true);
    });
}

/* ============================== sections ============================== */

const NAV = [
  ['dashboard', 'Overview', null],
  ['reports', 'Reports', 'open_reports'],
  ['users', 'Users', null],
  ['feedback', 'Feedback', 'unresolved_feedback'],
  ['audit', 'Audit', null],
  ['storage', 'Storage', null],
  ['settings', 'Server settings', null],
  ['security', 'Sign-in protection', null],
];
const SECTION_DESCRIPTIONS = {
  dashboard: 'Server activity, pending reviews and storage at a glance.',
  reports: 'Review reports, record decisions and keep the moderation history clear.',
  users: 'Find an account and manage access, devices and internal notes.',
  moderation: 'Account details, devices, restrictions and moderation history.',
  feedback: 'Read problem reports from the apps. Screenshots load only when requested.',
  audit: 'A chronological record of administrator actions and their reasons.',
  storage: 'Review usage and inspect a cleanup preview before anything is deleted.',
  settings: 'Public server information and limits. Private account data stays private.',
  security: 'See which protection methods your administrator account currently requires.',
};
function sectionIcon(name) {
  const paths = {
    dashboard: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
    reports: 'M5 21V3h13l-2 5 2 5H5',
    users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-3.8M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
    feedback: 'M21 11a8 8 0 0 1-8 8H7l-4 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4zM8 8h8M8 12h5',
    audit: 'M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2h-3M8 1h8v4H8zM7 10h10M7 14h10M7 18h6',
    storage: 'M3 3h18v6H3zM3 15h18v6H3zM7 6h.01M7 18h.01',
    settings: 'M4 7h16M4 17h16M8 4v6M16 14v6',
    security: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6zM8 12l3 3 5-6',
  };
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  for (const [key, value] of Object.entries({ viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round', class: 'nav-icon', 'aria-hidden': 'true' })) svg.setAttribute(key, value);
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', paths[name] || paths.dashboard);
  svg.append(path);
  return svg;
}

function renderNav() {
  const list = $('nav-list');
  clear(list);
  for (const [id, label, countKey] of NAV) {
    if ((id === 'storage' || id === 'settings' || id === 'audit') && state.role !== 'owner') continue;
    const count = countKey ? state.counts[countKey] : null;
    list.append(el('li', {}, [
      el('button', {
        type: 'button',
        class: 'nav-btn',
        'aria-current': state.section === id ? 'page' : null,
        onClick: () => { state.params = {}; go(id); },
      }, [
        sectionIcon(id),
        el('span', { text: label }),
        count ? el('span', { class: 'nav-count', text: String(count) }) : null,
      ]),
    ]));
  }
  $('session-note').textContent = state.role === 'owner'
    ? 'Owner access · Inactive sessions lock after 15 minutes.'
    : 'Moderator access · Server and storage controls are reserved for the owner.';
}

function render() {
  $('role-chip').textContent = state.role === 'owner' ? 'Owner' : 'Moderator';
  const view = $('view');
  clear(view);
  const section = SECTIONS[state.section] || SECTIONS.dashboard;
  $('section-title').textContent = NAV.find(([id]) => id === state.section)?.[1] || 'Account';
  $('section-description').textContent = SECTION_DESCRIPTIONS[state.section] || '';
  view.append(section());
}

function go(section, params = {}) {
  for (const list of [...activeLists]) list.dispose();
  closeModal(false);
  closeLightbox();
  state.section = section;
  state.params = params;
  renderNav();
  render();
  $('content').focus();
  if (window.matchMedia('(max-width: 899px)').matches) {
    $('nav').hidden = true;
    $('nav-toggle').setAttribute('aria-expanded', 'false');
  }
}

/* ------------------------------------------------------------- dashboard */

const SECTIONS = {};

SECTIONS.dashboard = () => {
  const card = el('div', { class: 'card' }, [el('h2', { text: 'Activity' })]);
  const body = el('div');
  card.append(body);
  const load = () => {
    clear(body);
    body.append(stateBlock('loading'));
    api('/admin/summary')
      .then((data) => {
        state.counts = data;
        renderNav();
        clear(body);
        const metrics = [
          ['Open reports', data.open_reports, 'reports'],
          ['In progress', data.in_progress_reports, 'reports'],
          ['Unresolved feedback', data.unresolved_feedback, 'feedback'],
          ['Account restrictions', data.active_account_suspensions, 'users'],
          ['Device restrictions', data.active_installation_suspensions, 'users'],
          ['Reports, last 24 h', data.reports_last_24h, 'reports'],
        ];
        body.append(el('div', { class: 'grid grid-cards dashboard-metrics' }, metrics.map(([label, value, target]) =>
          el('div', {
            class: 'metric',
            role: 'button',
            tabindex: '0',
            title: `Open ${label.toLowerCase()}`,
            onClick: () => go(target),
            onKeydown: (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); go(target); } },
          }, [
            el('span', { class: 'metric-value', text: String(value ?? 0) }),
            el('span', { class: 'metric-label', text: label }),
          ]))));
        if (data.storage) {
          const used = Number(data.storage.used_bytes || 0);
          const reserved = Number(data.storage.reserved_bytes || 0);
          const budget = Number(data.storage.budget_bytes || 0);
          const share = budget > 0 ? (used + reserved) / budget : 0;
          body.append(el('div', { class: 'card', style: null }, [
            el('div', { class: 'card-head' }, [
              el('h2', { text: 'Attachment storage' }),
              el('span', { class: 'chip', text: 'Owner' }),
            ]),
            el('div', { class: 'grid grid-2' }, [
              el('div', { class: 'metric' }, [
                el('span', { class: 'metric-value', text: bytes(used) }),
                el('span', { class: 'metric-label', text: 'Used' }),
              ]),
              el('div', { class: 'metric' }, [
                el('span', { class: 'metric-value', text: bytes(reserved) }),
                el('span', { class: 'metric-label', text: 'Reserved' }),
              ]),
              el('div', { class: 'metric' }, [
                el('span', { class: 'metric-value', text: budget > 0 ? bytes(budget) : 'Unlimited' }),
                el('span', { class: 'metric-label', text: 'Budget' }),
              ]),
            ]),
            budget > 0 ? el('progress', { class: 'progress-meter', max: '100', value: String(Math.min(100, Math.round(share * 100))) }) : null,
            share >= 0.9 ? el('p', { class: 'state', text: 'The storage budget is at least 90% used.' }) : null,
          ]));
        } else {
          body.append(el('p', { class: 'state', text: 'Storage figures are shown to the server owner only.' }));
        }
      })
      .catch((error) => { clear(body); const info = describe(error); body.append(stateBlock('error', info.message, load)); });
  };
  load();
  return card;
};

/* --------------------------------------------------------------- reports */

const REPORT_REASONS = ['spam', 'harassment', 'pornography', 'scam', 'self_harm', 'copyright', 'other'];

function reasonLabel(reason) {
  return reason.replace(/_/g, ' ').replace(/^\w/, (c) => c.toUpperCase());
}

SECTIONS.reports = () => {
  const filters = { status: '', reason: '', reported_user_id: '', from: '', to: '' };
  const list = createList({
    path: '/admin/reports',
    query: () => new URLSearchParams(Object.entries(filters).filter(([, v]) => v !== '')).toString(),
    key: (row) => row.id,
    render: () => renderBody(),
  });

  const statusChips = chipRow(
    [['', 'All'], ['open', 'New'], ['in_progress', 'In progress'], ['resolved', 'Resolved'], ['dismissed', 'Dismissed']],
    filters.status,
    (value) => { filters.status = value; list.load(true); renderBody(); },
  );
  const reasonSelect = select('report-reason', [['', 'Any reason'], ...REPORT_REASONS.map((r) => [r, reasonLabel(r)])]);
  reasonSelect.addEventListener('change', () => { filters.reason = reasonSelect.value; list.load(true); });
  const subjectInput = input('report-subject', { placeholder: 'Reported account id', autocomplete: 'off' });
  subjectInput.addEventListener('change', () => { filters.reported_user_id = subjectInput.value.trim(); list.load(true); });
  const fromInput = el('input', { type: 'datetime-local', id: 'report-from' });
  fromInput.addEventListener('change', () => { filters.from = fromInput.value ? new Date(fromInput.value).toISOString() : ''; list.load(true); });
  const toInput = el('input', { type: 'datetime-local', id: 'report-to' });
  toInput.addEventListener('change', () => { filters.to = toInput.value ? new Date(toInput.value).toISOString() : ''; list.load(true); });

  const body = el('div');
  function renderBody() {
    clear(body);
    body.append(pager(list, 'No reports match these filters.'));
    const rows = el('ul', { class: 'rows' });
    for (const report of list.rows) rows.append(reportRow(report, list));
    if (list.rows.length) body.append(el('div', { class: 'card' }, [
      el('h2', { text: 'Reports' }),
      rows,
    ]));
  }

  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [
      el('h2', { text: 'Reports' }),
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => list.load(true) }),
    ]),
    statusChips,
    el('div', { class: 'filters' }, [
      field('Reason', reasonSelect),
      field('Reported account', subjectInput, 'Exact account id as shown in the report.'),
      field('From', fromInput),
      field('To', toInput),
    ]),
  ]);
  card.append(body);
  list.load(true);
  return card;
};

function reportRow(report, list) {
  const row = el('li', { class: 'row' });
  row.append(el('div', { class: 'row-head' }, [
    el('span', { class: 'row-title', text: text(report.reason, 'Reported content') }),
    statusChip(report.status, REPORT_STATUS, { open: 'accent', in_progress: 'warn', resolved: 'ok' }),
  ]));
  row.append(el('div', { class: 'row-meta' }, [
    el('span', { text: `Reported: ${shortId(report.user_id)}` }),
    el('span', { text: `Created: ${when(report.created_at)}` }),
    report.reviewed_at ? el('span', { text: `Reviewed: ${when(report.reviewed_at)}` }) : null,
  ]));
  if (report.description) row.append(el('p', { class: 'row-body', text: report.description }));
  if (report.review_note) row.append(el('p', { class: 'row-body', text: `Note: ${report.review_note}` }));
  if (report.decision_reason) row.append(el('p', { class: 'row-body', text: `Decision: ${report.decision_reason}` }));
  const actions = el('div', { class: 'row-actions' });
  if (report.status !== 'in_progress') {
    actions.append(el('button', {
      class: 'btn btn-sm', type: 'button', text: 'Start review',
      onClick: () => run(async () => {
        await api(`/admin/reports/${report.id}`, { method: 'POST', body: { status: 'in_progress', note: report.review_note || '' } });
        banner('Review started');
        list.load(true);
      }),
    }));
  }
  actions.append(el('button', {
    class: 'btn btn-sm', type: 'button', text: 'Resolve',
    onClick: () => reviewReport(report, 'resolved', list),
  }));
  actions.append(el('button', {
    class: 'btn btn-sm btn-danger-quiet', type: 'button', text: 'Dismiss',
    onClick: () => reviewReport(report, 'dismissed', list),
  }));
  if (report.user_id) {
    actions.append(el('button', {
      class: 'btn btn-sm', type: 'button', text: 'Open moderation centre',
      onClick: () => go('moderation', { userId: report.user_id }),
    }));
  }
  row.append(actions);
  row.append(techDetail({ id: report.id, reported_user_id: report.user_id, reviewer: report.reviewed_by }));
  return row;
}

async function reviewReport(report, status, list) {
  const note = input(`note-${report.id}`, { value: report.review_note || '', placeholder: 'Moderator note' });
  const decision = el('textarea', { id: `decision-${report.id}`, placeholder: 'Reason for the decision (required when closing)' });
  decision.value = report.decision_reason || '';
  const body = el('div', {}, [
    field('Moderator note', note, 'Visible to administrators only.'),
    field('Decision reason', decision, 'Required when resolving or dismissing.'),
  ]);
  const confirmed = await confirmDialog({
    title: status === 'resolved' ? 'Resolve this report?' : 'Dismiss this report?',
    body,
    confirmLabel: status === 'resolved' ? 'Resolve' : 'Dismiss',
    danger: status === 'dismissed',
  });
  if (!confirmed) return;
  run(async () => {
    await api(`/admin/reports/${report.id}`, {
      method: 'POST',
      body: { status, note: note.value, decision_reason: decision.value },
    });
    banner('Report updated');
    list.load(true);
  });
}

/* ----------------------------------------------------------------- users */

SECTIONS.users = () => {
  const filters = { query: '', status: '' };
  const list = createList({
    path: '/admin/users',
    query: () => new URLSearchParams(Object.entries(filters).filter(([, v]) => v !== '')).toString(),
    key: (row) => row.user_id,
    render: () => renderBody(),
  });
  const search = input('user-search', { type: 'search', placeholder: 'Username or display name', autocomplete: 'off' });
  let debounce = null;
  search.addEventListener('input', () => {
    if (debounce) clearTimeout(debounce);
    debounce = setTimeout(() => { filters.query = search.value.trim(); list.load(true); }, 320);
  });
  const chips = chipRow([['', 'All'], ['active', 'Active'], ['suspended', 'Suspended']], filters.status,
    (value) => { filters.status = value; list.load(true); renderBody(); });

  const body = el('div');
  function renderBody() {
    clear(body);
    if (!filters.query && !filters.status) {
      body.append(stateBlock('empty', 'Search for an account, or pick a status to list accounts.'));
      return;
    }
    body.append(pager(list, 'No accounts match this search.'));
    if (!list.rows.length) return;
    const rows = el('ul', { class: 'rows' });
    for (const user of list.rows) {
      rows.append(el('li', { class: 'row' }, [
        el('div', { class: 'row-head' }, [
          el('span', { class: 'row-title', text: text(user.description, text(user.reason, shortId(user.user_id))) }),
          statusChip(user.status, ACCOUNT_STATUS, { suspended: 'danger', active: 'ok' }),
        ]),
        el('div', { class: 'row-meta' }, [
          el('span', { text: user.reason ? `@${user.reason}` : `Account ${shortId(user.user_id)}` }),
          el('span', { text: `Created: ${when(user.created_at)}` }),
          Number(user.installation_blocks) > 0 ? el('span', { text: `Restricted devices: ${user.installation_blocks}` }) : null,
        ]),
        el('div', { class: 'row-actions' }, [
          el('button', {
            class: 'btn btn-sm', type: 'button', text: 'Open moderation centre',
            onClick: () => go('moderation', { userId: user.user_id }),
          }),
        ]),
        techDetail({ user_id: user.user_id, sort_key: user.sort_key, suspended_until: user.suspended_until }),
      ]));
    }
    body.append(el('div', { class: 'card' }, [el('h2', { text: 'Accounts' }), rows]));
  }

  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [
      el('h2', { text: 'Accounts' }),
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => list.load(true) }),
    ]),
    field('Search', search, 'Administrators can also match the display name.'),
    chips,
  ]);
  card.append(body);
  renderBody();
  return card;
};

/* ----------------------------------------------------- moderation centre */

const SUSPENSION_PRESETS = [
  ['1h', '1 hour'],
  ['24h', '24 hours'],
  ['7d', '7 days'],
  ['30d', '30 days'],
  ['forever', 'Permanent'],
  ['custom', 'Custom'],
];

function suspensionUntil(preset, customHours) {
  if (preset === 'forever') return null;
  if (preset === 'custom') {
    const hours = Number(customHours);
    if (!Number.isFinite(hours) || hours <= 0) throw new ApiError(400, 'validation_error', 'Enter a number of hours greater than zero.');
    return new Date(Date.now() + hours * 3600 * 1000).toISOString();
  }
  const hours = preset === '1h' ? 1 : preset === '24h' ? 24 : preset === '7d' ? 168 : 720;
  return new Date(Date.now() + hours * 3600 * 1000).toISOString();
}

SECTIONS.moderation = () => {
  const userId = state.params.userId;
  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Back', onClick: () => go('users') }),
      el('h2', { text: 'User moderation centre' }),
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => load() }),
    ]),
  ]);
  const body = el('div');
  card.append(body);
  let detail = null;

  const timeline = createList({
    path: `/admin/users/${userId}/timeline`,
    key: (row) => row.ref || `${row.occurred_at}-${row.kind}`,
    render: () => renderTimeline(),
  });
  const notes = createList({
    path: `/admin/users/${userId}/notes`,
    key: (row) => row.id,
    render: () => renderNotes(),
  });
  const reports = createList({
    path: '/admin/reports',
    query: () => new URLSearchParams({ reported_user_id: userId }).toString(),
    key: (row) => row.id,
    render: () => renderReports(),
  });

  const timelineBody = el('div');
  const notesBody = el('div');
  const reportsBody = el('div');

  function renderTimeline() {
    clear(timelineBody);
    timelineBody.append(pager(timeline, 'No moderation events recorded for this account.'));
    const list = el('ul', { class: 'timeline' });
    for (const event of timeline.rows) {
      list.append(el('li', {}, [
        el('span', { class: 'what', text: TIMELINE_KIND[event.kind] || 'Other event' }),
        el('div', { class: 'when', text: when(event.occurred_at) }),
        event.reason ? el('div', { class: 'why', text: event.reason }) : null,
        techDetail({ actor: event.actor, ref: event.ref, detail: event.detail }),
      ]));
    }
    if (timeline.rows.length) timelineBody.append(list);
  }

  function renderNotes() {
    clear(notesBody);
    notesBody.append(pager(notes, 'No internal notes for this account.'));
    for (const note of notes.rows) {
      notesBody.append(el('div', { class: 'note' }, [
        el('span', { class: 'note-internal', text: 'Internal only' }),
        el('p', { class: 'note-body', text: note.body }),
        el('p', { class: 'note-meta', text: `${when(note.created_at)} · author ${shortId(note.author_user_id)}` }),
      ]));
    }
    const textarea = el('textarea', { id: 'new-note', placeholder: 'Add an internal note (1–2000 characters)' });
    notesBody.append(el('div', { class: 'form-actions' }, [
      textarea,
      el('button', {
        class: 'btn btn-primary', type: 'button', text: 'Add note',
        onClick: () => run(async () => {
          const value = textarea.value.trim();
          if (value.length < 1 || value.length > 2000) throw new ApiError(400, 'validation_error', 'A note between 1 and 2000 characters is required.');
          await api(`/admin/users/${userId}/notes`, { method: 'POST', body: { body: value } });
          textarea.value = '';
          banner('Note added');
          notes.load(true);
        }),
      }),
    ]));
  }

  function renderReports() {
    clear(reportsBody);
    reportsBody.append(pager(reports, 'No reports involve this account.'));
    const rows = el('ul', { class: 'rows' });
    for (const report of reports.rows) rows.append(reportRow(report, reports));
    if (reports.rows.length) reportsBody.append(rows);
  }

  async function suspendAccount(blocked) {
    const reason = el('textarea', { id: `susp-account-${blocked ? 'on' : 'off'}`, placeholder: 'Reason (required)' });
    const preset = select('susp-preset', SUSPENSION_PRESETS);
    const custom = el('input', { type: 'text', id: 'susp-custom-hours', placeholder: 'Hours', inputmode: 'numeric' });
    const include = el('input', { type: 'checkbox', id: 'susp-include' });
    const body = el('div', {}, [
      field('Reason', reason, 'Recorded in the audit journal.'),
      blocked ? field('Duration', preset) : null,
      blocked ? field('Custom hours', custom, 'Used when the duration is "Custom".') : null,
      blocked ? el('label', { class: 'checkbox' }, [include, el('span', { text: 'Also restrict this account’s known app installations' })]) : null,
      el('p', { class: 'hint', text: blocked
        ? 'A device restriction is separate from an account restriction: use the device list below to restrict one app installation only.'
        : 'Restoring an account does not lift device restrictions unless you include installations.' }),
    ]);
    const confirmed = await confirmDialog({
      title: blocked ? 'Restrict this account?' : 'Restore server access?',
      body,
      confirmLabel: blocked ? 'Restrict' : 'Restore',
      danger: blocked,
    });
    if (!confirmed) return;
    run(async () => {
      const payload = { blocked, reason: reason.value.trim() };
      if (blocked) {
        const until = suspensionUntil(preset.value, custom.value);
        if (until) payload.until = until;
      }
      if (include.checked) payload.include_installations = true;
      await api(`/admin/users/${userId}/suspension`, { method: 'POST', body: payload });
      banner(blocked ? 'Account restricted' : 'Account restored');
      load();
    });
  }

  async function suspendDevice(device, blocked) {
    const reason = el('textarea', { id: 'susp-device-reason', placeholder: 'Reason (required)' });
    const preset = select('susp-device-preset', SUSPENSION_PRESETS);
    const custom = el('input', { type: 'text', id: 'susp-device-hours', placeholder: 'Hours', inputmode: 'numeric' });
    const confirmed = await confirmDialog({
      title: blocked ? 'Restrict this device?' : 'Restore this device?',
      body: el('div', {}, [
        el('p', { class: 'row-body', text: `${text(device.display_name, 'Unnamed device')} · ${text(device.platform)} · ${text(device.installation_id)}` }),
        field('Reason', reason),
        blocked ? field('Duration', preset) : null,
        blocked ? field('Custom hours', custom) : null,
        el('p', { class: 'hint', text: 'Only this app installation loses access; the account keeps working on its other devices.' }),
      ]),
      confirmLabel: blocked ? 'Restrict device' : 'Restore device',
      danger: blocked,
    });
    if (!confirmed) return;
    run(async () => {
      const payload = { blocked, reason: reason.value.trim() };
      if (blocked) {
        const until = suspensionUntil(preset.value, custom.value);
        if (until) payload.until = until;
      }
      await api(`/admin/installations/${device.installation_id}/suspension`, { method: 'POST', body: payload });
      banner(blocked ? 'Device restricted' : 'Device restored');
      load();
    });
  }

  function renderDetail() {
    clear(body);
    body.append(el('div', { class: 'grid grid-2' }, [
      el('div', { class: 'card' }, [
        el('h3', { text: 'Profile' }),
        el('p', { class: 'row-title', text: text(detail.profile.display_name, text(detail.profile.username)) }),
        el('p', { class: 'row-meta', text: detail.profile.username ? `@${detail.profile.username}` : 'No username' }),
        el('p', { class: 'row-meta', text: `Created: ${when(detail.profile.created_at)}` }),
        detail.is_administrator ? el('p', {}, [el('span', { class: 'chip chip-accent', text: 'Server administrator' })]) : null,
        techDetail({ user_id: detail.profile.user_id, has_avatar: detail.profile.has_avatar }),
      ]),
      el('div', { class: 'card' }, [
        el('div', { class: 'card-head' }, [
          el('h3', { text: 'Moderation state' }),
          statusChip(detail.moderation.status, ACCOUNT_STATUS, { suspended: 'danger', active: 'ok' }),
        ]),
        el('p', { class: 'row-meta', text: `Reports total: ${detail.moderation.reports_total} · open: ${detail.moderation.reports_open}` }),
        el('p', { class: 'row-meta', text: `Until: ${detail.moderation.until ? when(detail.moderation.until) : 'No expiry'}` }),
        detail.moderation.reason ? el('p', { class: 'row-body', text: detail.moderation.reason }) : null,
        el('div', { class: 'form-actions' }, [
          detail.suspend_allowed
            ? el('button', { class: 'btn btn-danger', type: 'button', text: 'Restrict account', onClick: () => suspendAccount(true) })
            : el('span', { class: 'chip chip-warn', text: 'This account cannot be restricted here' }),
          el('button', { class: 'btn', type: 'button', text: 'Restore account access', onClick: () => suspendAccount(false) }),
        ]),
      ]),
    ]));

    const devices = el('div', { class: 'card' }, [el('h3', { text: 'Known installations' })]);
    if (!detail.devices.length) devices.append(stateBlock('empty', 'This account has no known installations.'));
    else {
      const wrap = el('div', { class: 'table-wrap' });
      const table = el('table', { class: 'data' }, [
        el('thead', {}, [el('tr', {}, [
          el('th', { text: 'Device' }), el('th', { text: 'Platform' }), el('th', { text: 'Last active' }),
          el('th', { text: 'State' }), el('th', { text: 'Actions' }),
        ])]),
      ]);
      const tbody = el('tbody');
      for (const device of detail.devices) {
        const restricted = Boolean(device.restriction_active);
        const stateChip = device.revoked
          ? el('span', { class: 'chip', text: 'Signed out' })
          : restricted ? el('span', { class: 'chip chip-danger', text: 'Restricted' }) : el('span', { class: 'chip chip-ok', text: 'Active' });
        tbody.append(el('tr', {}, [
          el('td', {}, [
            el('div', { text: text(device.display_name, 'Unnamed device') }),
            el('div', { class: 'diagnostic', text: device.installation_id }),
            device.is_administrator_device ? el('span', { class: 'chip chip-accent', text: 'Administrator device' }) : null,
          ]),
          el('td', { text: text(device.platform) }),
          el('td', { text: when(device.last_active_at) }),
          el('td', {}, [stateChip, restricted && device.restriction_until ? el('div', { class: 'diagnostic', text: `Until ${when(device.restriction_until)}` }) : null]),
          el('td', {}, [
            device.is_administrator_device
              ? el('span', { class: 'diagnostic', text: 'Protected' })
              : el('button', {
                class: 'btn btn-sm', type: 'button', text: restricted ? 'Restore' : 'Restrict',
                onClick: () => suspendDevice(device, !restricted),
              }),
          ]),
        ]));
      }
      table.append(tbody);
      wrap.append(table);
      devices.append(wrap);
    }

    body.append(el('div', { class: 'grid grid-2' }, [
      el('div', { class: 'card' }, [
        el('div', { class: 'card-head' }, [el('h3', { text: 'Moderation history' }), el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => { timeline.load(true); reports.load(true); } })]),
        timelineBody,
        el('h3', { text: 'Reports involving this account' }),
        reportsBody,
      ]),
      el('div', { class: 'card' }, [el('h3', { text: 'Internal notes' }), notesBody]),
    ]));
    body.append(devices);
  }

  function load() {
    clear(body);
    body.append(stateBlock('loading'));
    timeline.load(true);
    notes.load(true);
    reports.load(true);
    api(`/admin/users/${encodeURIComponent(userId)}`)
      .then((data) => {
        detail = data;
        renderDetail();
        renderReports();
      })
      .catch((error) => { clear(body); const info = describe(error); body.append(stateBlock('error', info.message, load)); });
  }

  if (!userId) {
    body.append(stateBlock('empty', 'Choose an account first: open Users and pick one.'));
    return card;
  }

  load();
  return card;
};

/* -------------------------------------------------------------- feedback */

SECTIONS.feedback = () => {
  const filters = { platform: '', status: '', from: '', to: '' };
  const list = createList({
    path: '/admin/feedback',
    query: () => new URLSearchParams(Object.entries(filters).filter(([, v]) => v !== '')).toString(),
    key: (row) => row.id,
    render: () => renderBody(),
  });
  const platform = select('feedback-platform', [['', 'Any platform'], ['android', 'Android'], ['ios', 'iOS']]);
  platform.addEventListener('change', () => { filters.platform = platform.value; list.load(true); });
  const status = select('feedback-status', [['', 'Any status'], ['open', 'New'], ['resolved', 'Resolved'], ['dismissed', 'Dismissed']]);
  status.addEventListener('change', () => { filters.status = status.value; list.load(true); });
  const fromInput = el('input', { type: 'datetime-local', id: 'feedback-from' });
  fromInput.addEventListener('change', () => { filters.from = fromInput.value ? new Date(fromInput.value).toISOString() : ''; list.load(true); });
  const toInput = el('input', { type: 'datetime-local', id: 'feedback-to' });
  toInput.addEventListener('change', () => { filters.to = toInput.value ? new Date(toInput.value).toISOString() : ''; list.load(true); });

  const body = el('div');
  function renderBody() {
    clear(body);
    body.append(pager(list, 'No feedback matches these filters.'));
    const rows = el('ul', { class: 'rows' });
    for (const item of list.rows) rows.append(feedbackRow(item, list));
    if (list.rows.length) body.append(el('div', { class: 'card' }, [el('h2', { text: 'Feedback' }), rows]));
  }

  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [
      el('h2', { text: 'Technical feedback' }),
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => list.load(true) }),
    ]),
    el('p', { class: 'hint', text: 'Lists carry metadata and a screenshot count only. Images are fetched one at a time when you ask for them.' }),
    el('div', { class: 'filters' }, [
      field('Platform', platform),
      field('Status', status),
      field('From', fromInput),
      field('To', toInput),
    ]),
  ]);
  card.append(body);
  list.load(true);
  return card;
};

function feedbackRow(item, list) {
  const row = el('li', { class: 'row' });
  row.append(el('div', { class: 'row-head' }, [
    el('span', { class: 'row-title', text: text(item.description, 'Feedback') }),
    statusChip(item.status, FEEDBACK_STATUS, { open: 'accent', resolved: 'ok' }),
  ]));
  row.append(el('div', { class: 'row-meta' }, [
    el('span', { text: `Platform: ${PLATFORM[item.platform] || text(item.platform)}` }),
    el('span', { text: `App ${text(item.app_version)} · OS ${text(item.os_version)}` }),
    el('span', { text: `Created: ${when(item.created_at)}` }),
    Number(item.screenshot_count) > 0 ? el('span', { text: `Screenshots: ${item.screenshot_count}` }) : null,
  ]));

  const shots = el('div', { class: 'screenshot-grid' });
  const actions = el('div', { class: 'row-actions' }, [
    el('button', {
      class: 'btn btn-sm', type: 'button', text: 'Load detail',
      onClick: () => run(async () => {
        const detail = await api(`/admin/feedback/${item.id}`);
        renderScreenshots(shots, item.id, detail.screenshots || []);
      }),
    }),
    el('button', { class: 'btn btn-sm', type: 'button', text: 'Start review', onClick: () => reviewFeedback(item, 'open', list) }),
    el('button', { class: 'btn btn-sm', type: 'button', text: 'Resolve', onClick: () => reviewFeedback(item, 'resolved', list) }),
    el('button', { class: 'btn btn-sm btn-danger-quiet', type: 'button', text: 'Dismiss', onClick: () => reviewFeedback(item, 'dismissed', list) }),
  ]);
  row.append(actions);
  row.append(shots);
  row.append(techDetail({ id: item.id, user_id: item.user_id, review_note: item.review_note, decision_reason: item.decision_reason }));
  return row;
}

/* Screenshots load on demand: nothing is fetched until the administrator asks,
 * each image has its own retry, and the bytes never leave the memory of this
 * page. */
function renderScreenshots(container, feedbackId, descriptors) {
  clear(container);
  if (!descriptors.length) {
    container.append(el('p', { class: 'hint', text: 'No screenshots were attached to this feedback.' }));
    return;
  }
  for (const descriptor of descriptors) {
    const slot = el('div', { class: 'shot-slot' });
    const label = `Screenshot ${descriptor.index + 1} · ${text(descriptor.mime_type)} · ${bytes(descriptor.byte_size)}`;
    const load = () => {
      clear(slot);
      slot.append(el('span', { text: 'Loading…' }));
      const image = el('img', {
        alt: `Feedback screenshot ${descriptor.index + 1}`,
        class: 'shot-zoom',
        loading: 'lazy',
      });
      image.addEventListener('load', () => {
        clear(slot);
        slot.append(image);
        slot.append(el('button', {
          class: 'btn btn-sm', type: 'button', text: 'Fullscreen',
          onClick: () => openLightbox(image.src),
        }));
      });
      image.addEventListener('error', () => {
        clear(slot);
        slot.append(el('span', { text: 'Could not load this image.' }));
        slot.append(el('button', { class: 'btn btn-sm', type: 'button', text: 'Retry', onClick: load }));
      });
      image.src = `/admin/feedback/${feedbackId}/screenshots/${descriptor.index}`;
    };
    clear(slot);
    slot.append(el('span', { text: label }));
    slot.append(el('button', { class: 'btn btn-sm', type: 'button', text: 'Show image', onClick: load }));
    container.append(slot);
  }
}

function openLightbox(src) {
  if (!state.role) return;
  lightboxFocus = document.activeElement;
  $('lightbox-image').src = src;
  $('lightbox').hidden = false;
  updateOverlayState();
  $('lightbox-close').focus();
}
function closeLightbox() {
  $('lightbox').hidden = true;
  $('lightbox-image').src = '';
  updateOverlayState();
  if (state.role && lightboxFocus?.isConnected) lightboxFocus.focus();
  lightboxFocus = null;
}

async function reviewFeedback(item, status, list) {
  const note = input(`fnote-${item.id}`, { value: item.review_note || '', placeholder: 'Moderator note' });
  const decision = el('textarea', { id: `fdecision-${item.id}`, placeholder: 'Reason (required when closing)' });
  decision.value = item.decision_reason || '';
  const confirmed = await confirmDialog({
    title: status === 'open' ? 'Reopen this feedback?' : `Mark feedback ${status}?`,
    body: el('div', {}, [
      field('Moderator note', note),
      field('Decision reason', decision, status === 'open' ? 'Optional when reopening.' : 'Required when closing.'),
    ]),
    confirmLabel: 'Save',
    danger: status === 'dismissed',
  });
  if (!confirmed) return;
  run(async () => {
    await api(`/admin/feedback/${item.id}/review`, {
      method: 'POST',
      body: { status, note: note.value, decision_reason: decision.value },
    });
    banner('Feedback updated');
    list.load(true);
  });
}

/* ----------------------------------------------------------------- audit */

SECTIONS.audit = () => {
  const filters = { action: '', actor: '', target: '', from: '', to: '' };
  const list = createList({
    path: '/admin/audit',
    query: () => new URLSearchParams(Object.entries(filters).filter(([, v]) => v !== '')).toString(),
    key: (row) => row.id,
    render: () => renderBody(),
  });
  const controls = {};
  for (const [key, label, placeholder] of [
    ['action', 'Action', 'e.g. suspend'],
    ['actor', 'Administrator', 'Account id'],
    ['target', 'Target', 'Account id'],
  ]) {
    const node = input(`audit-${key}`, { placeholder, autocomplete: 'off' });
    node.addEventListener('change', () => { filters[key] = node.value.trim(); list.load(true); });
    controls[key] = field(label, node);
  }
  const fromInput = el('input', { type: 'datetime-local', id: 'audit-from' });
  fromInput.addEventListener('change', () => { filters.from = fromInput.value ? new Date(fromInput.value).toISOString() : ''; list.load(true); });
  const toInput = el('input', { type: 'datetime-local', id: 'audit-to' });
  toInput.addEventListener('change', () => { filters.to = toInput.value ? new Date(toInput.value).toISOString() : ''; list.load(true); });

  const body = el('div');
  function renderBody() {
    clear(body);
    body.append(pager(list, 'No journal entries match these filters.'));
    if (!list.rows.length) return;
    const table = el('table', { class: 'data' }, [
      el('thead', {}, [el('tr', {}, [
        el('th', { text: 'When' }), el('th', { text: 'Action' }), el('th', { text: 'Administrator' }),
        el('th', { text: 'Target' }), el('th', { text: 'Reason' }), el('th', { text: 'Detail' }),
      ])]),
    ]);
    const tbody = el('tbody');
    for (const entry of list.rows) {
      tbody.append(el('tr', {}, [
        el('td', { text: when(entry.created_at) }),
        el('td', { text: humanizeAction(entry.action) }),
        el('td', { class: 'wrap', text: shortId(entry.actor) }),
        el('td', { class: 'wrap', text: shortId(entry.target) }),
        el('td', { class: 'wrap', text: text(entry.reason) }),
        el('td', {}, [techDetail({ action: entry.action, id: entry.id, detail: entry.detail })]),
      ]));
    }
    table.append(tbody);
    body.append(el('div', { class: 'card' }, [
      el('div', { class: 'table-wrap' }, [table]),
    ]));
  }

  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [
      el('h2', { text: 'Action journal' }),
      el('button', { class: 'btn btn-sm', type: 'button', text: 'Refresh', onClick: () => list.load(true) }),
    ]),
    el('div', { class: 'filters' }, [controls.action, controls.actor, controls.target, field('From', fromInput), field('To', toInput)]),
  ]);
  card.append(body);
  list.load(true);
  return card;
};

/* --------------------------------------------------------------- storage */

SECTIONS.storage = () => {
  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [el('h2', { text: 'Attachment storage' }), el('span', { class: 'chip chip-accent', text: 'Owner' })]),
  ]);
  const body = el('div');
  card.append(body);
  let plan = null;

  const load = () => {
    clear(body);
    body.append(stateBlock('loading'));
    api('/admin/storage')
      .then((data) => {
        clear(body);
        const budget = Number(data.budget_bytes || 0);
        body.append(el('div', { class: 'grid grid-cards' }, [
          el('div', { class: 'metric' }, [el('span', { class: 'metric-value', text: bytes(data.used_bytes) }), el('span', { class: 'metric-label', text: 'Used' })]),
          el('div', { class: 'metric' }, [el('span', { class: 'metric-value', text: bytes(data.reserved_bytes) }), el('span', { class: 'metric-label', text: 'Reserved' })]),
          el('div', { class: 'metric' }, [el('span', { class: 'metric-value', text: budget > 0 ? bytes(budget) : 'Unlimited' }), el('span', { class: 'metric-label', text: 'Budget' })]),
          el('div', { class: 'metric' + (data.physical_free_bytes !== null && data.physical_free_bytes !== undefined && Number(data.physical_free_bytes) < 1024 * 1024 * 1024 ? ' is-warn' : '') }, [
            el('span', { class: 'metric-value', text: data.physical_free_bytes === null || data.physical_free_bytes === undefined ? '—' : bytes(data.physical_free_bytes) }),
            el('span', { class: 'metric-label', text: 'Free on disk' }),
          ]),
        ]));
        if (data.warning) body.append(el('p', { class: 'state', text: 'The storage budget is at least 90% used.' }));
        if (data.users && data.users.length) {
          const rows = el('ul', { class: 'rows' });
          for (const user of data.users) {
            rows.append(el('li', { class: 'row' }, [
              el('div', { class: 'row-head' }, [
                el('span', { class: 'row-title', text: shortId(user.user_id) }),
                el('span', { class: 'chip', text: bytes(user.bytes) }),
              ]),
              el('div', { class: 'row-actions' }, [
                el('button', { class: 'btn btn-sm', type: 'button', text: 'Open moderation centre', onClick: () => go('moderation', { userId: user.user_id }) }),
              ]),
            ]));
          }
          body.append(el('div', { class: 'card' }, [el('h3', { text: 'Largest accounts' }), rows]));
        }

        const previewBody = el('div');
        body.append(el('div', { class: 'card' }, [
          el('h3', { text: 'Abandoned uploads' }),
          el('p', { class: 'hint', text: 'Only unattached uploads older than 24 hours can be cleaned. Nothing is deleted before you inspect a preview and confirm.' }),
          el('div', { class: 'form-actions' }, [
            el('button', {
              class: 'btn', type: 'button', text: 'Preview cleanup',
              onClick: () => run(async () => {
                clear(previewBody);
                previewBody.append(stateBlock('loading'));
                const data2 = await api('/admin/storage/preview', { method: 'POST', body: {} });
                plan = data2;
                clear(previewBody);
                if (!data2.items || data2.items.length === 0) {
                  previewBody.append(stateBlock('empty', 'Nothing is eligible for cleanup.'));
                  return;
                }
                previewBody.append(el('p', { class: 'row-body', text: `${data2.items.length} item(s) · ${bytes(data2.bytes)}` }));
                const rows = el('ul', { class: 'rows' });
                for (const item of data2.items) {
                  rows.append(el('li', { class: 'row' }, [
                    el('div', { class: 'row-head' }, [el('span', { class: 'row-title', text: bytes(item.bytes) })]),
                    el('div', { class: 'row-meta' }, [el('span', { text: `Created: ${when(item.created_at)}` }), el('span', { text: `Account: ${shortId(item.user_id)}` })]),
                    techDetail({ id: item.id, plan_id: data2.plan_id }),
                  ]));
                }
                previewBody.append(rows);
                previewBody.append(el('div', { class: 'form-actions' }, [
                  el('button', {
                    class: 'btn btn-danger', type: 'button', text: 'Delete listed uploads',
                    onClick: () => confirmCleanup(data2, previewBody),
                  }),
                ]));
              }),
            }),
          ]),
          previewBody,
        ]));
      })
      .catch((error) => { clear(body); const info = describe(error); body.append(stateBlock('error', info.message, load)); });
  };

  async function confirmCleanup(current, container) {
    const confirmed = await confirmDialog({
      title: 'Delete these uploads?',
      body: `This permanently removes ${current.items.length} unattached upload${current.items.length === 1 ? '' : 's'} (${bytes(current.bytes)}). Only this preview can be executed, and it can only be executed once.`,
      confirmLabel: 'Delete',
      danger: true,
    });
    if (!confirmed) return;
    run(async () => {
      const result = await api('/admin/storage/cleanup', { method: 'POST', body: { plan_id: current.plan_id } });
      banner(`Deleted ${result.deleted} · skipped ${result.skipped} · failed ${result.failed}`);
      clear(container);
      load();
    });
  }

  load();
  return card;
};

/* ------------------------------------------------------ server settings */

const POLICY_FIELDS = [
  ['name', 'Server name', 'text', 'Shown publicly in /server-info.'],
  ['operator', 'Operator', 'text', 'Shown publicly in /server-info.'],
  ['contact', 'Support contact', 'text', 'Shown publicly in /server-info.'],
  ['rules', 'Server rules', 'textarea', 'Shown publicly in /server-info.'],
  ['retention', 'Data retention policy', 'textarea', 'Shown publicly in /server-info.'],
  ['max_file_bytes', 'Maximum encrypted file size (bytes)', 'number', 'Not public. Between 4097 and 104861696.'],
  ['user_quota_bytes', 'Quota per account (bytes, 0 = unlimited)', 'number', 'Not public.'],
  ['storage_budget_bytes', 'Attachment storage budget (bytes, 0 = unlimited)', 'number', 'Not public.'],
];

SECTIONS.settings = () => {
  const card = el('div', { class: 'card' }, [
    el('div', { class: 'card-head' }, [el('h2', { text: 'Server settings' }), el('span', { class: 'chip chip-accent', text: 'Owner' })]),
  ]);
  const body = el('div');
  card.append(body);
  const controls = {};

  const load = () => {
    clear(body);
    body.append(stateBlock('loading'));
    api('/admin/server-policy')
      .then((data) => {
        clear(body);
        body.append(el('div', { class: 'card' }, [
          el('h3', { text: 'Public server card' }),
          el('p', { class: 'hint', text: 'These values are served publicly by /server-info and must not contain private information.' }),
          el('div', { class: 'grid grid-2' }, POLICY_FIELDS.slice(0, 5).map(([key, label, kind, hint]) => {
            const control = kind === 'textarea' ? el('textarea', { id: `policy-${key}` }) : input(`policy-${key}`, {});
            control.value = data[key] ?? '';
            controls[key] = control;
            return field(label, control, hint);
          })),
        ]));
        body.append(el('div', { class: 'card' }, [
          el('h3', { text: 'Upload limits, quota and budget' }),
          el('div', { class: 'grid grid-2' }, POLICY_FIELDS.slice(5).map(([key, label, , hint]) => {
            const control = input(`policy-${key}`, { inputmode: 'numeric' });
            control.value = String(data[key] ?? 0);
            controls[key] = control;
            return field(label, control, hint);
          })),
        ]));
        body.append(el('div', { class: 'form-actions' }, [
          el('button', {
            class: 'btn btn-primary', type: 'button', text: 'Save settings',
            onClick: () => run(async () => {
              const payload = {};
              for (const [key, , kind] of POLICY_FIELDS) {
                const raw = controls[key].value;
                if (kind === 'number') {
                  const value = Number(raw);
                  if (!Number.isFinite(value) || value < 0) throw new ApiError(400, 'validation_error', 'Numeric settings must be zero or a positive number.');
                  payload[key] = value;
                } else {
                  payload[key] = raw;
                }
              }
              if (!String(payload.name).trim()) throw new ApiError(400, 'validation_error', 'A server name is required.');
              await api('/admin/server-policy', { method: 'POST', body: payload });
              banner('Server settings saved');
              load();
            }),
          }),
          el('button', { class: 'btn btn-quiet', type: 'button', text: 'Discard changes', onClick: load }),
        ]));
      })
      .catch((error) => { clear(body); const info = describe(error); body.append(stateBlock('error', info.message, load)); });
  };

  load();
  return card;
};

/* Changing sign-in protection requires fresh proof in a native client. The
 * browser shows the server-owned policy and never collects an offline key. */
SECTIONS.security = () => {
  const card = el('div', { class: 'card' }, [el('h2', { text: 'Your administrator account' })]);
  const body = el('div');
  card.append(body);
  const load = () => {
    clear(body);
    body.append(stateBlock('loading'));
    api('/admin/security').then((data) => {
      clear(body);
      const policy = {
        authenticator: ['Authenticator', 'Your account password and an authenticator code are required. The administrator key is not required.'],
        paper_key: ['Administrator key', 'Your account password and your saved administrator key are required. An authenticator code is not required.'],
        both: ['Both methods', 'Your account password, authenticator code and saved administrator key are all required.'],
      }[data.auth_policy];
      if (!policy) {
        body.append(stateBlock('error', 'The server returned an unsupported sign-in policy. Update the console before changing protection.', load));
        return;
      }
      body.append(el('div', { class: 'security-policy' }, [
        el('span', { class: 'eyebrow', text: 'CURRENT SIGN-IN METHOD' }),
        el('h3', { text: policy[0] }), el('p', { text: policy[1] }),
      ]));
      const methods = [
        ['Authenticator', data.totp_configured, data.auth_policy !== 'paper_key', 'A time-based code from your authenticator. Recovery codes can be used if the authenticator is unavailable.'],
        ['Administrator key', data.paper_key_configured, data.auth_policy !== 'authenticator', 'A separate saved key that proves administrator access. It does not decrypt your conversations.'],
      ];
      body.append(el('div', { class: 'security-methods' }, methods.map(([label, configured, required, hint]) => el('div', { class: 'security-method' }, [
        el('h3', { text: label }),
        el('span', { class: `chip${required ? ' chip-ok' : ''}`, text: required ? 'Required at sign-in' : configured ? 'Saved · not required' : 'Not configured' }),
        el('p', { text: hint }),
      ]))));
      body.append(el('div', { class: 'security-policy' }, [
        el('h3', { text: 'Want to change your methods?' }),
        el('p', { text: 'Open UNYRI Admin Console on Windows → «Мой вход» → «Изменить способ входа». Confirm using your current method before choosing a new one. Every browser administration session will lock after the change.' }),
        el('p', { text: 'At least one additional protection method stays enabled. Your account password is always required for a new sign-in.' }),
      ]));
    }).catch((error) => { clear(body); body.append(stateBlock('error', describe(error).message, load)); });
  };
  load();
  return card;
};

/* ============================== lifecycle ============================== */

function expire(message) {
  state.role = null;
  state.csrf = null;
  state.params = {};
  state.counts = {};
  state.sessionEpoch += 1;
  for (const list of [...activeLists]) list.dispose();
  closeModal(false);
  closeLightbox();
  clear($('view'));
  clear($('nav-list'));
  $('banner').hidden = true;
  $('app').hidden = true;
  $('gate').hidden = false;
  $('gate-steps').hidden = false;
  $('gate-retry').hidden = false;
  $('gate-message').textContent = message
    || 'This administration session ended. Sign in again using UNYRI Admin Console on Windows or administration in UNYRI on your phone.';
}

function start(role) {
  state.sessionEpoch += 1;
  state.role = role;
  $('server-origin').textContent = location.origin;
  if (!state.csrf) state.csrf = readCookie(CSRF_COOKIE);
  $('gate').hidden = true;
  $('app').hidden = false;
  go('dashboard');
}

function readCookie(name) {
  for (const part of document.cookie.split(';')) {
    const [key, value] = part.trim().split('=');
    if (key === name) return decodeURIComponent(value || '');
  }
  return null;
}

/* The handoff is a single-use credential and the console never keeps it: it is
 * removed from the address bar before anything else happens, so it cannot be
 * recovered from history, a bookmark or a shared link. */
async function boot() {
  const params = new URLSearchParams(location.hash.replace(/^#/, ''));
  const handoff = params.get('handoff');
  if (location.hash) history.replaceState(null, '', location.pathname);

  if (handoff) {
    $('gate-message').textContent = 'Opening administration console…';
    try {
      const session = await api('/administration/handoff', { method: 'POST', body: { handoff } });
      state.csrf = session.csrf;
      start(session.role);
      return;
    } catch (error) {
      const info = describe(error);
      expire(error instanceof ApiError && error.status === 404
        ? 'This console link was already used or has expired. Open the console again from UNYRI.'
        : info.message);
      return;
    }
  }

  try {
    const session = await api('/administration/session');
    if (session.authenticated) { start(session.role); return; }
  } catch { /* fall through to the gate */ }
  $('gate-message').textContent = 'Open a secure session from UNYRI Admin Console on Windows or from UNYRI on your phone.';
  $('gate-steps').hidden = false;
  $('gate-retry').hidden = false;
}

/* Activity refreshes the console session's idle window. There is deliberately no
 * timer and no polling: an unattended tab must go idle, which is exactly what
 * the idle window is for. */
let lastTouch = 0;
function touch() {
  const now = Date.now();
  if (now - lastTouch < 60_000 || !state.role) return;
  lastTouch = now;
  api('/administration/session')
    .then((session) => { if (session && session.authenticated === false) expire(); })
    .catch(() => {});
}
for (const event of ['pointerdown', 'keydown']) {
  window.addEventListener(event, touch, { passive: true });
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { state.backgroundAt = Date.now(); return; }
  if (state.backgroundAt && Date.now() - state.backgroundAt > SESSION_IDLE_MINUTES * 60_000) {
    expire();
  }
  state.backgroundAt = null;
});

$('gate-retry').addEventListener('click', () => location.reload());
$('refresh').addEventListener('click', () => { state.counts = {}; go(state.section, state.params); });
$('close-console').addEventListener('click', () => run(async () => {
  await api('/administration/session', { method: 'DELETE' });
  expire('The console was closed. Unlock administration in UNYRI to open it again.');
}));
$('nav-toggle').addEventListener('click', () => {
  const nav = $('nav');
  const open = nav.hidden;
  nav.hidden = !open;
  $('nav-toggle').setAttribute('aria-expanded', String(open));
});
$('modal-cancel').addEventListener('click', () => closeModal(false));
$('modal-confirm').addEventListener('click', () => closeModal(true));
$('modal').addEventListener('click', (event) => { if (event.target === $('modal')) closeModal(false); });
$('lightbox-close').addEventListener('click', () => closeLightbox());
$('lightbox').addEventListener('click', (event) => { if (event.target === $('lightbox')) closeLightbox(); });
document.addEventListener('keydown', (event) => {
  trapModalFocus(event);
  if (event.key !== 'Escape') return;
  if (!$('lightbox').hidden) { closeLightbox(); return; }
  if (!$('modal').hidden) closeModal(false);
});

/* Mobile default: the section list is collapsed until asked for. */
const mobileNav = window.matchMedia('(max-width: 899px)');
function syncNavigation(event) {
  $('nav').hidden = event.matches;
  $('nav-toggle').setAttribute('aria-expanded', String(!event.matches));
}
syncNavigation(mobileNav);
mobileNav.addEventListener('change', syncNavigation);

boot();
