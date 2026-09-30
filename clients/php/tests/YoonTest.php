<?php

declare(strict_types=1);

namespace Yoon\Tests;

use GuzzleHttp\Client;
use GuzzleHttp\Exception\ConnectException;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Request;
use GuzzleHttp\Psr7\Response;
use PHPUnit\Framework\TestCase;
use Yoon\Yoon;
use Yoon\YoonException;

final class YoonTest extends TestCase
{
    /** @var list<array{request: \Psr\Http\Message\RequestInterface}> */
    private array $sent = [];

    private function yoon(Response|\Throwable ...$responses): Yoon
    {
        $stack = HandlerStack::create(new MockHandler($responses));
        $stack->push(Middleware::history($this->sent));
        return new Yoon('https://pay.example.com/', 'yk_test', new Client(['handler' => $stack]));
    }

    private const PAYMENT = '{"id":"pay_1","object":"payment","status":"pending","amount":5000,"amount_refunded":0,'
        . '"currency":"XOF","country":"SN","method":"wave","reference":"order_1042","description":null,'
        . '"customer":{"phone":"+22177***67"},"provider":"paydunya","provider_reference":"tok_1",'
        . '"checkout_url":"https://app.paydunya.com/checkout/tok_1","instructions":null,"routing_reason":"r",'
        . '"failure":null,"created_at":"2026-09-30T10:00:00Z","updated_at":"2026-09-30T10:00:00Z"}';

    public function testCreatePaymentSendsTheKeyAndSnakeCaseJson(): void
    {
        $payment = $this->yoon(new Response(201, ['Content-Type' => 'application/json'], self::PAYMENT))
            ->createPayment([
                'amount' => 5000, 'currency' => 'XOF', 'country' => 'SN', 'method' => 'wave',
                'customer' => ['phone' => '+221771234567'], 'reference' => 'order_1042',
                'return_url' => 'https://shop.example/orders/1042',
            ], 'order-1042');

        self::assertSame('pending', $payment->getStatus());
        self::assertSame(5000, $payment->getAmount());
        self::assertSame('https://app.paydunya.com/checkout/tok_1', $payment->getCheckoutUrl());
        self::assertNull($payment->getFailure());

        $request = $this->sent[0]['request'];
        self::assertSame('POST', $request->getMethod());
        self::assertSame('https://pay.example.com/v1/payments', (string) $request->getUri());
        self::assertSame('Bearer yk_test', $request->getHeaderLine('Authorization'));
        self::assertSame('order-1042', $request->getHeaderLine('Idempotency-Key'));
        $body = json_decode((string) $request->getBody(), true);
        self::assertSame(5000, $body['amount']);
        self::assertSame('https://shop.example/orders/1042', $body['return_url']);
        self::assertSame('+221771234567', $body['customer']['phone']);
    }

    public function testProblemsBecomeYoonExceptionsWithTheirCode(): void
    {
        $yoon = $this->yoon(new Response(422, ['Content-Type' => 'application/problem+json'],
            '{"type":"https://yoonpay.dev/problems/no_provider_for_method","title":"Unprocessable Content","status":422,'
            . '"detail":"No configured provider supports collect by bitcoin in SN (XOF)","code":"no_provider_for_method"}'));

        try {
            $yoon->createPayment(['amount' => 5000, 'currency' => 'XOF', 'country' => 'SN', 'method' => 'bitcoin'], 'k');
            self::fail('expected an exception');
        } catch (YoonException $e) {
            self::assertSame(422, $e->httpStatus());
            self::assertSame('no_provider_for_method', $e->problemCode());
            self::assertStringContainsString('bitcoin', $e->getMessage());
            self::assertFalse($e->isRetryable());
        }
    }

    public function testAnUnreachableServerIsRetryable(): void
    {
        $yoon = $this->yoon(new ConnectException('refused', new Request('POST', 'x')));

        try {
            $yoon->getPayment('pay_1');
            self::fail('expected an exception');
        } catch (YoonException $e) {
            self::assertNull($e->problemCode());
            self::assertTrue($e->isRetryable());
        }
    }

    public function testRefundSendsOnlyWhatIsGiven(): void
    {
        $refund = '{"id":"re_1","object":"refund","payment_id":"pay_1","status":"pending","amount":2000,"currency":"XOF",'
            . '"reason":null,"provider":"fake","provider_reference":null,"failure":null,'
            . '"created_at":"2026-09-30T10:00:00Z","updated_at":"2026-09-30T10:00:00Z"}';

        $r = $this->yoon(new Response(201, ['Content-Type' => 'application/json'], $refund))->refund('pay_1', 'refund-1', 2000);

        self::assertSame(2000, $r->getAmount());
        self::assertSame('{"amount":2000}', (string) $this->sent[0]['request']->getBody());
    }

    public function testListPagesCarryTheCursor(): void
    {
        $page = '{"data":[' . self::PAYMENT . '],"has_more":true,"next_cursor":"pay_1"}';
        $yoon = $this->yoon(new Response(200, ['Content-Type' => 'application/json'], $page));

        $result = $yoon->call(fn () => $yoon->payments()->listPayments(reference: 'order_1042', limit: 1));

        self::assertTrue($result->getHasMore());
        self::assertSame('pay_1', $result->getNextCursor());
        self::assertCount(1, $result->getData());
        self::assertStringContainsString('reference=order_1042', (string) $this->sent[0]['request']->getUri());
    }

    public function testCsvExportsComeBackAsText(): void
    {
        $csv = "id,created_at,status\r\npay_1,2026-09-30T10:00:00Z,succeeded\r\n";
        $yoon = $this->yoon(new Response(200, ['Content-Type' => 'text/csv;charset=UTF-8'], $csv));

        self::assertSame($csv, $yoon->exportCsv('payments'));
        self::assertStringEndsWith('/v1/exports/payments.csv', (string) $this->sent[0]['request']->getUri());
    }
}
