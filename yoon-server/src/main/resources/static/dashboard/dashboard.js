// Yoon operator dashboard. Plain ES module, no dependencies, no build step.
// Every value from the API is written with textContent / DOM nodes — never as HTML.
// The admin token lives in sessionStorage only and travels in the Authorization header.

const TOKEN_KEY = 'yoon.adminToken';
const PAGE_SIZE = 25;

const STATUSES = {
    payments: ['created', 'pending', 'succeeded', 'failed', 'expired'],
    refunds: ['created', 'pending', 'unknown', 'refunded', 'failed'],
    payouts: ['created', 'processing', 'unknown', 'paid', 'failed'],
};
const GOOD = new Set(['succeeded', 'refunded', 'paid', 'delivered', 'apply']);
const BAD = new Set(['failed', 'expired', 'dead']);
const WARN = new Set(['unknown', 'pending', 'processing', 'ignore']);

const $ = (id) => document.getElementById(id);

const state = {
    view: 'payments',
    app: null,
    cursors: [], // cursor stack: [] = newest page
    next: null,
};

// ---- storage ---------------------------------------------------------------------------------

function token() {
    try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
}
function setToken(t) {
    try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch { /* private mode */ }
}

// ---- API -------------------------------------------------------------------------------------

class ApiError extends Error {
    constructor(status, code, detail) {
        super(detail || code || `HTTP ${status}`);
        this.status = status;
        this.code = code;
    }
}

async function api(path, options = {}) {
    const t = token();
    if (!t) throw new ApiError(401, 'unauthorized', 'Not signed in');
    const res = await fetch(path, {
        method: options.method || 'GET',
        headers: { Authorization: `Bearer ${t}`, Accept: 'application/json' },
        credentials: 'omit',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
    });
    let body = null;
    try { body = await res.json(); } catch { /* empty or non-JSON */ }
    if (!res.ok) throw new ApiError(res.status, body && body.code, body && body.detail);
    return body;
}

function query(params) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== '') q.set(k, String(v));
    const s = q.toString();
    return s ? `?${s}` : '';
}

const appPath = (rest) => `/admin/v1/applications/${encodeURIComponent(state.app)}${rest}`;

// ---- DOM helpers (text only) -----------------------------------------------------------------

function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
        if (k === 'class') node.className = v;
        else if (k === 'onclick') node.addEventListener('click', v);
        else node.setAttribute(k, v);
    }
    for (const c of children) {
        if (c === null || c === undefined) continue;
        node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
}

function badge(value) {
    if (value === null || value === undefined) return '';
    const v = String(value);
    const cls = GOOD.has(v) ? 'ok' : BAD.has(v) ? 'bad' : WARN.has(v) ? 'warn' : '';
    return el('span', { class: `badge ${cls}` }, v);
}

/** Minor units → major-unit string without floating point. Currency digits from Intl (XOF: 0). */
function money(amount, currency) {
    if (amount === null || amount === undefined) return '';
    let digits = 2;
    try { digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits; } catch { /* unknown code */ }
    const negative = String(amount).startsWith('-');
    let s = String(amount).replace('-', '').padStart(digits + 1, '0');
    const whole = s.slice(0, s.length - digits).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    const frac = digits ? `.${s.slice(s.length - digits)}` : '';
    return `${negative ? '-' : ''}${whole}${frac} ${currency}`;
}

function when(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? String(iso) : d.toLocaleString();
}

function table(caption, columns, rows) {
    if (!rows.length) return el('p', { class: 'empty' }, `No ${caption.toLowerCase()}.`);
    const head = el('tr', {}, ...columns.map((c) => el('th', { scope: 'col' }, c.label)));
    const body = rows.map((r) => el('tr', {}, ...columns.map((c) => {
        const v = c.value(r);
        // data-label: on phones each row becomes a card and the label is shown next to the value (CSS).
        return el('td', { class: c.class || '', 'data-label': c.label }, v instanceof Node ? v : v ?? '');
    })));
    return el('div', { class: 'table-wrap' }, el('table', {}, el('caption', {}, caption), el('thead', {}, head), el('tbody', {}, ...body)));
}

function idButton(kind, id) {
    return el('button', { type: 'button', class: 'link', title: 'Show status history', onclick: () => showHistory(kind, id) }, id);
}

function message(text) {
    const m = $('message');
    m.textContent = text || '';
    m.hidden = !text;
}

// ---- views -----------------------------------------------------------------------------------

const failure = (r) => (r.failure ? `${r.failure.code}: ${r.failure.message}` : '');

const VIEWS = {
    payments: {
        scope: 'app',
        load: (cursor, status) => api(appPath(`/payments${query({ status, starting_after: cursor, limit: PAGE_SIZE })}`)),
        render: (page) => table('Payments', [
            { label: 'Id', value: (r) => idButton('payments', r.id) },
            { label: 'Status', value: (r) => badge(r.status) },
            { label: 'Amount', class: 'num', value: (r) => money(r.amount, r.currency) },
            { label: 'Refunded', class: 'num', value: (r) => (r.amount_refunded ? money(r.amount_refunded, r.currency) : '') },
            { label: 'Method', value: (r) => r.method },
            { label: 'Provider', value: (r) => r.provider },
            { label: 'Customer', value: (r) => (r.customer && r.customer.phone) || '' },
            { label: 'Reference', value: (r) => r.reference },
            { label: 'Created', value: (r) => when(r.created_at) },
            { label: 'Failure', class: 'wrap', value: failure },
        ], page.data),
    },
    refunds: {
        scope: 'app',
        load: (cursor, status) => api(appPath(`/refunds${query({ status, starting_after: cursor, limit: PAGE_SIZE })}`)),
        render: (page) => table('Refunds', [
            { label: 'Id', value: (r) => idButton('refunds', r.id) },
            { label: 'Status', value: (r) => badge(r.status) },
            { label: 'Amount', class: 'num', value: (r) => money(r.amount, r.currency) },
            { label: 'Payment', value: (r) => idButton('payments', r.payment_id) },
            { label: 'Provider', value: (r) => r.provider },
            { label: 'Reason', class: 'wrap', value: (r) => r.reason },
            { label: 'Created', value: (r) => when(r.created_at) },
            { label: 'Failure', class: 'wrap', value: failure },
        ], page.data),
    },
    payouts: {
        scope: 'app',
        load: (cursor, status) => api(appPath(`/payouts${query({ status, starting_after: cursor, limit: PAGE_SIZE })}`)),
        render: (page) => payoutTable('Payouts', page.data, true),
    },
    review: {
        scope: 'instance',
        load: (cursor) => api(`/admin/v1/payouts${query({ needs_review: true, starting_after: cursor, limit: PAGE_SIZE })}`),
        render: (page) => el('div', {},
            el('p', { class: 'muted' }, 'Check each payout on the provider’s dashboard, then record the outcome with the operator API (see the runbook). The dashboard does not resolve payouts.'),
            payoutTable('Payouts needing review (all applications)', page.data, false)),
    },
    dead: {
        scope: 'instance',
        load: (cursor) => api(`/admin/v1/dead-letters${query({ starting_after: cursor, limit: PAGE_SIZE })}`),
        render: (page) => table('Dead letters (all applications)', [
            { label: 'Event', value: (r) => r.id },
            { label: 'Type', value: (r) => r.type },
            { label: 'Resource', value: (r) => `${r.resource_type} ${r.resource_id}` },
            { label: 'Attempts', class: 'num', value: (r) => r.delivery.attempts },
            { label: 'Last answer', value: (r) => (r.delivery.last_status_code ?? '') },
            { label: 'Last error', class: 'wrap', value: (r) => r.delivery.last_error },
            { label: 'Created', value: (r) => when(r.created_at) },
            { label: 'Action', value: (r) => el('button', { type: 'button', class: 'secondary', onclick: (e) => replay(r.id, e.currentTarget) }, 'Replay') },
        ], page.data),
    },
    ledger: {
        scope: 'app',
        load: async (cursor) => {
            const [balances, entries] = await Promise.all([
                cursor ? Promise.resolve(null) : api(appPath('/balances')),
                api(appPath(`/ledger/entries${query({ starting_after: cursor, limit: PAGE_SIZE })}`)),
            ]);
            return { ...entries, balances };
        },
        render: (page) => el('div', {},
            page.balances ? el('div', {},
                el('p', { class: 'muted' }, page.balances.note),
                table('Balances', [
                    { label: 'Account', value: (r) => r.account },
                    { label: 'Provider', value: (r) => r.provider },
                    { label: 'Balance', class: 'num', value: (r) => money(r.amount, r.currency) },
                ], page.balances.data),
                el('br')) : null,
            table('Ledger entries (positive = debit, negative = credit)', [
                { label: 'Entry', class: 'num', value: (r) => r.id },
                { label: 'Posting', class: 'num', value: (r) => r.posting_id },
                { label: 'Account', value: (r) => r.account },
                { label: 'Amount', class: 'num', value: (r) => money(r.amount, r.currency) },
                { label: 'Description', class: 'wrap', value: (r) => r.description },
                { label: 'Created', value: (r) => when(r.created_at) },
            ], page.data)),
    },
};

function payoutTable(caption, rows, linkHistory) {
    return table(caption, [
        { label: 'Id', value: (r) => (linkHistory ? idButton('payouts', r.id) : r.id) },
        { label: 'Status', value: (r) => badge(r.status) },
        { label: 'Review', value: (r) => (r.needs_review ? badge('unknown') : '') },
        { label: 'Amount', class: 'num', value: (r) => money(r.amount, r.currency) },
        { label: 'Method', value: (r) => r.method },
        { label: 'Provider', value: (r) => r.provider },
        { label: 'Recipient', value: (r) => (r.recipient && r.recipient.phone) || '' },
        { label: 'Reference', value: (r) => r.reference },
        { label: 'Created', value: (r) => when(r.created_at) },
        { label: 'Failure', class: 'wrap', value: failure },
    ], rows);
}

// ---- actions ---------------------------------------------------------------------------------

async function replay(id, button) {
    if (!window.confirm(`Deliver event ${id} to the application's webhook URL again?`)) return;
    button.disabled = true;
    try {
        await api(`/admin/v1/dead-letters/${encodeURIComponent(id)}/replay`, { method: 'POST' });
        button.replaceWith(el('span', { class: 'badge ok' }, 'queued'));
    } catch (e) {
        button.disabled = false;
        handle(e);
    }
}

async function showHistory(kind, id) {
    const box = $('history');
    const content = $('history-content');
    $('history-title').textContent = `Status history of ${id}`;
    content.replaceChildren(el('p', { class: 'muted' }, 'Loading…'));
    box.hidden = false;
    try {
        const events = await api(appPath(`/${kind}/${encodeURIComponent(id)}/events`));
        content.replaceChildren(table('Status changes, oldest first', [
            { label: 'When', value: (r) => when(r.created_at) },
            { label: 'From', value: (r) => r.from_status },
            { label: 'To', value: (r) => badge(r.to_status) },
            { label: 'Decision', value: (r) => badge(r.decision) },
            { label: 'Cause', value: (r) => r.cause },
            { label: 'Provider status', value: (r) => r.raw_status },
            { label: 'Detail', class: 'wrap', value: (r) => r.detail },
        ], events));
    } catch (e) {
        content.replaceChildren(el('p', { class: 'message' }, e.message));
    }
    box.scrollIntoView({ behavior: 'smooth', block: 'start' });
    $('history-close').focus();
}

// ---- rendering -------------------------------------------------------------------------------

function fillStatus() {
    const select = $('status');
    const options = STATUSES[state.view];
    $('toolbar').querySelector('label').hidden = !options;
    select.hidden = !options;
    select.replaceChildren(el('option', { value: '' }, 'Any'), ...(options || []).map((s) => el('option', { value: s }, s)));
}

async function render() {
    const view = VIEWS[state.view];
    const cursor = state.cursors[state.cursors.length - 1] || null;
    const appName = $('app').selectedOptions[0] ? $('app').selectedOptions[0].textContent : '';
    $('scope').textContent = view.scope === 'app' ? `Application: ${appName}` : 'All applications';
    $('content').replaceChildren(el('p', { class: 'muted' }, 'Loading…'));
    message('');
    try {
        const page = await view.load(cursor, $('status').value || null);
        $('content').replaceChildren(view.render(page));
        state.next = page.has_more ? page.next_cursor : null;
    } catch (e) {
        $('content').replaceChildren();
        state.next = null;
        handle(e);
    }
    $('next').disabled = !state.next;
    $('first').disabled = state.cursors.length === 0;
}

function select(viewName) {
    state.view = viewName;
    state.cursors = [];
    for (const tab of $('tabs').querySelectorAll('[role="tab"]')) {
        tab.setAttribute('aria-selected', String(tab.dataset.view === viewName));
        tab.tabIndex = tab.dataset.view === viewName ? 0 : -1;
    }
    fillStatus();
    $('history').hidden = true;
    render();
}

function handle(e) {
    if (e instanceof ApiError && e.status === 401) {
        signOut('The admin token was refused. Sign in again.');
        return;
    }
    message(e instanceof ApiError && e.status === 404 && e.code === 'resource_not_found' && /admin API is disabled/.test(e.message)
        ? 'The operator API is disabled on this instance (YOON_ADMIN_TOKEN is not set).'
        : e.message || 'Request failed');
}

function signOut(why) {
    setToken(null);
    $('session').hidden = true;
    $('app-view').hidden = true;
    $('login').hidden = false;
    $('token').value = '';
    message(why || '');
    $('token').focus();
}

async function signIn() {
    let apps;
    try {
        apps = await api('/admin/v1/applications');
    } catch (e) {
        handle(e);
        return;
    }
    $('app').replaceChildren(...apps.data.map((a) => el('option', { value: a.id }, a.name)));
    state.app = apps.data.length ? apps.data[0].id : null;
    $('login').hidden = true;
    $('session').hidden = false;
    $('app-view').hidden = false;
    message(apps.data.length ? '' : 'No applications yet. Create one with "yoon apps create <name>".');
    select(state.view);
}

// ---- wiring ----------------------------------------------------------------------------------

$('login-form').addEventListener('submit', (e) => {
    e.preventDefault();
    setToken($('token').value.trim());
    $('token').value = '';
    signIn();
});
$('signout').addEventListener('click', () => signOut(''));
$('app').addEventListener('change', () => { state.app = $('app').value; state.cursors = []; $('history').hidden = true; render(); });
$('status').addEventListener('change', () => { state.cursors = []; render(); });
$('refresh').addEventListener('click', () => render());
$('next').addEventListener('click', () => { if (state.next) { state.cursors.push(state.next); render(); } });
$('first').addEventListener('click', () => { state.cursors = []; render(); });
$('history-close').addEventListener('click', () => { $('history').hidden = true; });
$('tabs').addEventListener('click', (e) => {
    const tab = e.target.closest('[role="tab"]');
    if (tab) select(tab.dataset.view);
});
$('tabs').addEventListener('keydown', (e) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
    const tabs = [...$('tabs').querySelectorAll('[role="tab"]')];
    const i = tabs.findIndex((t) => t.dataset.view === state.view);
    const next = tabs[(i + (e.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length];
    select(next.dataset.view);
    next.focus();
});

if (token()) signIn(); else $('token').focus();
