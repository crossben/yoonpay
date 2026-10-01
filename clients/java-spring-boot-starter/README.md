# yoon-spring-boot-starter

Spring Boot auto-configuration for the [Yoon](https://github.com/crossben/yoonpay) Java client
(`io.github.crossben:yoon-java`). Apache-2.0.

Requires Java 17+ and Spring Boot 3.x or 4.x (CI tests 3.5 and 4.1). Spring Boot itself is not
pulled in: the application brings its own.

```xml
<dependency>
    <groupId>io.github.crossben</groupId>
    <artifactId>yoon-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Configuration

```properties
yoon.url=${YOON_URL}
yoon.api-key=${YOON_API_KEY}
yoon.webhook-secret=${YOON_WEBHOOK_SECRET}   # YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side
yoon.webhook.paths=/yoon/webhook             # the paths Yoon posts events to
# yoon.timeout=30s
# yoon.webhook.dedupe=3d                      # how long the default store remembers an event id
```

Spring's relaxed binding also reads them straight from the environment (`YOON_URL`,
`YOON_API_KEY`, `YOON_WEBHOOK_SECRET`, `YOON_WEBHOOK_PATHS`).

| Property | Effect |
| --- | --- |
| `yoon.api-key` set | a `dev.yoonpay.client.Yoon` bean (startup fails if `yoon.url` is missing) |
| `yoon.webhook.paths` set | the webhook filter on those paths (startup fails if `yoon.webhook-secret` is missing) |

A `Yoon`, `YoonEventStore` or `YoonWebhookFilter` bean you declare yourself replaces the
auto-configured one.

## Using the client

```java
@Service
class Checkout {
    private final Yoon yoon;

    Checkout(Yoon yoon) {
        this.yoon = yoon;
    }

    String pay(Order order) {
        Payment payment = yoon.createPayment(new CreatePaymentRequest()
                .amount(5000L).currency("XOF").country("SN").method("wave")
                .customer(new CreatePaymentRequestCustomer().phone("+221771234567"))
                .reference(order.id()),
            "order-" + order.id());   // idempotency key: a retry can never charge twice
        return payment.getCheckoutUrl();
    }
}
```

Everything else about the client (helpers, `call(...)`, `YoonException`, no retries) is in
[`clients/java`](../java/README.md).

## Receiving Yoon's events

```java
@RestController
class YoonWebhookController {

    @PostMapping("/yoon/webhook")
    ResponseEntity<Void> handle(YoonEvent event) {
        if (event.type().equals("payment.succeeded")) {
            String paymentId = event.object().path("id").asText();
            // mark the order paid
        }
        return ResponseEntity.noContent().build();
    }
}
```

On the paths in `yoon.webhook.paths` (POST only; other requests pass untouched), the filter:

- checks the `Yoon-Signature` over the **raw body** before anything parses it, and answers 401
  if it is wrong or more than 300 s off;
- answers an already-handled event with 200 without calling the controller;
- hands the verified `YoonEvent` (`id`, `type`, `object`, `payload`) to the controller — as a
  parameter, or the request attribute `YoonWebhookFilter.EVENT_ATTRIBUTE` — and the raw body is
  still readable (`@RequestBody String`);
- remembers the event id only after the controller answered 2xx: a controller that fails or
  throws gets Yoon's retry.

Events are unordered and may arrive more than once: act on the state in `event.object()`.
Exclude the path from CSRF protection if Spring Security is on.

The default store keeps ids in memory, in one process. With several instances, declare a
shared one:

```java
@Bean
YoonEventStore yoonEventStore(CacheManager caches) {
    return new CacheYoonEventStore(caches.getCache("yoon-events"));   // e.g. Redis, entries kept for days
}
```

## Layout

- `src/main/java/dev/yoonpay/spring` — `YoonAutoConfiguration`, `YoonProperties`,
  `YoonWebhookFilter`, `YoonEvent`, the event stores.

```sh
(cd ../java && mvn install -DskipTests)   # until yoon-java is on Maven Central
mvn test             # Spring Boot 4
mvn test -Pboot3     # Spring Boot 3
```
