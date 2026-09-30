<?php

declare(strict_types=1);

namespace Yoon\Laravel\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static \Yoon\Generated\Model\Payment createPayment(array|\Yoon\Generated\Model\CreatePaymentRequest $payment, string $idempotencyKey)
 * @method static \Yoon\Generated\Model\Payment getPayment(string $id)
 * @method static \Yoon\Generated\Model\Refund refund(string $paymentId, string $idempotencyKey, ?int $amount = null, ?string $reason = null)
 * @method static \Yoon\Generated\Model\Payout createPayout(array|\Yoon\Generated\Model\CreatePayoutRequest $payout, string $idempotencyKey)
 * @method static \Yoon\Generated\Api\PaymentsApi payments()
 * @method static \Yoon\Generated\Api\RefundsApi refunds()
 * @method static \Yoon\Generated\Api\PayoutsApi payouts()
 * @method static \Yoon\Generated\Api\EventsApi events()
 * @method static mixed call(callable $call)
 *
 * @see \Yoon\Yoon
 */
final class Yoon extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Yoon\Yoon::class;
    }
}
