<?php

namespace Tests\Feature;

use App\Models\Order;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Psr7\Response;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;
use Yoon\Webhook\Signature;
use Yoon\Yoon;

class ShopTest extends TestCase
{
    use RefreshDatabase;

    private const SECRET = 'shop-test-webhook-secret-0123456789';

    protected function setUp(): void
    {
        parent::setUp();
        config(['yoon.webhook_secret' => self::SECRET, 'yoon.api_key' => 'yk_test', 'cache.default' => 'array']);
    }

    private function yoonAnswers(Response $response): void
    {
        $http = new \GuzzleHttp\Client(['handler' => HandlerStack::create(new MockHandler([$response]))]);
        $this->app->instance(Yoon::class, new Yoon('http://yoon.test', 'yk_test', $http));
    }

    public function test_the_shop_page_shows_the_product(): void
    {
        $this->get('/')->assertOk()->assertSee('Thiéboudienne for two')->assertSee('5 000 FCFA');
    }

    public function test_buying_redirects_to_the_providers_checkout(): void
    {
        $this->yoonAnswers(new Response(201, ['Content-Type' => 'application/json'], json_encode([
            'id' => 'pay_1', 'object' => 'payment', 'status' => 'pending', 'amount' => 5000, 'amount_refunded' => 0,
            'currency' => 'XOF', 'country' => 'SN', 'method' => 'wave', 'reference' => 'order_1',
            'checkout_url' => 'https://checkout.example/pay_1', 'created_at' => '2026-09-30T10:00:00Z',
            'updated_at' => '2026-09-30T10:00:00Z',
        ])));

        $this->post('/checkout', ['phone' => '+221771234567', 'method' => 'wave'])
            ->assertRedirect('https://checkout.example/pay_1');

        $this->assertSame('pay_1', Order::first()->yoon_payment_id);
    }

    public function test_a_signed_payment_succeeded_event_marks_the_order_paid(): void
    {
        $order = Order::create(['product' => 'x', 'amount' => 5000, 'currency' => 'XOF', 'phone' => '+221771234567',
            'yoon_payment_id' => 'pay_9']);
        $body = json_encode(['id' => 'evt_1', 'object' => 'event', 'type' => 'payment.succeeded',
            'created_at' => '2026-09-30T10:00:00Z', 'data' => ['object' => ['id' => 'pay_9', 'object' => 'payment', 'status' => 'succeeded']]]);

        $this->call('POST', '/yoon/webhook', [], [], [], ['CONTENT_TYPE' => 'application/json',
            'HTTP_YOON_SIGNATURE' => Signature::sign(self::SECRET, $body)], $body)->assertOk();

        $this->assertSame('paid', $order->fresh()->status);
    }

    public function test_an_unsigned_event_changes_nothing(): void
    {
        $order = Order::create(['product' => 'x', 'amount' => 5000, 'currency' => 'XOF', 'phone' => '+221771234567',
            'yoon_payment_id' => 'pay_9']);
        $body = json_encode(['id' => 'evt_2', 'object' => 'event', 'type' => 'payment.succeeded',
            'created_at' => '2026-09-30T10:00:00Z', 'data' => ['object' => ['id' => 'pay_9', 'object' => 'payment', 'status' => 'succeeded']]]);

        $this->call('POST', '/yoon/webhook', [], [], [], ['CONTENT_TYPE' => 'application/json'], $body)->assertStatus(401);

        $this->assertSame('pending', $order->fresh()->status);
    }
}
