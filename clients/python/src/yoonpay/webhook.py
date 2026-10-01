"""Verifying and receiving Yoon's webhooks, independent of any web framework.

``Yoon-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, "<t>.<raw body>")>``.
Always verify the raw request body as received: a body parsed and re-encoded is rejected.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Optional, Protocol, Union

SIGNATURE_HEADER = "Yoon-Signature"
TOLERANCE_SECONDS = 300
DEDUPE_SECONDS = 259_200  # 3 days: longer than Yoon's retry schedule

RawBody = Union[bytes, bytearray, str]


def _bytes(raw: RawBody) -> bytes:
    return raw.encode("utf-8") if isinstance(raw, str) else bytes(raw)


def verify_signature(
    secret: str,
    header: Optional[str],
    raw_body: RawBody,
    now: Optional[int] = None,
    tolerance: int = TOLERANCE_SECONDS,
) -> bool:
    """True when ``header`` is a valid, fresh signature of ``raw_body`` with ``secret``."""
    if not secret or not header:
        return False
    timestamp: Optional[int] = None
    signature: Optional[str] = None
    for part in header.split(","):
        key, sep, value = part.strip().partition("=")
        if not sep:
            continue
        if key == "t":
            if not value.isdigit() or not value.isascii():
                return False
            timestamp = int(value)
        elif key == "v1":
            signature = value.lower()
    if timestamp is None or signature is None:
        return False
    if abs((int(time.time()) if now is None else now) - timestamp) > tolerance:
        return False
    expected = hmac.new(
        secret.encode("utf-8"), str(timestamp).encode("ascii") + b"." + _bytes(raw_body), hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, signature)


def sign_signature(secret: str, raw_body: RawBody, timestamp: Optional[int] = None) -> str:
    """Builds a ``Yoon-Signature`` header — for tests of your own webhook handler."""
    t = int(time.time()) if timestamp is None else timestamp
    digest = hmac.new(secret.encode("utf-8"), str(t).encode("ascii") + b"." + _bytes(raw_body), hashlib.sha256)
    return f"t={t},v1={digest.hexdigest()}"


@dataclass(frozen=True)
class Event:
    """A verified event from Yoon. Delivery is at-least-once and unordered: deduplicate on
    ``id`` and act on the state in ``object`` (or re-fetch it), not on arrival order.

    ``type`` is a plain string, so an event type added by a newer server never breaks parsing.
    """

    id: str
    type: str
    object: dict[str, Any]
    payload: dict[str, Any] = field(repr=False)

    @classmethod
    def from_json(cls, raw_body: RawBody) -> "Event":
        try:
            payload = json.loads(_bytes(raw_body))
        except ValueError as error:
            raise ValueError("Not a Yoon event") from error
        if not isinstance(payload, dict):
            raise ValueError("Not a Yoon event")
        data = payload.get("data")
        obj = data.get("object") if isinstance(data, dict) else None
        if not isinstance(payload.get("id"), str) or not isinstance(payload.get("type"), str) or not isinstance(obj, dict):
            raise ValueError("Not a Yoon event")
        return cls(id=payload["id"], type=payload["type"], object=obj, payload=payload)


class EventStore(Protocol):
    """Where already-handled event ids are remembered."""

    def has(self, event_id: str) -> bool: ...

    def add(self, event_id: str) -> None: ...


class MemoryEventStore:
    """In-process store with a TTL. With more than one process, use :class:`CacheEventStore`
    on a shared cache: this one only knows its own process."""

    def __init__(self, ttl_seconds: int = DEDUPE_SECONDS, max_entries: int = 10_000) -> None:
        self._ttl = ttl_seconds
        self._max = max_entries
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    def has(self, event_id: str) -> bool:
        with self._lock:
            seen = self._seen.get(event_id)
            if seen is None:
                return False
            if time.monotonic() - seen > self._ttl:
                del self._seen[event_id]
                return False
            return True

    def add(self, event_id: str) -> None:
        with self._lock:
            self._seen[event_id] = time.monotonic()
            if len(self._seen) > self._max:
                del self._seen[min(self._seen, key=self._seen.__getitem__)]


class CacheEventStore:
    """Adapts any cache with ``get(key)`` and ``set(key, value, timeout)`` — Django's cache,
    Flask-Caching, cachetools-like wrappers."""

    def __init__(self, cache: Any, ttl_seconds: int = DEDUPE_SECONDS, prefix: str = "yoon_event_") -> None:
        self._cache = cache
        self._ttl = ttl_seconds
        self._prefix = prefix

    def _key(self, event_id: str) -> str:
        return self._prefix + hashlib.sha256(event_id.encode("utf-8")).hexdigest()

    def has(self, event_id: str) -> bool:
        return self._cache.get(self._key(event_id)) is not None

    def add(self, event_id: str) -> None:
        self._cache.set(self._key(event_id), True, self._ttl)


@dataclass(frozen=True)
class Verdict:
    """What to do with a delivery before the application sees it.

    ``event`` is set when the application must handle it; otherwise answer ``status`` with
    ``body`` without calling the application.
    """

    status: int
    body: dict[str, Any]
    event: Optional[Event] = None


def check_delivery(
    raw_body: RawBody,
    header: Optional[str],
    secret: Optional[str],
    store: EventStore,
    now: Optional[Callable[[], int]] = None,
) -> Verdict:
    """The one place the webhook contract lives, so every framework adapter behaves the same:
    a bad or stale signature is refused (401), an unreadable body is refused (400), an
    already-handled event is answered 200 without reaching the handler. The adapter calls
    ``store.add(event.id)`` only after the handler answered 2xx."""
    if not verify_signature(secret or "", header, raw_body, None if now is None else now()):
        return Verdict(401, {"error": "invalid signature"})
    try:
        event = Event.from_json(raw_body)
    except ValueError:
        return Verdict(400, {"error": "not a Yoon event"})
    if store.has(event.id):
        return Verdict(200, {"duplicate": True})
    return Verdict(200, {"received": True}, event)
