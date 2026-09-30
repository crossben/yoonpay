<?php

declare(strict_types=1);

namespace Yoon\Laravel\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Cache;
use Symfony\Component\HttpFoundation\Response;
use Yoon\Webhook\Event;
use Yoon\Webhook\Signature;

/**
 * Route middleware (`yoon.webhook`) for the endpoint Yoon posts events to:
 *
 *     Route::post('/yoon/webhook', YoonWebhookController::class)->middleware('yoon.webhook');
 *
 * Rejects deliveries whose signature or timestamp is wrong (401), answers already-handled events
 * with 200 without calling your controller, and puts the parsed {@see Event} on the request:
 * `$request->attributes->get('yoon_event')`. An event is remembered only after your controller
 * answered 2xx, so a failed handling is delivered again.
 */
final class VerifyYoonWebhook
{
    public function handle(Request $request, Closure $next): Response
    {
        $raw = $request->getContent();
        $secret = (string) config('yoon.webhook_secret');
        if (!Signature::verify($secret, $request->header(Signature::HEADER), $raw)) {
            return response()->json(['error' => 'invalid signature'], 401);
        }

        try {
            $event = Event::fromJson($raw);
        } catch (\InvalidArgumentException) {
            return response()->json(['error' => 'not a Yoon event'], 400);
        }

        $key = 'yoon:event:' . $event->id();
        if (Cache::has($key)) {
            return response()->json(['duplicate' => true]);
        }

        $request->attributes->set('yoon_event', $event);
        $response = $next($request);

        if ($response->getStatusCode() >= 200 && $response->getStatusCode() < 300) {
            Cache::put($key, true, (int) config('yoon.dedupe_seconds', 259200));
        }
        return $response;
    }
}
