<?php

declare(strict_types=1);

namespace Yoon;

use GuzzleHttp\Client as Guzzle;
use GuzzleHttp\ClientInterface;
use GuzzleHttp\RequestOptions;
use Yoon\Generated\ApiException;
use Yoon\Generated\Api\EventsApi;
use Yoon\Generated\Api\ExportsApi;
use Yoon\Generated\Api\LedgerApi;
use Yoon\Generated\Api\MetaApi;
use Yoon\Generated\Api\PaymentsApi;
use Yoon\Generated\Api\PayoutsApi;
use Yoon\Generated\Api\RefundsApi;
use Yoon\Generated\Configuration;
use Yoon\Generated\Model\CreatePaymentRequest;
use Yoon\Generated\Model\CreatePayoutRequest;
use Yoon\Generated\Model\CreateRefundRequest;
use Yoon\Generated\Model\Payment;
use Yoon\Generated\Model\Payout;
use Yoon\Generated\Model\Refund;

/**
 * Entry point. The common calls have short helpers; everything else is on the generated API
 * objects (`$yoon->payments()`, `->refunds()`, `->payouts()`, `->events()`, `->ledger()`,
 * `->exports()`, `->meta()`), which cover every operation in the API contract.
 *
 * Every create call needs an idempotency key. Use something tied to your intent — your order id —
 * so that a retry after a timeout returns the original result instead of charging twice.
 */
final class Yoon
{
    private readonly Configuration $config;
    private readonly ClientInterface $http;

    /**
     * @param string $baseUrl e.g. https://pay.example.com
     * @param string $apiKey  the application key (yk_…)
     */
    public function __construct(string $baseUrl, string $apiKey, ?ClientInterface $http = null, float $timeoutSeconds = 30.0)
    {
        $this->config = (new Configuration())
            ->setHost(rtrim($baseUrl, '/'))
            ->setAccessToken($apiKey)
            ->setUserAgent('yoon-php');
        $this->http = $http ?? new Guzzle([RequestOptions::TIMEOUT => $timeoutSeconds, RequestOptions::CONNECT_TIMEOUT => 5]);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @param array<string, mixed>|CreatePaymentRequest $payment e.g. ['amount' => 5000, 'currency' => 'XOF',
     *        'country' => 'SN', 'method' => 'wave', 'customer' => ['phone' => '+221771234567'], 'reference' => 'order_1042']
     */
    public function createPayment(array|CreatePaymentRequest $payment, string $idempotencyKey): Payment
    {
        $request = $payment instanceof CreatePaymentRequest ? $payment : new CreatePaymentRequest($payment);
        return $this->call(fn () => $this->payments()->createPayment($idempotencyKey, $request));
    }

    public function getPayment(string $id): Payment
    {
        return $this->call(fn () => $this->payments()->getPayment($id));
    }

    /** @param int|null $amount null refunds whatever is left */
    public function refund(string $paymentId, string $idempotencyKey, ?int $amount = null, ?string $reason = null): Refund
    {
        $request = new CreateRefundRequest(array_filter(['amount' => $amount, 'reason' => $reason], fn ($v) => $v !== null));
        return $this->call(fn () => $this->refunds()->createRefund($paymentId, $idempotencyKey, $request));
    }

    /** @param array<string, mixed>|CreatePayoutRequest $payout */
    public function createPayout(array|CreatePayoutRequest $payout, string $idempotencyKey): Payout
    {
        $request = $payout instanceof CreatePayoutRequest ? $payout : new CreatePayoutRequest($payout);
        return $this->call(fn () => $this->payouts()->createPayout($idempotencyKey, $request));
    }

    /**
     * A CSV export: amounts in minor units, UTC timestamps, phones masked.
     *
     * @param string $export payments, refunds, payouts or ledger
     */
    public function exportCsv(string $export, ?\DateTimeInterface $from = null, ?\DateTimeInterface $to = null): string
    {
        $api = $this->exports();
        return $this->call(fn () => match ($export) {
            'payments' => $api->exportPayments($from, $to),
            'refunds' => $api->exportRefunds($from, $to),
            'payouts' => $api->exportPayouts($from, $to),
            'ledger' => $api->exportLedger($from, $to),
            default => throw new \InvalidArgumentException("Unknown export '$export'"),
        });
    }

    /**
     * Runs any generated API call and turns Yoon's problem+json errors into {@see YoonException}.
     *
     * @template T
     * @param callable(): T $call
     * @return T
     */
    public function call(callable $call): mixed
    {
        try {
            return $call();
        } catch (ApiException $e) {
            $body = $e->getResponseBody();
            $problem = is_string($body) ? json_decode($body, true) : (is_object($body) ? json_decode(json_encode($body), true) : null);
            $problem = is_array($problem) ? $problem : null;
            $status = $e->getCode();
            if ($status === 0) {
                throw new YoonException('Yoon unreachable: ' . $e->getMessage(), 0, null, null, $e);
            }
            throw new YoonException($problem['detail'] ?? $e->getMessage(), $status, $problem['code'] ?? null, $problem, $e);
        }
    }

    // ------------------------------------------------------------------ generated APIs

    public function payments(): PaymentsApi
    {
        return new PaymentsApi($this->http, $this->config);
    }

    public function refunds(): RefundsApi
    {
        return new RefundsApi($this->http, $this->config);
    }

    public function payouts(): PayoutsApi
    {
        return new PayoutsApi($this->http, $this->config);
    }

    public function events(): EventsApi
    {
        return new EventsApi($this->http, $this->config);
    }

    public function ledger(): LedgerApi
    {
        return new LedgerApi($this->http, $this->config);
    }

    public function exports(): ExportsApi
    {
        return new ExportsApi($this->http, $this->config);
    }

    public function meta(): MetaApi
    {
        return new MetaApi($this->http, $this->config);
    }
}
