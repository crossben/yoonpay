# yoonpay

Python client for [Yoon](https://github.com/crossben/yoonpay), a self-hosted payment gateway
for African payment providers, with Django, FastAPI and Flask helpers. Apache-2.0.

Requires Python 3.10+. Synchronous (urllib3); there is no async client yet.

```sh
pip install yoonpay              # the client
pip install "yoonpay[django]"    # or [fastapi], [flask]: the framework is an optional extra
```

## Plain Python

```python
import os
from yoonpay import Yoon, YoonException

yoon = Yoon("https://pay.example.com", os.environ["YOON_API_KEY"])  # timeout=30, connect_timeout=5

try:
    payment = yoon.create_payment(
        {
            "amount": 5000,              # XOF has no minor unit: 5 000 FCFA
            "currency": "XOF",
            "country": "SN",
            "method": "wave",            # wave, orange_money, free_money, card
            "customer": {"phone": "+221771234567"},
            "reference": "order_1042",
            "return_url": "https://shop.example/orders/1042",
        },
        idempotency_key="order-1042",    # tie it to your order: a retry can never charge twice
    )
    redirect_to = payment.checkout_url
except YoonException as e:
    e.problem_code     # e.g. no_provider_for_method, refund_exceeds_payment
    e.is_retryable()   # True: retry with the same idempotency key
```

Helpers: `create_payment`, `get_payment`, `refund(payment_id, idempotency_key, amount=None,
reason=None)`, `create_payout`, `export_csv("payments" | "refunds" | "payouts" | "ledger",
from_=None, to=None)`. Every other operation of the API is on the generated API objects —
`yoon.payments()`, `.refunds()`, `.payouts()`, `.events()`, `.ledger()`, `.meta()` — wrapped
with `yoon.call(lambda: …)` to turn errors into `YoonException`:

```python
page = yoon.call(lambda: yoon.payments().list_payments(reference="order_1042"))
```

The idempotency key is a required argument of every write: the client never makes one up and
never retries on its own (urllib3's retries are switched off). Retrying is your decision, with
the same key.

## Django

```python
# settings.py
YOON_WEBHOOK_SECRET = os.environ["YOON_WEBHOOK_SECRET"]  # YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side
# optional: YOON_WEBHOOK_CACHE = "default", YOON_WEBHOOK_DEDUPE_SECONDS = 259200

# views.py
from django.http import HttpResponse
from yoonpay.django import yoon_webhook

@yoon_webhook
def yoon_events(request, event):
    if event.type == "payment.succeeded":
        Order.objects.filter(yoon_payment_id=event.object["id"]).update(status="paid")
    return HttpResponse(status=204)

# urls.py
urlpatterns = [path("yoon/webhook", yoon_events)]
```

The decorator exempts the view from CSRF, accepts only POST, and remembers handled event ids in
Django's cache (use a shared backend such as Redis when you run several servers).

## FastAPI

```python
import os
from fastapi import APIRouter, Depends, FastAPI
from yoonpay import Event
from yoonpay.fastapi import yoon_event, yoon_webhook_route

webhooks = APIRouter(route_class=yoon_webhook_route(secret=os.environ["YOON_WEBHOOK_SECRET"]))

@webhooks.post("/yoon/webhook", status_code=204)
def yoon_events(event: Event = Depends(yoon_event)) -> None:
    if event.type == "payment.succeeded":
        ...

app = FastAPI()
app.include_router(webhooks)
```

Only the routes of that router are checked. The default id store is in-process; with several
workers pass `store=CacheEventStore(shared_cache)`.

## Flask

```python
from yoonpay.flask import yoon_webhook

app.config["YOON_WEBHOOK_SECRET"] = os.environ["YOON_WEBHOOK_SECRET"]

@app.post("/yoon/webhook")
@yoon_webhook
def yoon_events(event):
    if event.type == "payment.succeeded":
        ...
    return "", 204
```

The default id store is in-process, per app; with several workers pass
`@yoon_webhook(store=CacheEventStore(cache))`, e.g. over Flask-Caching.

## Webhooks

All three helpers behave the same: a wrong or stale signature (more than 300 s off) gets 401
without reaching your code, an already-handled event gets 200 without reaching your code, and an
event id is remembered only after your code answered 2xx — if it fails or raises, Yoon's retry
reaches it again. Events are unordered and may arrive more than once: act on the state in
`event.object`, not on arrival order.

**Raw body.** The signature covers the bytes Yoon sent. The helpers read the raw body
(`request.body` in Django, `await request.body()` in FastAPI, `request.get_data()` in Flask);
if you verify by hand, do the same — a body parsed and re-encoded as JSON no longer matches
and is rejected.

Outside these frameworks:

```python
from yoonpay import Event, verify_signature

if not verify_signature(secret, headers.get("Yoon-Signature"), raw_body):
    ...  # answer 401
event = Event.from_json(raw_body)   # event.id, event.type, event.object
```

`sign_signature(secret, raw_body)` builds a header for your own tests.

## Errors

`YoonException` carries `http_status` (`None` when Yoon was not reached), `problem_code` (Yoon's
stable `code`, `None` when Yoon was not reached), `problem` (the full problem document) and
`is_retryable()` — true when Yoon was unreachable, busy (`idempotency_in_progress`) or failing
(5xx). A 2xx answer the client cannot read gets `problem_code = "unreadable_response"` and is
**not** retryable: the call may have worked, look it up first. The API key never appears in
errors or in `repr(yoon)`.

A status or event type added by a newer Yoon server does not break parsing: the value is kept
as a string (`payment.status == "new_status"`).

## Layout

- `generated/` — generated from `api/openapi.yaml` by `clients/generate.sh` (the package
  `yoonpay.generated`). Never edit by hand; CI fails if it drifts from the contract.
- `src/yoonpay/` — the hand-written layer: `Yoon`, `YoonException`, webhook helpers, framework
  integrations.
- `e2e/run.py` — the shared end-to-end scenario (`clients/e2e/scenario.md`) against a real server.

```sh
pip install -e ".[django,fastapi,flask,test]" && pytest
```
