// Yoon payment link page (ADR-0030). One vanilla ES module, no build, no third-party code.
// Data from the server is written with textContent only. "Pay" creates an ordinary hosted payment
// and sends the customer to its checkout page, where the method is chosen.

const TEXT = {
    en: {
        secure: 'Secure payment', toPay: 'Amount to pay', yourAmount: 'Amount', pay: 'Pay',
        footer: 'Payment processed by Yoon', loading: 'Loading…', starting: 'Opening the payment…',
        notFound: 'This payment link does not exist.', inactive: 'This payment link is no longer active.',
        expired: 'This payment link has expired.', used_up: 'This payment link has already been used.',
        network: 'Connection problem. Check your connection and try again.',
        tooMany: 'Too many requests. Wait a moment and try again.', invalid: 'Check the amount.',
        enterAmount: 'Enter the amount to pay.', between: 'Between {min} and {max}.', atLeast: 'At least {min}.',
        atMost: 'At most {max}.', otherLang: 'Français',
    },
    fr: {
        secure: 'Paiement sécurisé', toPay: 'Montant à payer', yourAmount: 'Montant', pay: 'Payer',
        footer: 'Paiement traité par Yoon', loading: 'Chargement…', starting: 'Ouverture du paiement…',
        notFound: "Ce lien de paiement n'existe pas.", inactive: "Ce lien de paiement n'est plus actif.",
        expired: 'Ce lien de paiement a expiré.', used_up: 'Ce lien de paiement a déjà été utilisé.',
        network: 'Problème de connexion. Vérifiez votre connexion et réessayez.',
        tooMany: 'Trop de requêtes. Patientez un instant puis réessayez.', invalid: 'Vérifiez le montant.',
        enterAmount: 'Saisissez le montant à payer.', between: 'Entre {min} et {max}.', atLeast: 'Au moins {min}.',
        atMost: 'Au plus {max}.', otherLang: 'English',
    },
};

const $ = (id) => document.getElementById(id);
const code = decodeURIComponent(location.pathname.split('/').filter(Boolean)[1] || '');
// One attempt per page view: a double click or a retry pays one payment (server-side idempotency).
const attempt = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random().toString(16).slice(2);

const preferred = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en'])
    .map((l) => l.slice(0, 2).toLowerCase()).find((l) => l === 'fr' || l === 'en');
let lang = preferred || 'en';
let view = null;
let message = null;

const t = (key) => TEXT[lang][key] || TEXT.en[key] || key;

function digits(currency) {
    try {
        return new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits;
    } catch (e) {
        return 2;
    }
}

/** Minor units → display string without floating point: 5000 XOF → "5 000 XOF". */
function formatAmount(amount, currency) {
    const d = digits(currency);
    const s = String(amount).padStart(d + 1, '0');
    const whole = d > 0 ? s.slice(0, -d) : s;
    const frac = d > 0 ? s.slice(-d) : '';
    const locale = lang === 'fr' ? 'fr-FR' : 'en-US';
    const sep = new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal')?.value || '.';
    return new Intl.NumberFormat(locale, { maximumFractionDigits: 0 }).format(BigInt(whole)) + (frac ? sep + frac : '') + ' ' + currency;
}

/** What the customer typed → minor units, or null. "5 000", "5000", "12,50" (2-digit currencies). */
function parseAmount(text, currency) {
    const d = digits(currency);
    const clean = text.replace(/[\s  ]/g, '').replace(',', '.');
    const re = d > 0 ? new RegExp(`^\\d+(\\.\\d{1,${d}})?$`) : /^\d+$/;
    if (!re.test(clean)) return null;
    const [whole, frac = ''] = clean.split('.');
    const minor = BigInt(whole) * 10n ** BigInt(d) + BigInt((frac + '0'.repeat(d)).slice(0, d) || '0');
    return minor > 0n && minor <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(minor) : null;
}

function setStatus(key, kind) {
    message = key ? { key, kind } : null;
    $('status').className = 'status' + (kind ? ' ' + kind : '');
    $('status').textContent = key ? t(key) : '';
}

function showError(text) {
    $('form-error').hidden = !text;
    $('form-error').textContent = text || '';
}

function boundsText() {
    const min = view.min_amount == null ? null : formatAmount(view.min_amount, view.currency);
    const max = view.max_amount == null ? null : formatAmount(view.max_amount, view.currency);
    if (min && max) return t('between').replace('{min}', min).replace('{max}', max);
    if (min) return t('atLeast').replace('{min}', min);
    if (max) return t('atMost').replace('{max}', max);
    return '';
}

function render() {
    if (!view) return;
    $('merchant').textContent = view.merchant || t('secure');
    $('description').textContent = view.description;
    const fixed = view.amount != null;
    $('amount').hidden = !fixed;
    if (fixed) $('amount').textContent = formatAmount(view.amount, view.currency);
    $('amount-field').hidden = fixed;
    $('bounds').textContent = fixed ? '' : boundsText();
    $('bounds').hidden = fixed || !$('bounds').textContent;
    const open = view.state === 'open';
    $('summary').hidden = false;
    $('pay-form').hidden = !open;
    if (!open) setStatus(view.state, 'bad');
    document.title = view.description;
}

function applyLanguage() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    $('lang').textContent = (lang === 'fr' ? 'en' : 'fr').toUpperCase();
    $('lang').setAttribute('aria-label', t('otherLang'));
    if (message) setStatus(message.key, message.kind);
    render();
}

async function call(path, body) {
    try {
        const res = await fetch(path, {
            method: body ? 'POST' : 'GET',
            headers: { 'Accept': 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
            body: body ? JSON.stringify(body) : undefined,
            credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
        });
        let json = null;
        try { json = await res.json(); } catch (e) { json = null; }
        return { ok: res.ok, status: res.status, body: json };
    } catch (e) {
        return { ok: false, status: 0, body: null };
    }
}

async function load() {
    const r = await call(`/checkout/api/links/${encodeURIComponent(code)}`);
    if (r.status === 404) { setStatus('notFound', 'bad'); return; }
    if (!r.ok) { setStatus(r.status === 429 ? 'tooMany' : 'network', 'bad'); return; }
    view = r.body;
    setStatus(null);
    render();
}

async function pay(event) {
    event.preventDefault();
    if (!view || view.state !== 'open') return;
    const body = { attempt };
    if (view.amount == null) {
        const amount = parseAmount($('amount-input').value, view.currency);
        if (amount == null) { showError(t('enterAmount')); $('amount-input').focus(); return; }
        if ((view.min_amount != null && amount < view.min_amount) || (view.max_amount != null && amount > view.max_amount)) {
            showError(boundsText()); $('amount-input').focus(); return;
        }
        body.amount = amount;
    }
    showError(null);
    $('pay').disabled = true;
    setStatus('starting', 'wait');
    const r = await call(`/checkout/api/links/${encodeURIComponent(code)}/payments`, body);
    if (r.ok && r.body && r.body.checkout_url) {
        location.assign(r.body.checkout_url);
        return;
    }
    $('pay').disabled = false;
    const c = r.body && r.body.code;
    if (c && c.startsWith('link_')) { view.state = c.slice(5); setStatus(view.state, 'bad'); render(); return; }
    setStatus(null);
    showError(r.status === 400 ? t('invalid') : r.status === 429 ? t('tooMany') : t('network'));
}

$('lang').addEventListener('click', () => { lang = lang === 'fr' ? 'en' : 'fr'; applyLanguage(); });
$('pay-form').addEventListener('submit', pay);

applyLanguage();
setStatus('loading', 'wait');
if (!code) setStatus('notFound', 'bad'); else load();
