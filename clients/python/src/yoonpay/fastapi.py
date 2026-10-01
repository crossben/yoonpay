"""FastAPI integration: ``pip install yoonpay[fastapi]``.

::

    from fastapi import APIRouter, Depends
    from yoonpay import Event
    from yoonpay.fastapi import yoon_event, yoon_webhook_route

    webhooks = APIRouter(route_class=yoon_webhook_route(secret=os.environ["YOON_WEBHOOK_SECRET"]))

    @webhooks.post("/yoon/webhook", status_code=204)
    def yoon_events(event: Event = Depends(yoon_event)) -> None:
        if event.type == "payment.succeeded":
            ...

    app.include_router(webhooks)

The route class verifies the signature over the raw body before FastAPI parses anything
(401), answers an already-handled event with 200 without calling the endpoint, and remembers
the id only after the endpoint answered 2xx: an endpoint that raises or answers an error gets
the retry. ``yoon_event`` is the dependency that hands the verified event to the endpoint.
"""

from __future__ import annotations

from typing import Any, Callable, Coroutine, Optional

from fastapi import Request, Response
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

from yoonpay.webhook import SIGNATURE_HEADER, Event, EventStore, MemoryEventStore, check_delivery


def yoon_webhook_route(secret: str, store: Optional[EventStore] = None) -> type[APIRoute]:
    """An ``APIRoute`` class for the routers that receive Yoon's events. ``store`` defaults to
    an in-process :class:`~yoonpay.MemoryEventStore`; with several workers, pass a
    :class:`~yoonpay.CacheEventStore` over a shared cache."""
    if not secret:
        raise ValueError("secret is required: the same value as YOON_APPS_<APP>_WEBHOOK_SECRET on the Yoon side")
    events: EventStore = store if store is not None else MemoryEventStore()

    class YoonWebhookRoute(APIRoute):
        def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
            endpoint = super().get_route_handler()

            async def handler(request: Request) -> Response:
                verdict = check_delivery(await request.body(), request.headers.get(SIGNATURE_HEADER), secret, events)
                if verdict.event is None:
                    return JSONResponse(verdict.body, status_code=verdict.status)
                request.state.yoon_event = verdict.event
                response = await endpoint(request)
                if 200 <= response.status_code < 300:
                    events.add(verdict.event.id)
                return response

            return handler

    return YoonWebhookRoute


def yoon_event(request: Request) -> Event:
    """Dependency: the verified event of a route built with :func:`yoon_webhook_route`."""
    event = getattr(request.state, "yoon_event", None)
    if not isinstance(event, Event):
        raise RuntimeError("yoon_event is only available on routes whose router uses yoon_webhook_route()")
    return event
