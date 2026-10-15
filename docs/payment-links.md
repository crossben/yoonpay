# Payment links — get paid without writing code

A payment link is a URL (and a QR code) you share anywhere — WhatsApp, Instagram, an SMS, a
printed sign at the counter. The customer opens it, presses **Pay**, and completes Yoon's hosted
checkout with Wave, Orange Money, a card or any method your providers offer. Design:
[ADR-0030](adr/0030-payment-links.md).

Each payment through a link is an **ordinary hosted payment**: the provider's callback is
re-confirmed with its API, the ledger records it, your application gets `payment.succeeded`. A
link adds an entry point, nothing that touches money.

Payment links need `YOON_PUBLIC_URL` (the link must be absolute).

## 1. Create a link

**Without a developer** — in the operator dashboard, tab **Payment links**: a description, the
amount (or "the customer types the amount"), single use if needed, **Create link**. Copy the
link, or save its QR code.

**From the command line** (amount in minor units — 5000 is 5 000 XOF — or `any`):

```sh
docker compose run --rm yoon links create shop 5000 XOF SN Tote bag, size M
```

**From your application:**

```http
POST /v1/payment_links
Idempotency-Key: link-tote-bag-m

{"amount": 5000, "currency": "XOF", "country": "SN", "description": "Tote bag, size M",
 "max_payments": 1, "return_url": "https://shop.example/thanks"}
```

The answer carries `url` (what you share, `<YOON_PUBLIC_URL>/l/<code>`) and `qr_url` (an SVG QR
code of it). The code is random and unguessable; whoever has the link may pay it.

| Field | Meaning |
| --- | --- |
| `amount` | A fixed amount in minor units. Omit it to let the customer type the amount, optionally between `min_amount` and `max_amount` (donations, tips, open invoices). |
| `max_payments` | `1` for single use; omit for a link anyone can pay many times. |
| `expires_at` | After this, the page says the link has expired. |
| `method` | Limit the link to one method (e.g. `wave`). Default: every method your providers offer. |
| `return_url` | Where the customer goes after paying. |

The amount, currency and country never change: archive the link and create another.

## 2. What the customer sees

`/l/<code>`: your application's name, the description, the amount (or a field for it) and
**Pay**, in French or English, sized for a phone. Pressing **Pay** opens the hosted checkout. A
double tap or a retry pays one payment. An archived, expired or used-up link says so instead.

## 3. Follow the money

- `GET /v1/payment_links/{id}`: `payments_count` and `amount_collected` (succeeded payments).
- Every payment carries `payment_link`; the dashboard and `payments.csv` show it;
  `payment_links.csv` exports each link with its totals.
- Your webhook receives `payment.succeeded` for each payment, with `payment_link` set.

A single-use link counts **succeeded** payments: if two customers pay at the same moment, both
payments go through (money received is never refused) and the link then shows as used.

## 4. Stop a link

Dashboard **Archive**, `PATCH /v1/payment_links/{id}` with `{"active": false}`, or
`POST /admin/v1/applications/{app}/payment_links/{id}/archive`. Payments already made are kept.
