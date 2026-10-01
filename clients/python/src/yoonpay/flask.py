"""Flask integration: ``pip install yoonpay[flask]``.

::

    app.config["YOON_WEBHOOK_SECRET"] = os.environ["YOON_WEBHOOK_SECRET"]

    @app.post("/yoon/webhook")
    @yoon_webhook
    def yoon_events(event):
        if event.type == "payment.succeeded":
            ...
        return "", 204

The decorator verifies the signature over the raw body (``request.get_data()``, 401),
answers an already-handled event with 200 without calling the view, and remembers the id
only after the view answered 2xx.
"""

from __future__ import annotations

import functools
from typing import Any, Callable, Optional

from flask import Response, current_app, jsonify, request

from yoonpay.webhook import SIGNATURE_HEADER, EventStore, MemoryEventStore, check_delivery

View = Callable[..., Any]


def _default_store() -> EventStore:
    store = current_app.extensions.get("yoonpay_event_store")
    if store is None:
        store = current_app.extensions["yoonpay_event_store"] = MemoryEventStore()
    return store


def yoon_webhook(
    view: Optional[View] = None, *, secret: Optional[str] = None, store: Optional[EventStore] = None
) -> Any:
    """Decorates a view ``(event, *args, **kwargs)``. ``secret`` defaults to
    ``app.config["YOON_WEBHOOK_SECRET"]``; ``store`` to an in-process
    :class:`~yoonpay.MemoryEventStore` per app — with several workers, pass a
    :class:`~yoonpay.CacheEventStore` over a shared cache (e.g. Flask-Caching)."""

    def decorate(view: View) -> View:
        @functools.wraps(view)
        def wrapper(*args: Any, **kwargs: Any) -> Response:
            events = store if store is not None else _default_store()
            verdict = check_delivery(
                request.get_data(cache=True),
                request.headers.get(SIGNATURE_HEADER),
                secret if secret is not None else current_app.config.get("YOON_WEBHOOK_SECRET"),
                events,
            )
            if verdict.event is None:
                response = jsonify(verdict.body)
                response.status_code = verdict.status
                return response
            response = current_app.make_response(view(verdict.event, *args, **kwargs))
            if 200 <= response.status_code < 300:
                events.add(verdict.event.id)
            return response

        return wrapper

    return decorate(view) if view is not None else decorate
