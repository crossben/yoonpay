"""Billing helpers for prepaid subscriptions (ADR-0026, ADR-0027). Framework-free.

::

    from yoonpay.billing import ENTITLEMENT_EVENTS, Entitlement

    if event.type in ENTITLEMENT_EVENTS:
        e = Entitlement.from_subscription(event.object)
        save(user=e.external_id, entitled=e.entitled, plan=e.plan, until=e.until, features=e.features)

Statuses and event types are an open set: a value added by a newer Yoon server never raises
here. An unknown status is not entitled; an unknown event type is in neither set.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Mapping, Optional, Union

#: Subscription statuses that give access now. ``past_due`` is the grace period: still entitled.
ENTITLED_STATUSES: frozenset[str] = frozenset({"active", "past_due"})

#: Event types after which the customer's access may have changed: recompute the entitlement
#: from ``event.object`` (a subscription).
ENTITLEMENT_EVENTS: frozenset[str] = frozenset(
    {
        "subscription.activated",
        "subscription.renewed",
        "subscription.changed",
        "subscription.past_due",
        "subscription.expired",
        "subscription.canceled",
    }
)

#: Billing event types that never change access (reminders, invoices, a new incomplete
#: subscription). ``event.object`` is a subscription or an invoice.
INFORMATIONAL_EVENTS: frozenset[str] = frozenset(
    {
        "subscription.created",
        "subscription.renewal_upcoming",
        "invoice.created",
        "invoice.paid",
        "invoice.voided",
    }
)


def changes_entitlement(event_type: str) -> bool:
    """True for the event types after which access must be recomputed; False for anything
    else, including types this version does not know."""
    return event_type in ENTITLEMENT_EVENTS


@dataclass(frozen=True)
class Entitlement:
    """What a subscription gives its customer now.

    ``until`` is the end of the paid (or granted) period; ``None`` with ``entitled`` means an
    open-ended comp. During ``past_due`` it is the end of the unpaid period — grace runs past it
    for ``grace_period_days``, and Yoon sends ``subscription.expired`` when grace ends.
    """

    entitled: bool
    status: str
    plan: Optional[str]
    until: Optional[datetime]
    features: dict[str, Any] = field(default_factory=dict)
    subscription_id: Optional[str] = None
    customer_id: Optional[str] = None
    external_id: Optional[str] = None
    comp: bool = False

    @classmethod
    def from_subscription(cls, subscription: Union[Mapping[str, Any], Any]) -> "Entitlement":
        """From a subscription: an event's ``object`` (a dict) or a generated ``Subscription``.

        Never raises on content: missing fields and unknown statuses give ``entitled=False``.
        """
        sub = _as_dict(subscription)
        status = _text(sub.get("status")) or ""
        price = sub.get("price") if isinstance(sub.get("price"), Mapping) else {}
        plan = price.get("plan") if isinstance(price.get("plan"), Mapping) else {}
        customer = sub.get("customer") if isinstance(sub.get("customer"), Mapping) else {}
        features = sub.get("features")
        return cls(
            entitled=status in ENTITLED_STATUSES,
            status=status,
            plan=_text(plan.get("slug")),
            until=_datetime(sub.get("current_period_end")),
            features=dict(features) if isinstance(features, Mapping) else {},
            subscription_id=_text(sub.get("id")),
            customer_id=_text(customer.get("id")),
            external_id=_text(customer.get("external_id")),
            comp=sub.get("comp") is True,
        )


def checkout_url(obj: Union[Mapping[str, Any], Any]) -> Optional[str]:
    """The ``checkout_url`` of an invoice, or of a subscription's ``latest_invoice``; ``None``
    when there is none (invoice paid, void, or its checkout expired: call ``renew``)."""
    data = _as_dict(obj)
    if data.get("object") == "subscription" or "latest_invoice" in data:
        data = _as_dict(data.get("latest_invoice") or {})
    url = data.get("checkout_url")
    return url if isinstance(url, str) and url else None


def _as_dict(obj: Any) -> Mapping[str, Any]:
    if isinstance(obj, Mapping):
        return obj
    to_dict = getattr(obj, "to_dict", None)  # a generated model
    if callable(to_dict):
        result = to_dict()
        if isinstance(result, Mapping):
            return result
    return {}


def _text(value: Any) -> Optional[str]:
    value = getattr(value, "value", value)  # a generated str Enum
    return value if isinstance(value, str) else None


def _datetime(value: Any) -> Optional[datetime]:
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


__all__ = [
    "ENTITLED_STATUSES",
    "ENTITLEMENT_EVENTS",
    "INFORMATIONAL_EVENTS",
    "Entitlement",
    "changes_entitlement",
    "checkout_url",
]
