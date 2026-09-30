<?php

declare(strict_types=1);

namespace Yoon\Tests;

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;
use Orchestra\Testbench\TestCase;
use Yoon\Laravel\YoonServiceProvider;
use Yoon\Webhook\Event;
use Yoon\Webhook\Signature;

final class VerifyYoonWebhookTest extends TestCase
{
    private const SECRET = 'laravel-test-webhook-secret-0123456789';

    /** @var list<string> */
    public static array $handled = [];

    protected function getPackageProviders($app): array
    {
        return [YoonServiceProvider::class];
    }

    protected function defineEnvironment($app): void
    {
        $app['config']->set('yoon.webhook_secret', self::SECRET);
        $app['config']->set('yoon.api_key', 'yk_test');
        $app['config']->set('cache.default', 'array');
    }

    protected function defineRoutes($router): void
    {
        Route::post('/yoon/webhook', function (Request $request) {
            /** @var Event $event */
            $event = $request->attributes->get('yoon_event');
            if ($event->object()['id'] === 'pay_fail') {
                return response()->json(['error' => 'handler failed'], 500);
            }
            self::$handled[] = $event->id();
            return response()->json(['ok' => true]);
        })->middleware('yoon.webhook');
    }

    protected function setUp(): void
    {
        parent::setUp();
        self::$handled = [];
    }

    private function deliver(string $body, ?string $signature = null)
    {
        return $this->call('POST', '/yoon/webhook', [], [], [], [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_YOON_SIGNATURE' => $signature ?? Signature::sign(self::SECRET, $body),
        ], $body);
    }

    private static function event(string $id, string $paymentId = 'pay_1'): string
    {
        return json_encode(['id' => $id, 'object' => 'event', 'type' => 'payment.succeeded',
            'created_at' => '2026-09-30T10:00:00Z', 'data' => ['object' => ['id' => $paymentId, 'status' => 'succeeded']]]);
    }

    public function testAVerifiedEventReachesTheHandler(): void
    {
        $this->deliver(self::event('evt_1'))->assertOk();

        self::assertSame(['evt_1'], self::$handled);
    }

    public function testABadSignatureIsRejected(): void
    {
        $this->deliver(self::event('evt_2'), 't=' . time() . ',v1=' . str_repeat('0', 64))->assertStatus(401);

        self::assertSame([], self::$handled);
    }

    public function testADuplicateDeliveryIsAcknowledgedWithoutHandlingItTwice(): void
    {
        $this->deliver(self::event('evt_3'))->assertOk();
        $this->deliver(self::event('evt_3'))->assertOk()->assertJson(['duplicate' => true]);

        self::assertSame(['evt_3'], self::$handled);
    }

    public function testAFailedHandlingIsNotRememberedSoTheRetryIsHandled(): void
    {
        $this->deliver(self::event('evt_4', 'pay_fail'))->assertStatus(500);
        $this->deliver(self::event('evt_4', 'pay_fail'))->assertStatus(500);

        self::assertSame([], self::$handled, 'both deliveries reached the handler');
    }

    public function testTheFacadeResolvesAConfiguredClient(): void
    {
        self::assertInstanceOf(\Yoon\Yoon::class, \Yoon\Laravel\Facades\Yoon::getFacadeRoot());
    }
}
