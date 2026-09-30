<?php

declare(strict_types=1);

namespace Yoon\Tests;

use PHPUnit\Framework\TestCase;
use Yoon\Webhook\Signature;

final class SignatureTest extends TestCase
{
    /** @return array{secret: string, timestamp: int, body: string, header: string} */
    private static function vector(): array
    {
        return json_decode(file_get_contents(__DIR__ . '/../../../api/test-vectors/webhook-signature.json'), true);
    }

    public function testTheSharedVectorVerifies(): void
    {
        $v = self::vector();

        self::assertTrue(Signature::verify($v['secret'], $v['header'], $v['body'], $v['timestamp']));
        self::assertSame($v['header'], Signature::sign($v['secret'], $v['body'], $v['timestamp']));
    }

    public function testATamperedBodyOrWrongSecretFails(): void
    {
        $v = self::vector();

        self::assertFalse(Signature::verify($v['secret'], $v['header'], $v['body'] . ' ', $v['timestamp']));
        self::assertFalse(Signature::verify('another-secret', $v['header'], $v['body'], $v['timestamp']));
    }

    public function testStaleOrFutureTimestampsAreRejected(): void
    {
        $v = self::vector();

        self::assertFalse(Signature::verify($v['secret'], $v['header'], $v['body'], $v['timestamp'] + 301));
        self::assertFalse(Signature::verify($v['secret'], $v['header'], $v['body'], $v['timestamp'] - 301));
        self::assertTrue(Signature::verify($v['secret'], $v['header'], $v['body'], $v['timestamp'] + 299));
    }

    public function testMalformedHeadersAreRejected(): void
    {
        $v = self::vector();

        foreach ([null, '', 'garbage', 't=abc,v1=00', 'v1=' . str_repeat('0', 64), 't=' . $v['timestamp']] as $header) {
            self::assertFalse(Signature::verify($v['secret'], $header, $v['body'], $v['timestamp']));
        }
        self::assertFalse(Signature::verify('', $v['header'], $v['body'], $v['timestamp']));
    }
}
