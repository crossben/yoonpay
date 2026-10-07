# yoon-java

Java client for [Yoon](https://github.com/crossben/yoonpay), a self-hosted payment gateway for
African payment providers. Apache-2.0. Java 17+, Jackson 2, no other dependency.

```xml
<dependency>
    <groupId>io.github.crossben</groupId>
    <artifactId>yoon-java</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
Yoon yoon = new Yoon("https://pay.example.com", System.getenv("YOON_API_KEY"));

Payment payment = yoon.createPayment(new CreatePaymentRequest()
        .amount(5000L).currency("XOF").country("SN").method("wave")
        .customer(new CreatePaymentRequestCustomer().phone("+221771234567"))
        .reference("order_1042")
        .returnUrl(URI.create("https://shop.example/orders/1042")),
    "order-1042");   // idempotency key tied to your order

String csv = yoon.exportCsv(Yoon.Export.PAYMENTS, null, null);
```

Errors are `YoonException`: `problemCode()` (e.g. `no_provider_for_method`), `httpStatus()`,
`isRetryable()`. Every API operation is on the generated API objects (`yoon.payments()`,
`yoon.events()`, …), wrapped with `yoon.call(() -> …)`. CSV exports go through `exportCsv`:
the generated exports API cannot read CSV.

Verify Yoon's webhooks with `WebhookSignature.verify(secret, header, rawBody)`.

`generated/` is produced from `api/openapi.yaml` by `clients/generate.sh` and never edited by
hand. Yoon's own test suite runs this client against a real server (`JavaClientTest`).

## Prompt for an AI coding agent

Copy this into your coding agent (Claude Code, Cursor, Copilot) to add Yoon to an existing app.

```text
Integrate Yoon payments (io.github.crossben:yoon-java 0.2.x) into this Java app.
Install: Maven/Gradle dependency io.github.crossben:yoon-java:0.2.0 (Java 17+)
Do:
1. One shared client: new Yoon(System.getenv("YOON_URL"), System.getenv("YOON_API_KEY")).
2. Create a payment keyed on the order:
   yoon.createPayment(new CreatePaymentRequest().amount(...).currency("XOF").country("SN")
   .method("wave").reference(orderId).returnUrl(...), "order-" + orderId);
   redirect to payment.getCheckoutUrl(), or show the instructions.
3. Webhook endpoint: read the RAW body bytes (not a parsed object), check
   WebhookSignature.verify(secret, header "Yoon-Signature", rawBody); 401 if false; parse the
   JSON (id, type, data.object) and update the order once per event id.
4. Errors are YoonException: problemCode(), httpStatus(), isRetryable().
Rules (they protect real money; follow them exactly):
- Amounts are integers in minor units, never floats: XOF has no minor unit, 5000 = 5 000 XOF.
- Every write takes an idempotency key tied to the business object ("order-<id>",
  "refund-<order id>-<n>"). On a retryable error, retry with the SAME key; a new key could
  charge twice. The client never retries on its own.
- Store Yoon's payment id on the order. Fulfil ONLY when the webhook says payment.succeeded
  (or GET the payment and check status == "succeeded"). The customer returning to return_url
  proves nothing.
- "pending" means the provider has not answered yet, not failed: show "waiting for payment".
  payment.failed / payment.expired: let the customer try again (a new order or a new key).
- Webhooks: verify the signature over the RAW request body (the helper below does it), answer
  2xx quickly, and make the handler idempotent: the same event can arrive more than once.
  Events: payment.succeeded, payment.failed, payment.expired, refund.succeeded, refund.failed,
  payout.paid, payout.failed, payout.needs_review.
- Never log the API key or full phone numbers; read YOON_URL, YOON_API_KEY and
  YOON_WEBHOOK_SECRET from the environment.
- Optional: "checkout": "hosted" (without "method") sends the customer to Yoon's own page to
  choose Wave, Orange Money, card, …; redirect them to the returned checkout_url.
Verify: run Yoon with YOON_DEMO_ENABLED=true and YOON_APPS_<APP>_PROVIDERS_DEMO_PRIORITY=1,
set YOON_APPS_<APP>_WEBHOOK_URL to this app's webhook URL, create a payment, open its
checkout_url, click Pay, and check the order becomes paid only after the webhook arrives.
Then click Decline on another payment and check the order is not paid.
Docs: https://yoonpay.benhattab.pro/docs/
```
