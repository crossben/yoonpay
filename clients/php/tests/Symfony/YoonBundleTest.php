<?php

declare(strict_types=1);

namespace Yoon\Tests\Symfony;

use PHPUnit\Framework\TestCase;
use Symfony\Component\Filesystem\Filesystem;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Yoon\Webhook\Signature;
use Yoon\Yoon;

final class YoonBundleTest extends TestCase
{
    private TestKernel $kernel;
    /** @var callable|null */
    private $exceptionHandler;

    protected function setUp(): void
    {
        $this->exceptionHandler = self::currentExceptionHandler();
        $this->kernel = new TestKernel();
        $this->kernel->boot();
        WebhookController::$reached = [];
    }

    protected function tearDown(): void
    {
        $this->kernel->shutdown();
        (new Filesystem())->remove(\dirname($this->kernel->getCacheDir()));
        // Symfony 6.4's FrameworkBundle installs an exception handler on boot and leaves it.
        for ($i = 0; $i < 10 && self::currentExceptionHandler() !== $this->exceptionHandler; $i++) {
            restore_exception_handler();
        }
    }

    private static function currentExceptionHandler(): ?callable
    {
        $handler = set_exception_handler(null);
        restore_exception_handler();
        return $handler;
    }

    private function deliver(string $body, ?string $signature = null, string $path = '/yoon/webhook'): Response
    {
        $request = Request::create($path, 'POST', [], [], [], [
            'CONTENT_TYPE' => 'application/json',
            'HTTP_YOON_SIGNATURE' => $signature ?? Signature::sign(TestKernel::SECRET, $body),
        ], $body);
        $response = $this->kernel->handle($request, HttpKernelInterface::MAIN_REQUEST, true);
        $this->kernel->terminate($request, $response);
        return $response;
    }

    private static function event(string $id, string $paymentId = 'pay_1'): string
    {
        return json_encode(['id' => $id, 'object' => 'event', 'type' => 'payment.succeeded',
            'created_at' => '2026-09-30T10:00:00Z', 'data' => ['object' => ['id' => $paymentId, 'status' => 'succeeded']]]);
    }

    public function testAVerifiedEventReachesTheHandlerAsAnArgument(): void
    {
        $response = $this->deliver(self::event('evt_1'));

        self::assertSame(200, $response->getStatusCode());
        self::assertSame(['evt_1'], WebhookController::$reached);
    }

    public function testABadSignatureIsRejected(): void
    {
        $response = $this->deliver(self::event('evt_2'), 't=' . time() . ',v1=' . str_repeat('0', 64));

        self::assertSame(401, $response->getStatusCode());
        self::assertSame([], WebhookController::$reached);
    }

    public function testAStaleSignatureIsRejected(): void
    {
        $body = self::event('evt_stale');
        $response = $this->deliver($body, Signature::sign(TestKernel::SECRET, $body, time() - 301));

        self::assertSame(401, $response->getStatusCode());
        self::assertSame([], WebhookController::$reached);
    }

    public function testAReEncodedBodyIsRejected(): void
    {
        $body = self::event('evt_reencoded');
        $signature = Signature::sign(TestKernel::SECRET, $body);
        $reencoded = json_encode(json_decode($body, true), JSON_PRETTY_PRINT);

        self::assertSame(401, $this->deliver($reencoded, $signature)->getStatusCode());
    }

    public function testADuplicateDeliveryIsAcknowledgedWithoutHandlingItTwice(): void
    {
        self::assertSame(200, $this->deliver(self::event('evt_3'))->getStatusCode());
        $second = $this->deliver(self::event('evt_3'));

        self::assertSame(200, $second->getStatusCode());
        self::assertSame(['duplicate' => true], json_decode((string) $second->getContent(), true));
        self::assertSame(['evt_3'], WebhookController::$reached);
    }

    public function testAFailedHandlingIsNotRememberedSoTheRetryIsHandled(): void
    {
        self::assertSame(500, $this->deliver(self::event('evt_4', 'pay_fail'))->getStatusCode());
        self::assertSame(500, $this->deliver(self::event('evt_4', 'pay_fail'))->getStatusCode());

        self::assertSame(['evt_4', 'evt_4'], WebhookController::$reached);
    }

    public function testACrashingHandlerIsNotRememberedEither(): void
    {
        self::assertSame(500, $this->deliver(self::event('evt_5', 'pay_throw'))->getStatusCode());
        self::assertSame(500, $this->deliver(self::event('evt_5', 'pay_throw'))->getStatusCode());

        self::assertSame(['evt_5', 'evt_5'], WebhookController::$reached);
    }

    public function testRoutesWithoutTheAttributeAreLeftAlone(): void
    {
        $response = $this->deliver('{}', 'nonsense', '/plain');

        self::assertSame(200, $response->getStatusCode());
    }

    public function testTheClientIsConfiguredAndAutowirable(): void
    {
        $yoon = $this->kernel->getContainer()->get('yoon');

        self::assertInstanceOf(Yoon::class, $yoon);
        self::assertSame($yoon, $this->kernel->getContainer()->get('test.service_container')->get(Yoon::class));
    }
}
