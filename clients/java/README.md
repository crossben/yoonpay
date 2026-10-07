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
