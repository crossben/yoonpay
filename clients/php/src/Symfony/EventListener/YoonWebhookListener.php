<?php

declare(strict_types=1);

namespace Yoon\Symfony\EventListener;

use Psr\Cache\CacheItemPoolInterface;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpKernel\Event\ControllerEvent;
use Symfony\Component\HttpKernel\Event\ResponseEvent;
use Yoon\Symfony\Attribute\YoonWebhook;
use Yoon\Webhook\Event;
use Yoon\Webhook\Signature;

/**
 * Applies {@see YoonWebhook}: verifies the signature before the controller runs and remembers
 * the event id once the response is known to be 2xx. Ids are kept in a PSR-6 pool (the
 * application's `cache.app` by default) so duplicates are dropped across processes.
 */
final class YoonWebhookListener
{
    /** Request attribute holding the verified {@see Event}. */
    public const EVENT_ATTRIBUTE = 'yoon_event';
    private const PENDING_ATTRIBUTE = '_yoon_event_pending';

    public function __construct(
        private readonly ?string $secret,
        private readonly CacheItemPoolInterface $cache,
        private readonly int $dedupeSeconds,
    ) {
    }

    public function onController(ControllerEvent $event): void
    {
        if (!$event->isMainRequest() || ($event->getAttributes()[YoonWebhook::class] ?? []) === []) {
            return;
        }
        $request = $event->getRequest();
        $raw = $request->getContent();

        if (!Signature::verify((string) $this->secret, $request->headers->get(Signature::HEADER), $raw)) {
            $event->setController(static fn () => new JsonResponse(['error' => 'invalid signature'], 401));
            return;
        }
        try {
            $yoonEvent = Event::fromJson($raw);
        } catch (\InvalidArgumentException) {
            $event->setController(static fn () => new JsonResponse(['error' => 'not a Yoon event'], 400));
            return;
        }
        if ($this->cache->hasItem(self::key($yoonEvent->id()))) {
            $event->setController(static fn () => new JsonResponse(['duplicate' => true]));
            return;
        }
        $request->attributes->set(self::EVENT_ATTRIBUTE, $yoonEvent);
        $request->attributes->set(self::PENDING_ATTRIBUTE, true);
    }

    public function onResponse(ResponseEvent $event): void
    {
        $request = $event->getRequest();
        if (!$event->isMainRequest() || !$request->attributes->get(self::PENDING_ATTRIBUTE)) {
            return;
        }
        $request->attributes->remove(self::PENDING_ATTRIBUTE);
        $yoonEvent = $request->attributes->get(self::EVENT_ATTRIBUTE);
        if ($yoonEvent instanceof Event && $event->getResponse()->isSuccessful()) {
            $item = $this->cache->getItem(self::key($yoonEvent->id()));
            $this->cache->save($item->set(true)->expiresAfter($this->dedupeSeconds));
        }
    }

    /** PSR-6 keys allow a limited alphabet; the id comes from a signed body but is hashed anyway. */
    private static function key(string $eventId): string
    {
        return 'yoon_event_' . hash('sha256', $eventId);
    }
}
