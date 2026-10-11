from datetime import datetime, timezone

import pytest

from yoonpay.billing import (
    ENTITLED_STATUSES,
    ENTITLEMENT_EVENTS,
    INFORMATIONAL_EVENTS,
    Entitlement,
    changes_entitlement,
    checkout_url,
)
from yoonpay.generated import Subscription

INVOICE = {
    "id": "in_1", "object": "invoice", "number": "INV-2026-000001", "customer": "cus_1",
    "subscription": "sub_1", "kind": "first", "status": "open", "amount_due": 5000, "currency": "XOF",
    "lines": [], "period_start": None, "period_end": None, "payment": "pay_1",
    "checkout_url": "https://pay.example.com/checkout/pay_1?t=tok", "receipt_url": None,
    "paid_at": None, "voided_at": None, "created_at": "2026-09-30T10:00:00Z",
}


def subscription(status: str = "active", **overrides) -> dict:
    sub = {
        "id": "sub_1", "object": "subscription",
        "customer": {"id": "cus_1", "external_id": "user_42"},
        "price": {"id": "price_1", "currency": "XOF", "amount": 5000, "interval": "month", "interval_count": 1,
                  "plan": {"id": "plan_1", "slug": "pro", "name": "Pro"}},
        "status": status, "collection_method": "prepaid_manual",
        "current_period_start": "2026-09-30T10:00:00Z", "current_period_end": "2026-10-30T10:00:00Z",
        "grace_period_days": 3, "cancel_at_period_end": False, "pending_change": None, "canceled_at": None,
        "comp": False, "entitled": status in ("active", "past_due"), "features": {"seats": 5},
        "latest_invoice": INVOICE, "metadata": {},
        "created_at": "2026-09-30T10:00:00Z", "updated_at": "2026-09-30T10:00:00Z",
    }
    sub.update(overrides)
    return sub


@pytest.mark.parametrize("status, entitled", [
    ("active", True), ("past_due", True), ("incomplete", False), ("expired", False), ("canceled", False),
    ("paused_by_a_newer_server", False), ("", False),
])
def test_entitlement_follows_the_status(status, entitled):
    e = Entitlement.from_subscription(subscription(status))
    assert e.entitled is entitled
    assert e.status == status


def test_entitlement_carries_plan_period_features_and_ids():
    e = Entitlement.from_subscription(subscription())
    assert e.plan == "pro"
    assert e.until == datetime(2026, 10, 30, 10, tzinfo=timezone.utc)
    assert e.features == {"seats": 5}
    assert (e.subscription_id, e.customer_id, e.external_id, e.comp) == ("sub_1", "cus_1", "user_42", False)


def test_an_open_ended_comp_is_entitled_without_end():
    e = Entitlement.from_subscription(subscription(comp=True, current_period_end=None))
    assert e.entitled and e.until is None and e.comp


def test_a_generated_model_works_like_a_dict():
    e = Entitlement.from_subscription(Subscription.from_dict(subscription("past_due")))
    assert e.entitled and e.status == "past_due" and e.plan == "pro"
    assert e.until == datetime(2026, 10, 30, 10, tzinfo=timezone.utc)


@pytest.mark.parametrize("junk", [{}, None, "sub_1", {"status": 3, "price": "x", "features": [], "current_period_end": "soon"}])
def test_malformed_input_never_raises(junk):
    e = Entitlement.from_subscription(junk)
    assert not e.entitled and e.plan is None and e.until is None and e.features == {}


def test_event_type_sets():
    assert "subscription.past_due" in ENTITLEMENT_EVENTS and "past_due" in ENTITLED_STATUSES
    assert not ENTITLEMENT_EVENTS & INFORMATIONAL_EVENTS
    for t in ENTITLEMENT_EVENTS:
        assert changes_entitlement(t)
    for t in INFORMATIONAL_EVENTS | {"subscription.renewal_upcoming", "invoice.paid", "payment.succeeded",
                                     "subscription.something_new", ""}:
        assert not changes_entitlement(t)


def test_checkout_url_of_an_invoice_or_a_subscription():
    assert checkout_url(INVOICE) == INVOICE["checkout_url"]
    assert checkout_url(subscription()) == INVOICE["checkout_url"]
    assert checkout_url(Subscription.from_dict(subscription())) == INVOICE["checkout_url"]


@pytest.mark.parametrize("sub", [
    subscription(latest_invoice=None),
    subscription(latest_invoice={**INVOICE, "status": "paid", "checkout_url": None}),
])
def test_no_checkout_url(sub):
    assert checkout_url(sub) is None
    assert checkout_url({**INVOICE, "checkout_url": None}) is None
    assert checkout_url(None) is None
