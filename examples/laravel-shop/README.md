# Example: a Laravel shop paying through Yoon

A one-product shop (Laravel 13) that takes payment through Yoon with
[`yoonpay/yoon-php`](../../clients/php). It runs end to end against Yoon's **demo provider** —
no provider account, no real money.

What it shows:

- `ShopController::checkout` — creates the payment with the order id as idempotency key, then
  redirects to the checkout page.
- `ShopController::webhook` behind the `yoon.webhook` middleware — verified, deduplicated events
  update the order.
- `tests/Feature/ShopTest.php` — the same flows with a mocked Yoon and signed events.

## Run it

1. Start Yoon in demo mode, from the repository root. In `.env`:

   ```dotenv
   POSTGRES_PASSWORD=change-me
   YOON_PUBLIC_URL=http://localhost:8080
   YOON_DEMO_ENABLED=true
   YOON_APPS_SHOP_PROVIDERS_DEMO_PRIORITY=1
   YOON_APPS_SHOP_WEBHOOK_URL=http://host.docker.internal:8010/yoon/webhook
   YOON_APPS_SHOP_WEBHOOK_SECRET=pick-a-secret-of-at-least-32-characters
   ```

   ```sh
   docker compose up --build -d
   docker compose run --rm yoon apps create shop     # copy the printed key
   docker compose restart yoon                       # picks up the app's provider settings
   ```

2. Start the shop:

   ```sh
   cd examples/laravel-shop
   composer install
   cp .env.example .env && php artisan key:generate && php artisan migrate
   # in .env: YOON_API_KEY=<the key>, YOON_WEBHOOK_SECRET=<the same secret as above>
   php artisan serve --host=0.0.0.0 --port=8010
   ```

3. Open <http://localhost:8010>, click **Buy**, then **Pay** on the demo checkout. You come back
   to the order page; Yoon's webhook marks it paid a few seconds later (refresh).

`--host=0.0.0.0` lets Yoon's container reach the shop through `host.docker.internal`.
