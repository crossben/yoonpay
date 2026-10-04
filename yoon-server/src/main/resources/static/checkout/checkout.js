// Yoon hosted checkout (ADR-0024). One vanilla ES module, no build, no third-party code.
// Data from the server is written with textContent only. The checkout token comes from this
// page's URL and is sent only to this origin, in the Yoon-Checkout-Token header.

const TEXT = {
    en: {
        secure: 'Secure payment', toPay: 'Amount to pay', choose: 'Choose how to pay', pay: 'Pay',
        phone: 'Mobile number', phoneHint: 'The number of the wallet that will pay.',
        alias: 'PI-SPI alias', aliasHint: 'The payment alias shown in your banking or wallet app.',
        continue: 'Continue to payment', back: 'Return to the shop', footer: 'Payment processed by Yoon',
        loading: 'Loading…', starting: 'Starting the payment…', redirecting: 'Taking you to the payment page…',
        pending: 'Waiting for confirmation of your payment. Keep this page open.',
        pendingAction: 'Complete the payment, then come back to this page.',
        succeeded: 'Payment received. Thank you!', failed: 'This payment did not go through.',
        expired: 'This payment has expired.', notFound: 'This payment link is invalid or has expired.',
        unavailable: 'temporarily unavailable', pickOne: 'Choose a payment method.',
        needAlias: 'Enter your PI-SPI alias.', needPhone: 'Enter your mobile number to pay with this method.',
        refused: 'This method was refused. Choose another method or try again.',
        busy: 'A payment attempt is already running. Please wait.', tooMany: 'Too many requests. Wait a moment and try again.',
        network: 'Connection problem. Check your connection and try again.', invalid: 'Check the details you entered.',
        noMethods: 'No payment method is available right now.', otherLang: 'Français',
    },
    fr: {
        secure: 'Paiement sécurisé', toPay: 'Montant à payer', choose: 'Choisissez votre moyen de paiement', pay: 'Payer',
        phone: 'Numéro de mobile', phoneHint: 'Le numéro du portefeuille qui paie.',
        alias: 'Alias PI-SPI', aliasHint: "L'alias de paiement affiché dans votre application bancaire ou de portefeuille.",
        continue: 'Continuer vers le paiement', back: 'Retour à la boutique', footer: 'Paiement traité par Yoon',
        loading: 'Chargement…', starting: 'Démarrage du paiement…', redirecting: 'Redirection vers la page de paiement…',
        pending: 'En attente de la confirmation de votre paiement. Gardez cette page ouverte.',
        pendingAction: 'Terminez le paiement, puis revenez sur cette page.',
        succeeded: 'Paiement reçu. Merci !', failed: "Ce paiement n'a pas abouti.",
        expired: 'Ce paiement a expiré.', notFound: 'Ce lien de paiement est invalide ou a expiré.',
        unavailable: 'momentanément indisponible', pickOne: 'Choisissez un moyen de paiement.',
        needAlias: 'Saisissez votre alias PI-SPI.', needPhone: 'Saisissez votre numéro de mobile pour payer avec ce moyen.',
        refused: 'Ce moyen a été refusé. Choisissez-en un autre ou réessayez.',
        busy: 'Une tentative de paiement est déjà en cours. Patientez.', tooMany: 'Trop de requêtes. Patientez un instant puis réessayez.',
        network: 'Problème de connexion. Vérifiez votre connexion et réessayez.', invalid: 'Vérifiez les informations saisies.',
        noMethods: "Aucun moyen de paiement n'est disponible pour le moment.", otherLang: 'English',
    },
};

const METHOD_NAMES = {
    wave: { en: 'Wave', fr: 'Wave' },
    orange_money: { en: 'Orange Money', fr: 'Orange Money' },
    free_money: { en: 'Free Money', fr: 'Free Money' },
    card: { en: 'Bank card', fr: 'Carte bancaire' },
    pispi: { en: 'PI-SPI (instant transfer)', fr: 'PI-SPI (virement instantané)' },
    mtn: { en: 'MTN Mobile Money', fr: 'MTN Mobile Money' },
    moov: { en: 'Moov Money', fr: 'Moov Money' },
};

const $ = (id) => document.getElementById(id);
const id = decodeURIComponent(location.pathname.split('/').filter(Boolean)[1] || '');
const token = new URLSearchParams(location.search).get('t') || '';

// The browser's preferred languages (what it sends as Accept-Language): the first of fr/en wins.
const preferred = (navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || 'en'])
    .map((l) => l.slice(0, 2).toLowerCase()).find((l) => l === 'fr' || l === 'en');
let lang = preferred || 'en';
let view = null;
let forcePhone = false;
let forceAlias = false;
let pollDelay = 3000;
let pollTimer = null;
let message = null; // { key, kind }

const t = (key) => TEXT[lang][key] || TEXT.en[key] || key;

function methodName(m) {
    const known = METHOD_NAMES[m];
    if (known) return known[lang];
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
    const locale = lang === 'fr' ? 'fr-FR' : 'en-US';
    const nf = new Intl.NumberFormat(locale, { maximumFractionDigits: 0 });
    const decimal = new Intl.NumberFormat(locale).formatToParts(1.5).find((p) => p.type === 'decimal')?.value || '.';
    return nf.format(BigInt(whole)) + (frac ? decimal + frac : '') + ' ' + currency;
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

function applyLanguage() {
    document.documentElement.lang = lang;
    document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = t(el.dataset.i18n); });
    const other = lang === 'fr' ? 'en' : 'fr';
    $('lang').textContent = other.toUpperCase();
    $('lang').setAttribute('aria-label', t('otherLang'));
    document.title = t('secure');
    if (view) render();
}

async function api(path, options = {}) {
    let res;
    try {
        res = await fetch(path, {
            method: options.method || 'GET',
            headers: {
                'Accept': 'application/json',
                'Yoon-Checkout-Token': token,
                ...(options.body ? { 'Content-Type': 'application/json' } : {}),
            },
            body: options.body ? JSON.stringify(options.body) : undefined,
            credentials: 'omit',
            cache: 'no-store',
            referrerPolicy: 'no-referrer',
        });
    } catch (e) {
        return { ok: false, status: 0, body: null };
    }
    let body = null;
    try { body = await res.json(); } catch (e) { body = null; }
    return { ok: res.ok, status: res.status, body };
}

function setStatus(key, kind) {
    message = key ? { key, kind } : null;
    const el = $('status');
    el.className = 'status' + (kind ? ' ' + kind : '');
    el.textContent = key ? t(key) : '';
}

function clear(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
}

function selectedMethod() {
    const checked = document.querySelector('input[name="method"]:checked');
    return checked ? checked.value : null;
}

function updateFields() {
    if (!view) return;
    const m = selectedMethod();
    const option = view.methods.find((o) => o.method === m);
    const needsAlias = forceAlias || (option && option.needs === 'pi_alias' && !view.has_pi_alias);
    $('alias-field').hidden = !needsAlias;
    $('phone-field').hidden = !forcePhone;
}

function renderMethods() {
    const box = $('methods');
    const previous = selectedMethod() || view.method;
    clear(box);
    const usable = view.methods.filter((m) => m.available);
    view.methods.forEach((m) => {
        const label = document.createElement('label');
        label.className = 'method';
        const input = document.createElement('input');
        input.type = 'radio';
        input.name = 'method';
        input.value = m.method;
        input.disabled = !m.available;
        input.checked = m.available && (m.method === previous || usable.length === 1);
        input.addEventListener('change', () => { forcePhone = false; forceAlias = false; showFormError(null); updateFields(); });
        const text = document.createElement('span');
        const name = document.createElement('span');
        name.className = 'name';
        name.textContent = methodName(m.method);
        text.appendChild(name);
        if (!m.available) {
            const note = document.createElement('span');
            note.className = 'note muted';
            note.textContent = ' — ' + t('unavailable');
            text.appendChild(note);
        }
        label.appendChild(input);
        label.appendChild(text);
        box.appendChild(label);
    });
    $('pay').disabled = usable.length === 0;
    if (usable.length === 0) showFormError('noMethods');
    updateFields();
}

let formError = null;
function showFormError(key) {
    formError = key;
    $('form-error').hidden = !key;
    $('form-error').textContent = key ? t(key) : '';
}

function render() {
    $('amount').textContent = formatAmount(view.amount, view.currency);
    $('description').hidden = !view.description;
    $('description').textContent = view.description || '';

    const back = safeHttpUrl(view.return_url);
    $('return-wrap').hidden = !back || view.status === 'created';
    if (back) $('return').href = back;

    $('choose').hidden = !view.can_choose;
    if (view.can_choose) {
        renderMethods();
        if (formError) showFormError(formError);
    }

    const next = view.next_action;
    $('next').hidden = !(view.status === 'pending' && next);
    if (next) {
        $('instructions').textContent = next.instructions || '';
        $('instructions').hidden = !next.instructions;
        const url = next.type === 'redirect' ? safeHttpUrl(next.url) : null;
        $('provider-link').hidden = !url;
        if (url) $('provider-link').href = url;
    }

    switch (view.status) {
        case 'created':
            if (view.can_choose) {
                // Keep an error the customer has not acted on; drop "loading"/"starting".
                if (!message || message.kind === 'wait') setStatus(null);
            } else {
                setStatus('pending', 'wait'); // an attempt is running, or the checkout just expired
                schedulePoll();
            }
            break;
        case 'pending':
            setStatus(next ? 'pendingAction' : 'pending', 'wait');
            schedulePoll();
            break;
        case 'succeeded':
            setStatus('succeeded', 'ok');
            break;
        case 'failed':
            setStatus('failed', 'bad');
            break;
        case 'expired':
            setStatus('expired', 'bad');
            break;
        default:
            setStatus('pending', 'wait');
            schedulePoll();
    }
}

function schedulePoll() {
    if (pollTimer) return;
    pollTimer = setTimeout(async () => {
        pollTimer = null;
        pollDelay = Math.min(Math.round(pollDelay * 1.3), 15000);
        await load();
    }, pollDelay);
}

async function load() {
    const r = await api(`/checkout/api/${encodeURIComponent(id)}`);
    if (r.status === 404) {
        view = null;
        $('choose').hidden = true;
        $('amount').textContent = '—';
        setStatus('notFound', 'bad');
        return;
    }
    if (!r.ok) {
        setStatus(r.status === 429 ? 'tooMany' : 'network', 'bad');
        schedulePoll();
        return;
    }
    view = r.body;
    render();
}

async function choose(event) {
    event.preventDefault();
    if (!view || !view.can_choose) return;
    const method = selectedMethod();
    if (!method) { showFormError('pickOne'); return; }
    const body = { method };
    const phone = $('phone').value.trim();
    const alias = $('alias').value.trim();
    if (!$('phone-field').hidden && phone) body.phone = phone;
    if (!$('alias-field').hidden) {
        if (!alias) { showFormError('needAlias'); $('alias').focus(); return; }
        body.pi_alias = alias;
    }

    showFormError(null);
    $('pay').disabled = true; // the server refuses a second round anyway; this keeps the button honest
    setStatus('starting', 'wait');
    const r = await api(`/checkout/api/${encodeURIComponent(id)}/attempts`, { method: 'POST', body });

    if (r.ok) {
        view = r.body;
        const code = view.last_error ? view.last_error.code : null;
        if (view.status === 'created' && code) {
            forcePhone = code === 'PHONE_REQUIRED';
            forceAlias = code === 'PI_ALIAS_REQUIRED';
            message = null;
            setStatus(null);
            showFormError(forcePhone ? 'needPhone' : forceAlias ? 'needAlias' : 'refused');
            render();
            (forcePhone ? $('phone') : forceAlias ? $('alias') : $('pay')).focus();
            return;
        }
        const url = view.next_action && view.next_action.type === 'redirect' ? safeHttpUrl(view.next_action.url) : null;
        if (view.status === 'pending' && url) {
            render();
            setStatus('redirecting', 'wait');
            location.assign(url);
            return;
        }
        render();
        return;
    }

    $('pay').disabled = false;
    if (r.status === 404) { setStatus('notFound', 'bad'); $('choose').hidden = true; return; }
    if (r.status === 429) { setStatus('tooMany', 'bad'); return; }
    if (r.status === 400) { setStatus(null); showFormError(forcePhone ? 'needPhone' : 'invalid'); return; }
    if (r.status === 409) {
        const code = r.body && r.body.code;
        setStatus(code === 'checkout_in_progress' ? 'busy' : code === 'checkout_expired' ? 'expired' : 'pending',
            code === 'checkout_expired' ? 'bad' : 'wait');
        await load();
        return;
    }
    if (r.status === 422) { setStatus(null); showFormError('refused'); await load(); return; }
    setStatus('network', 'bad');
}

$('lang').addEventListener('click', () => {
    lang = lang === 'fr' ? 'en' : 'fr';
    applyLanguage();
    if (message) setStatus(message.key, message.kind);
});
$('choose-form').addEventListener('submit', choose);
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && view && view.status !== 'succeeded') {
        pollDelay = 3000;
        load();
    }
});

applyLanguage();
setStatus('loading', 'wait');
if (!id || !token) {
    setStatus('notFound', 'bad');
} else {
    load();
}
