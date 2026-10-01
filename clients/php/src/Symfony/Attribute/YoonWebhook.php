<?php

declare(strict_types=1);

namespace Yoon\Symfony\Attribute;

use Attribute;

/**
 * Marks a controller (class or method) as the endpoint Yoon posts events to. The bundle then
 * verifies the `Yoon-Signature` over the raw body (401 if wrong or stale), answers an
 * already-handled event with 200 without calling the controller, injects the verified
 * {@see \Yoon\Webhook\Event} as a controller argument, and remembers the event id only after
 * the controller answered 2xx — a failed handling is delivered again.
 */
#[Attribute(Attribute::TARGET_CLASS | Attribute::TARGET_METHOD)]
final class YoonWebhook
{
}
