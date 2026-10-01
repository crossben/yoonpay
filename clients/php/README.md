# yoonpay/yoon-php

PHP client for [Yoon](https://github.com/crossben/yoonpay), a self-hosted payment
gateway for African payment providers, with Laravel and Symfony integrations. Apache-2.0.

Requires PHP 8.2+. Works with Guzzle 7 and 8 (Laravel 10 to 13) and with Symfony 6.4 LTS and 7.x.

```sh
composer require yoonpay/yoon-php
```

## Plain PHP

```php
use Yoon\Yoon;
use Yoon\YoonException;

$yoon = new Yoon('https://pay.example.com', getenv('YOON_API_KEY'));

try {
    $payment = $yoon->createPayment([
        'amount' => 5000,              // XOF has no minor unit: 5 000 FCFA
        'currency' => 'XOF',
        'country' => 'SN',
        'method' => 'wave',            // wave, orange_money, free_money, card
        'customer' => ['phone' => '+221771234567'],
        'reference' => 'order_1042',
        'return_url' => 'https://shop.example/orders/1042',
    ], idempotencyKey: 'order-1042');  // tie it to your order: a retry can never charge twice

    header('Location: ' . $payment->getCheckoutUrl());
} catch (YoonException $e) {
    $e->problemCode();   // e.g. no_provider_for_method, refund_exceeds_payment
    $e->isRetryable();   // true: retry with the same idempotency key
}
```

Helpers: `createPayment`, `getPayment`, `refund`, `createPayout`, `exportCsv`. Every other
operation of the API is on the generated API objects — `$yoon->payments()`, `->refunds()`,
`->payouts()`, `->events()`, `->ledger()`, `->meta()` — wrapped with `$yoon->call(fn () => …)`
to turn errors into `YoonException`.

## Laravel

The service provider and the `Yoon` facade are auto-discovered.

```dotenv
YOON_URL=https://pay.example.com
YOON_API_KEY=yk_…
YOON_WEBHOOK_SECRET=…    # the same value as YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side
```

```php
use Yoon\Laravel\Facades\Yoon;

$payment = Yoon::createPayment([...], 'order-' . $order->id);
```

### Receiving Yoon's events

```php
// routes/web.php — exclude the path from CSRF (bootstrap/app.php: validateCsrfTokens(except: ['yoon/webhook']))
Route::post('/yoon/webhook', YoonWebhookController::class)->middleware('yoon.webhook');

// in the controller
$event = $request->attributes->get('yoon_event');   // Yoon\Webhook\Event
if ($event->type() === 'payment.succeeded') {
    Order::where('yoon_payment_id', $event->object()['id'])->update(['status' => 'paid']);
}
```

The `yoon.webhook` middleware rejects bad signatures and stale timestamps (401), answers
already-handled events with 200 without calling your controller, and remembers an event only
after your controller answered 2xx. Events are unordered: act on the state in `object()`.

Outside Laravel: `Yoon\Webhook\Signature::verify($secret, $header, $rawBody)` and
`Yoon\Webhook\Event::fromJson($rawBody)`.

## Symfony

The bundle ships in this package (`Yoon\Symfony`); Symfony components are not a dependency of
it, so Laravel applications never pull them. Register the bundle:

```php
// config/bundles.php
return [
    // ...
    Yoon\Symfony\YoonBundle::class => ['all' => true],
];
```

```yaml
# config/packages/yoon.yaml
yoon:
    url: '%env(YOON_URL)%'
    api_key: '%env(YOON_API_KEY)%'
    webhook_secret: '%env(YOON_WEBHOOK_SECRET)%'  # YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side
    # timeout: 30                       # seconds
    # webhook:
    #     cache: cache.app              # any PSR-6 pool service
    #     dedupe_seconds: 259200        # how long a handled event id is remembered
```

`Yoon\Yoon` is autowirable:

```php
public function checkout(Order $order, Yoon $yoon): Response
{
    $payment = $yoon->createPayment([...], 'order-' . $order->getId());
    return $this->redirect($payment->getCheckoutUrl());
}
```

### Receiving Yoon's events

Put `#[YoonWebhook]` on the controller (method or class) and type an argument `Yoon\Webhook\Event`:

```php
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Yoon\Symfony\Attribute\YoonWebhook;
use Yoon\Webhook\Event;

final class YoonWebhookController
{
    #[Route('/yoon/webhook', methods: ['POST'])]
    #[YoonWebhook]
    public function __invoke(Event $event): Response
    {
        if ($event->type() === 'payment.succeeded') {
            // mark the order paid, using $event->object()['id']
        }
        return new Response('', 204);
    }
}
```

Like the Laravel middleware, the bundle checks the signature over the raw body (401 if wrong or
older than 300 s), answers already-handled events with 200 without calling your controller, and
remembers an event id in the cache pool only after your controller answered 2xx: a controller
that fails or throws gets the retry. Keep the route outside any firewall that requires a login
or CSRF token. With more than one server, point `webhook.cache` at a shared pool (Redis, …).

## Layout

- `generated/` — generated from `api/openapi.yaml` by `clients/generate.sh`. Never edit by hand;
  CI fails if it drifts from the contract.
- `src/` — the hand-written layer: `Yoon`, `YoonException`, webhook helpers, Laravel integration
  (`src/Laravel`), Symfony bundle (`src/Symfony`).

```sh
composer install && vendor/bin/phpunit
```
