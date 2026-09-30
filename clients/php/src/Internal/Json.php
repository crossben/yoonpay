<?php

declare(strict_types=1);

namespace Yoon\Internal;

/**
 * Stand-in for GuzzleHttp\Utils::jsonEncode (removed in Guzzle 8), used by the generated code so
 * the package works with Guzzle 7 and 8. clients/generate.sh rewrites the calls.
 */
final class Json
{
    public static function encode(mixed $value, int $options = 0, int $depth = 512): string
    {
        return json_encode($value, $options | JSON_THROW_ON_ERROR, $depth);
    }
}
