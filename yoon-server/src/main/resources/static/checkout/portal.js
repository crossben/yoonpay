// Yoon customer portal (ADR-0031). One vanilla ES module, no build, no third-party code.
// Data from the server is written with textContent only. The session token comes from this
// page's URL and is sent only to this origin, in the Yoon-Checkout-Token header.

const TEXT = {
    en: {
        title: 'My subscription', invoices: 'Invoices', back: 'Back', footer: 'Payments processed by Yoon',
        loading: 'Loading…', notFound: 'This link is invalid or has expired. Open it again from the app.',
        network: 'Connection problem. Check your connection and try again.',
        tooMany: 'Too many requests. Wait a moment and try again.', hello: 'Hello {name}.',
        none: 'You have no subscription yet.', paidUntil: 'Paid until {date}', trialUntil: 'Free trial until {date}',
        endsOn: 'Ends on {date}', willChange: 'Changes to {plan} at the next renewal', discount: 'Coupon {code}',
        discountLeft: 'Coupon {code} — {n} more payment(s)', pay: 'Pay {amount}', receipt: 'Receipt',
        every: { day: ['day', 'days'], week: ['week', 'weeks'], month: ['month', 'months'], year: ['year', 'years'] },
        statuses: { active: 'Active', trialing: 'Free trial', past_due: 'Payment due', incomplete: 'Awaiting payment',
            expired: 'Expired', canceled: 'Canceled' },
        invoiceStatuses: { paid: 'Paid', open: 'To pay', void: 'Canceled', uncollectible: 'Unpaid' },
        renew: 'Renew', payNow: 'Pay now (the trial days are kept)', cancel: 'Cancel at the end of the period', confirmCancel: 'Tap again to confirm',
        resume: 'Keep my subscription', change: 'Change plan', changeTo: 'Change to', apply: 'Apply',
        couponPlaceholder: 'Coupon code', done: 'Done.', working: 'One moment…',
        refused: 'This could not be done: {why}', notAllowed: 'This is not available here.',
        otherLang: 'Français',
    },
    fr: {
        title: 'Mon abonnement', invoices: 'Factures', back: 'Retour', footer: 'Paiements traités par Yoon',
        loading: 'Chargement…', notFound: "Ce lien est invalide ou a expiré. Rouvrez-le depuis l'application.",
        network: 'Problème de connexion. Vérifiez votre connexion et réessayez.',
        tooMany: 'Trop de requêtes. Patientez un instant puis réessayez.', hello: 'Bonjour {name}.',
        none: "Vous n'avez pas encore d'abonnement.", paidUntil: "Payé jusqu'au {date}", trialUntil: "Essai gratuit jusqu'au {date}",
        endsOn: 'Se termine le {date}', willChange: 'Passe à {plan} au prochain renouvellement', discount: 'Code promo {code}',
        discountLeft: 'Code promo {code} — encore {n} paiement(s)', pay: 'Payer {amount}', receipt: 'Reçu',
        every: { day: ['jour', 'jours'], week: ['semaine', 'semaines'], month: ['mois', 'mois'], year: ['an', 'ans'] },
        statuses: { active: 'Actif', trialing: 'Essai gratuit', past_due: 'Paiement en attente', incomplete: 'En attente de paiement',
            expired: 'Expiré', canceled: 'Annulé' },
        invoiceStatuses: { paid: 'Payée', open: 'À payer', void: 'Annulée', uncollectible: 'Impayée' },
        renew: 'Renouveler', payNow: "Payer maintenant (les jours d'essai sont conservés)", cancel: 'Résilier en fin de période', confirmCancel: 'Touchez encore pour confirmer',
        resume: 'Garder mon abonnement', change: 'Changer de formule', changeTo: 'Passer à', apply: 'Appliquer',
        couponPlaceholder: 'Code promo', done: 'C’est fait.', working: 'Un instant…',
        refused: 'Impossible : {why}', notAllowed: "Cette action n'est pas disponible ici.",
        otherLang: 'English',
    },
};

const $ = (id) => document.getElementById(id);
const id = decodeURIComponent(location.pathname.split('/').filter(Boolean)[2] || '');
const token = new URLSearchParams(location.search).get('t') || '';

const preferred = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en'])
    .map((l) => l.slice(0, 2).toLowerCase()).find((l) => l === 'fr' || l === 'en');
let lang = preferred || 'en';
let view = null;
let message = null;

const t = (key) => TEXT[lang][key] ?? TEXT.en[key] ?? key;
const fill = (text, values) => Object.entries(values).reduce((s, [k, v]) => s.replace(`{${k}}`, v), text);
const locale = () => (lang === 'fr' ? 'fr-FR' : 'en-GB');
const date = (iso) => (iso ? new Date(iso).toLocaleDateString(locale(), { year: 'numeric', month: 'long', day: 'numeric' }) : '');

/** Minor units → display string without floating point: 5000 XOF → "5 000 XOF". */
function money(amount, currency) {
    let d = 2;
    try { d = new Intl.NumberFormat('en', { style: 'currency', currency }).resolvedOptions().maximumFractionDigits; } catch (e) { /* keep 2 */ }
    const s = String(amount).padStart(d + 1, '0');
    const whole = d > 0 ? s.slice(0, -d) : s;
    const frac = d > 0 ? s.slice(-d) : '';
    const sep = new Intl.NumberFormat(locale()).formatToParts(1.5).find((p) => p.type === 'decimal')?.value || '.';
    return new Intl.NumberFormat(locale(), { maximumFractionDigits: 0 }).format(BigInt(whole)) + (frac ? sep + frac : '') + ' ' + currency;
}

function every(interval, count) {
    const unit = t('every')[interval];
    if (!unit) return `${count} ${interval}`;
    return count === 1 ? unit[0] : `${count} ${unit[1]}`;
}

function safeHttpUrl(value) {
    if (!value) return null;
    try {
        const u = new URL(value, location.href);
        return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null;
    } catch (e) {
        return null;
    }
}

function node(tag, cls, text) {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (text !== undefined && text !== null) el.textContent = text;
    return el;
}

function setStatus(key, kind) {
    message = key ? { key, kind } : null;
    $('status').className = 'status' + (kind ? ' ' + kind : '');
    $('status').textContent = key ? t(key) : '';
}

function subscriptionCard(s) {
    const card = node('section', 'card sub');
    const head = node('div', 'sub-head');
    head.appendChild(node('h2', 'section-title', s.plan));
    head.appendChild(node('span', `pill ${s.entitled ? 'ok' : 'bad'}`, t('statuses')[s.status] || s.status));
    card.appendChild(head);
    card.appendChild(node('p', 'muted', `${money(s.amount, s.currency)} / ${every(s.interval, s.interval_count)}`));
    const facts = [];
    if (s.status === 'trialing' && s.trial_end) facts.push(fill(t('trialUntil'), { date: date(s.trial_end) }));
    else if (s.cancel_at_period_end && s.paid_until) facts.push(fill(t('endsOn'), { date: date(s.paid_until) }));
    else if (s.paid_until && s.entitled) facts.push(fill(t('paidUntil'), { date: date(s.paid_until) }));
    if (s.pending_plan) facts.push(fill(t('willChange'), { plan: s.pending_plan }));
    if (s.discount_code) {
        facts.push(s.discount_periods_remaining == null ? fill(t('discount'), { code: s.discount_code })
            : fill(t('discountLeft'), { code: s.discount_code, n: s.discount_periods_remaining }));
    }
    facts.forEach((f) => card.appendChild(node('p', null, f)));
    const pay = s.open_invoice && safeHttpUrl(s.open_invoice.checkout_url);
    if (pay) {
        const a = node('a', 'button primary', fill(t('pay'), { amount: money(s.open_invoice.amount_due, s.open_invoice.currency) }));
        a.href = pay;
        card.appendChild(a);
    } else if (s.status !== 'canceled') {
        card.appendChild(button(t(s.status === 'trialing' ? 'payNow' : 'renew'), 'primary', () => act({ action: 'renew', subscription: s.id })));
    }
    const live = ['active', 'trialing'].includes(s.status);
    if (view.allow.cancel && live && !s.cancel_at_period_end) {
        const b = button(t('cancel'), 'secondary', () => {
            if (!b.dataset.armed) { b.dataset.armed = '1'; b.textContent = t('confirmCancel'); return; }
            act({ action: 'cancel', subscription: s.id });
        });
        card.appendChild(b);
    }
    if (view.allow.cancel && s.cancel_at_period_end) {
        card.appendChild(button(t('resume'), 'secondary', () => act({ action: 'resume', subscription: s.id })));
    }
    const options = view.allow.prices.filter((p) => p.currency === s.currency && !(p.amount === s.amount && p.plan === s.plan
        && p.interval === s.interval && p.interval_count === s.interval_count));
    if (options.length && ['active', 'trialing', 'past_due'].includes(s.status)) {
        const select = node('select', 'field-input');
        select.setAttribute('aria-label', t('changeTo'));
        options.forEach((p) => {
            const o = node('option', null, `${p.plan} — ${money(p.amount, p.currency)} / ${every(p.interval, p.interval_count)}`);
            o.value = p.id;
            select.appendChild(o);
        });
        const row = node('div', 'action-row');
        row.appendChild(select);
        row.appendChild(button(t('change'), 'secondary', () => act({ action: 'change', subscription: s.id, price: select.value })));
        card.appendChild(row);
    }
    if (view.allow.coupons && s.status !== 'canceled') {
        const input = node('input', 'field-input');
        input.placeholder = t('couponPlaceholder');
        input.maxLength = 32;
        input.setAttribute('aria-label', t('couponPlaceholder'));
        const row = node('div', 'action-row');
        row.appendChild(input);
        row.appendChild(button(t('apply'), 'secondary', () => act({ action: 'coupon', subscription: s.id, coupon: input.value.trim() })));
        card.appendChild(row);
    }
    return card;
}

function button(label, kind, onClick) {
    const b = node('button', `button ${kind}`, label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
}

function newAttempt() {
    return (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random().toString(16).slice(2);
}

/** One action; a fresh attempt per click (the server acts once per attempt). */
async function act(body) {
    document.querySelectorAll('#subscriptions button').forEach((b) => { b.disabled = true; });
    setStatus('working', 'wait');
    let res, json = null;
    try {
        res = await fetch(`/checkout/api/portal/${encodeURIComponent(id)}/actions`, {
            method: 'POST',
            headers: { 'Accept': 'application/json', 'Content-Type': 'application/json', 'Yoon-Checkout-Token': token },
            body: JSON.stringify({ ...body, attempt: newAttempt() }),
            credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
        });
        try { json = await res.json(); } catch (e) { json = null; }
    } catch (e) {
        setStatus('network', 'bad');
        render();
        return;
    }
    if (res.ok) {
        const next = json && safeHttpUrl(json.checkout_url);
        if (next) { location.assign(next); return; }
        await load();
        setStatus('done', 'ok');
        return;
    }
    if (res.status === 404) { setStatus('notFound', 'bad'); return; }
    message = null;
    $('status').className = 'status bad';
    $('status').textContent = res.status === 403 ? t('notAllowed')
        : fill(t('refused'), { why: (json && json.detail) || res.status });
    render();
}

function render() {
    if (!view) return;
    $('merchant').textContent = view.merchant || t('title');
    $('hello').hidden = !view.customer;
    if (view.customer) $('hello').textContent = fill(t('hello'), { name: view.customer });
    const subs = $('subscriptions');
    while (subs.firstChild) subs.removeChild(subs.firstChild);
    if (!view.subscriptions.length) subs.appendChild(node('p', 'card', t('none')));
    view.subscriptions.forEach((s) => subs.appendChild(subscriptionCard(s)));

    const rows = $('invoices');
    while (rows.firstChild) rows.removeChild(rows.firstChild);
    view.invoices.forEach((i) => {
        const tr = node('tr');
        tr.appendChild(node('td', 'nowrap', i.number));
        tr.appendChild(node('td', 'muted', date(i.paid_at || i.period_start)));
        tr.appendChild(node('td', 'num', money(i.amount_due, i.currency)));
        const last = node('td', 'num');
        const receipt = safeHttpUrl(i.receipt_url);
        if (receipt) {
            const a = node('a', null, t('receipt'));
            a.href = receipt;
            last.appendChild(a);
        } else {
            last.textContent = t('invoiceStatuses')[i.status] || i.status;
        }
        tr.appendChild(last);
        rows.appendChild(tr);
    });
    $('invoices-card').hidden = view.invoices.length === 0;

    const back = safeHttpUrl(view.return_url);
    $('back-wrap').hidden = !back;
    if (back) $('back').href = back;
    document.title = t('title');
}

function applyLanguage() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    $('lang').textContent = (lang === 'fr' ? 'en' : 'fr').toUpperCase();
    $('lang').setAttribute('aria-label', t('otherLang'));
    if (message) setStatus(message.key, message.kind);
    render();
}

async function load() {
    let res;
    try {
        res = await fetch(`/checkout/api/portal/${encodeURIComponent(id)}`, {
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

applyLanguage();
setStatus('loading', 'wait');
if (!id || !token) setStatus('notFound', 'bad'); else load();
