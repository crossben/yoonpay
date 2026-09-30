<?php

declare(strict_types=1);

namespace Yoon;

use RuntimeException;
use Throwable;

/**
 * An error answered by Yoon (application/problem+json), or a transport failure.
 *
 * `problemCode()` is Yoon's stable, machine-readable code — e.g. `no_provider_for_method`,
 * `refund_exceeds_payment`, `idempotency_in_progress`. Null for transport failures, where the
 * outcome is unknown: retry with the same Idempotency-Key.
 */
final class YoonException extends RuntimeException
{
    public function __construct(
        string $message,
        private readonly int $httpStatus,
        private readonly ?string $problemCode,
        private readonly ?array $problem = null,
        ?Throwable $previous = null,
    ) {
        parent::__construct($message, $httpStatus, $previous);
    }

    public function httpStatus(): int
    {
        return $this->httpStatus;
    }

    public function problemCode(): ?string
    {
        return $this->problemCode;
    }

    /** @return array<string, mixed>|null the full problem document */
    public function problem(): ?array
    {
        return $this->problem;
    }

    /** True when retrying with the same Idempotency-Key is the right move. */
    public function isRetryable(): bool
    {
        return $this->problemCode === null
            || $this->problemCode === 'idempotency_in_progress'
            || $this->httpStatus >= 500;
    }
}
