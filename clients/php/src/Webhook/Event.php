<?php

declare(strict_types=1);

namespace Yoon\Webhook;

use InvalidArgumentException;

/**
 * A verified event from Yoon. Delivery is at-least-once and unordered: deduplicate on `id()` and
 * act on the state in `object()` (or re-fetch it), not on arrival order.
 */
final class Event
{
    /** @param array<string, mixed> $payload */
    private function __construct(private readonly array $payload)
    {
    }

    public static function fromJson(string $rawBody): self
    {
        $payload = json_decode($rawBody, true);
        if (!is_array($payload) || !isset($payload['id'], $payload['type'], $payload['data']['object'])) {
            throw new InvalidArgumentException('Not a Yoon event');
        }
        return new self($payload);
    }

    public function id(): string
    {
        return $this->payload['id'];
    }

    /** e.g. payment.succeeded, payment.failed, refund.succeeded, payout.paid, payout.needs_review */
    public function type(): string
    {
        return $this->payload['type'];
    }

    /** @return array<string, mixed> the payment, refund or payout as the API returns it */
    public function object(): array
    {
        return $this->payload['data']['object'];
    }

    /** @return array<string, mixed> */
    public function toArray(): array
    {
        return $this->payload;
    }
}
