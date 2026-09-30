<?php

declare(strict_types=1);

namespace Yoon\Webhook;

/**
 * Verifies `Yoon-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>`.
 * Always pass the raw request body, not re-encoded JSON.
 */
final class Signature
{
    public const HEADER = 'Yoon-Signature';
    public const TOLERANCE_SECONDS = 300;

    public static function verify(string $secret, ?string $header, string $rawBody, ?int $now = null, int $tolerance = self::TOLERANCE_SECONDS): bool
    {
        if ($secret === '' || $header === null || $header === '') {
            return false;
        }
        $timestamp = null;
        $signature = null;
        foreach (explode(',', $header) as $part) {
            [$key, $value] = array_pad(explode('=', trim($part), 2), 2, null);
            if ($key === 't' && $value !== null && ctype_digit($value)) {
                $timestamp = (int) $value;
            } elseif ($key === 'v1') {
                $signature = $value;
            }
        }
        if ($timestamp === null || $signature === null) {
            return false;
        }
        if (abs(($now ?? time()) - $timestamp) > $tolerance) {
            return false;
        }
        $expected = hash_hmac('sha256', $timestamp . '.' . $rawBody, $secret);
        return hash_equals($expected, strtolower($signature));
    }

    /** Builds a header — for tests of your own webhook handler. */
    public static function sign(string $secret, string $rawBody, ?int $timestamp = null): string
    {
        $t = $timestamp ?? time();
        return 't=' . $t . ',v1=' . hash_hmac('sha256', $t . '.' . $rawBody, $secret);
    }
}
