// Yoon payment receipt (ADR-0027). One vanilla ES module, no build, no third-party code.
// Data from the server is written with textContent only. The receipt token comes from this
// page's URL and is sent only to this origin, in the Yoon-Checkout-Token header.

const TEXT = {
    en: {
        title: 'Payment receipt', notTax: 'Payment receipt — not a tax invoice.', paidOn: 'Paid on',
        customer: 'Customer', plan: 'Plan', period: 'Period', method: 'Paid with', reference: 'Reference',
        total: 'Total paid', discount: 'Discount', coupon: 'coupon', withCoupon: 'Coupon', print: 'Print or save as PDF', footer: 'Payment processed by Yoon',
        loading: 'Loading…', notFound: 'This receipt link is invalid.', tooMany: 'Too many requests. Wait a moment and try again.',
        network: 'Connection problem. Check your connection and try again.', otherLang: 'Français',
    },
    fr: {
        title: 'Reçu de paiement', notTax: 'Reçu de paiement — ceci n’est pas une facture fiscale.', paidOn: 'Payé le',
        customer: 'Client', plan: 'Formule', period: 'Période', method: 'Payé avec', reference: 'Référence',
        total: 'Total payé', discount: 'Remise', coupon: 'code', withCoupon: 'Code promo', print: 'Imprimer ou enregistrer en PDF', footer: 'Paiement traité par Yoon',
        loading: 'Chargement…', notFound: 'Ce lien de reçu est invalide.', tooMany: 'Trop de requêtes. Patientez un instant puis réessayez.',
        network: 'Problème de connexion. Vérifiez votre connexion et réessayez.', otherLang: 'English',
    },
};

const METHOD_NAMES = {
    wave: 'Wave', orange_money: 'Orange Money', free_money: 'Free Money', pispi: 'PI-SPI',
    mtn: 'MTN Mobile Money', moov: 'Moov Money', card: { en: 'Bank card', fr: 'Carte bancaire' },
};

const $ = (id) => document.getElementById(id);
const id = decodeURIComponent(location.pathname.split('/').filter(Boolean)[2] || '');
const token = new URLSearchParams(location.search).get('t') || '';

const preferred = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en'])
    .map((l) => l.slice(0, 2).toLowerCase()).find((l) => l === 'fr' || l === 'en');
let lang = preferred || 'en';
let view = null;
let message = null;

const t = (key) => TEXT[lang][key] || TEXT.en[key] || key;
const locale = () => (lang === 'fr' ? 'fr-FR' : 'en-GB');

function methodName(m) {
    const known = METHOD_NAMES[m];
    if (known) return typeof known === 'string' ? known : known[lang];
    return m.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

/** Minor units → display string, without floating point: 5000 XOF → "5 000 XOF". */
function formatAmount(amount, currency) {
    let digits = 2;
    try {
        digits = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits;
    } catch (e) { /* unknown currency code: keep 2 */ }
    const s = String(amount).padStart(digits + 1, '0');
    const whole = digits > 0 ? s.slice(0, -digits) : s;
    const frac = digits > 0 ? s.slice(-digits) : '';
    const nf = new Intl.NumberFormat(locale(), { maximumFractionDigits: 0 });
    const decimal = new Intl.NumberFormat(locale()).formatToParts(1.5).find((p) => p.type === 'decimal')?.value || '.';
    return nf.format(BigInt(whole)) + (frac ? decimal + frac : '') + ' ' + currency;
}

const date = (iso) => (iso ? new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'long', day: 'numeric' }) : '');
const dateTime = (iso) => (iso ? new Date(iso).toLocaleString(locale(), { dateStyle: 'long', timeStyle: 'short' }) : '');

function setStatus(key, kind) {
    message = key ? { key, kind } : null;
    $('status').className = 'status' + (kind ? ' ' + kind : '');
    $('status').textContent = key ? t(key) : '';
}

function row(label, value, cls) {
    const tr = document.createElement('tr');
    if (cls) tr.className = cls;
    const a = document.createElement('td');
    a.textContent = label;
    const b = document.createElement('td');
    b.className = 'num';
    b.textContent = value;
    tr.appendChild(a);
    tr.appendChild(b);
    return tr;
}

function show(fieldId, value) {
    $(fieldId).textContent = value || '';
    $(fieldId).hidden = !value;
    const label = $(fieldId + '-label');
    if (label) label.hidden = !value;
}

function render() {
    if (!view) return;
    $('merchant').textContent = view.merchant || t('title');
    $('number').textContent = view.number;
    $('paid-at').textContent = dateTime(view.paid_at);
    show('customer', view.customer);
    $('plan').textContent = view.plan;
    show('period', view.period_start && view.period_end ? `${date(view.period_start)} – ${date(view.period_end)}` : null);
    show('method', view.method ? methodName(view.method)
        : view.coupon_code && view.amount === 0 ? `${t('withCoupon')} ${view.coupon_code}` : null);
    $('payment').textContent = view.payment || '';
    const body = $('lines');
    while (body.firstChild) body.removeChild(body.firstChild);
    view.lines.forEach((l) => body.appendChild(row(l.description, formatAmount(l.amount, view.currency))));
    if (view.discount_amount > 0) {
        const label = view.coupon_code ? `${t('discount')} (${t('coupon')} ${view.coupon_code})` : t('discount');
        body.appendChild(row(label, '−' + formatAmount(view.discount_amount, view.currency)));
    }
    body.appendChild(row(t('total'), formatAmount(view.amount, view.currency), 'total'));
    $('receipt').hidden = false;
    $('actions').hidden = false;
    document.title = `${t('title')} ${view.number}`;
}

function applyLanguage() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    const other = lang === 'fr' ? 'en' : 'fr';
    $('lang').textContent = other.toUpperCase();
    $('lang').setAttribute('aria-label', t('otherLang'));
    document.title = t('title');
    if (message) setStatus(message.key, message.kind);
    render();
}

async function load() {
    let res;
    try {
        res = await fetch(`/checkout/api/receipts/${encodeURIComponent(id)}`, {
            headers: { 'Accept': 'application/json', 'Yoon-Checkout-Token': token },
            credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
        });
    } catch (e) {
        setStatus('network', 'bad');
        return;
    }
    if (!res.ok) {
        setStatus(res.status === 404 ? 'notFound' : res.status === 429 ? 'tooMany' : 'network', 'bad');
        return;
    }
    view = await res.json();
    setStatus(null);
    render();
}

$('lang').addEventListener('click', () => { lang = lang === 'fr' ? 'en' : 'fr'; applyLanguage(); });
$('print').addEventListener('click', () => window.print());

applyLanguage();
setStatus('loading', 'wait');
if (!id || !token) setStatus('notFound', 'bad');
else load();
