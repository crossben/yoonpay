<?php

declare(strict_types=1);

namespace Yoon\Tests\Symfony;

use Symfony\Component\HttpFoundation\JsonResponse;
use Yoon\Symfony\Attribute\YoonWebhook;
use Yoon\Webhook\Event;

final class WebhookController
{
    /** @var list<string> ids of the events that reached the handler, including failed ones */
    public static array $reached = [];

    #[YoonWebhook]
    public function handle(Event $event): JsonResponse
    {
        self::$reached[] = $event->id();
        return match ($event->object()['id']) {
            'pay_fail' => new JsonResponse(['error' => 'handler failed'], 500),
            'pay_throw' => throw new \RuntimeException('handler crashed'),
            default => new JsonResponse(['ok' => true]),
        };
    }

    public function plain(): JsonResponse
    {
        return new JsonResponse(['plain' => true]);
    }
}
