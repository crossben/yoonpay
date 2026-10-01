"""Django integration: ``pip install yoonpay[django]``.

::

    # settings.py
    YOON_WEBHOOK_SECRET = os.environ["YOON_WEBHOOK_SECRET"]

    # views.py
    from yoonpay.django import yoon_webhook

    @yoon_webhook
    def yoon_events(request, event):
        if event.type == "payment.succeeded":
            ...
        return HttpResponse(status=204)

The decorator exempts the view from CSRF, verifies the signature over ``request.body`` (401),
answers an already-handled event with 200 without calling the view, and remembers the id in
Django's cache only after the view answered 2xx.
"""

from __future__ import annotations

import functools
from typing import Any, Callable, Optional

from django.conf import settings
from django.http import HttpRequest, HttpResponse, HttpResponseNotAllowed, JsonResponse
from django.views.decorators.csrf import csrf_exempt

from yoonpay.webhook import DEDUPE_SECONDS, SIGNATURE_HEADER, CacheEventStore, EventStore, check_delivery

View = Callable[..., HttpResponse]


def _default_store() -> EventStore:
    from django.core.cache import caches

    alias = getattr(settings, "YOON_WEBHOOK_CACHE", "default")
    ttl = int(getattr(settings, "YOON_WEBHOOK_DEDUPE_SECONDS", DEDUPE_SECONDS))
    return CacheEventStore(caches[alias], ttl)


def yoon_webhook(
    view: Optional[View] = None, *, secret: Optional[str] = None, store: Optional[EventStore] = None
) -> Any:
    """Decorates a view ``(request, event, *args, **kwargs)``. ``secret`` defaults to
    ``settings.YOON_WEBHOOK_SECRET``; ``store`` to Django's ``default`` cache (or the alias in
    ``settings.YOON_WEBHOOK_CACHE``)."""

    def decorate(view: View) -> View:
        @csrf_exempt
        @functools.wraps(view)
        def wrapper(request: HttpRequest, *args: Any, **kwargs: Any) -> HttpResponse:
            if request.method != "POST":
                return HttpResponseNotAllowed(["POST"])
            events = store or _default_store()
            verdict = check_delivery(
                request.body,
                request.headers.get(SIGNATURE_HEADER),
                secret if secret is not None else getattr(settings, "YOON_WEBHOOK_SECRET", None),
                events,
            )
            if verdict.event is None:
                return JsonResponse(verdict.body, status=verdict.status)
            response = view(request, verdict.event, *args, **kwargs)
            if 200 <= response.status_code < 300:
                events.add(verdict.event.id)
            return response

        return wrapper

    return decorate(view) if view is not None else decorate
