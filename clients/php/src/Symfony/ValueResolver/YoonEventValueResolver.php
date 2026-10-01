<?php

declare(strict_types=1);

namespace Yoon\Symfony\ValueResolver;

use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpKernel\Controller\ValueResolverInterface;
use Symfony\Component\HttpKernel\ControllerMetadata\ArgumentMetadata;
use Yoon\Symfony\EventListener\YoonWebhookListener;
use Yoon\Webhook\Event;

/** Injects the verified {@see Event} into a `#[YoonWebhook]` controller argument typed `Event`. */
final class YoonEventValueResolver implements ValueResolverInterface
{
    /** @return iterable<Event> */
    public function resolve(Request $request, ArgumentMetadata $argument): iterable
    {
        if ($argument->getType() !== Event::class) {
            return [];
        }
        $event = $request->attributes->get(YoonWebhookListener::EVENT_ATTRIBUTE);
        return $event instanceof Event ? [$event] : [];
    }
}
