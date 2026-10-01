import json

import pytest

django = pytest.importorskip("django")

from django.conf import settings  # noqa: E402

from conftest import SECRET, event_body  # noqa: E402

if not settings.configured:
    settings.configure(
        DEBUG=False,
        SECRET_KEY="test",
        ROOT_URLCONF=__name__,
        ALLOWED_HOSTS=["testserver"],
        MIDDLEWARE=["django.middleware.csrf.CsrfViewMiddleware"],
        CACHES={"default": {"BACKEND": "django.core.cache.backends.locmem.LocMemCache"}},
        YOON_WEBHOOK_SECRET=SECRET,
    )
    django.setup()

from django.core.cache import cache  # noqa: E402
from django.http import HttpResponse, JsonResponse  # noqa: E402
from django.test import Client  # noqa: E402
from django.urls import path  # noqa: E402

from yoonpay import sign_signature  # noqa: E402
from yoonpay.django import yoon_webhook  # noqa: E402

reached: list[str] = []


@yoon_webhook
def yoon_events(request, event):
    reached.append(event.id)
    if event.object["id"] == "pay_fail":
        return JsonResponse({"error": "handler failed"}, status=500)
    if event.object["id"] == "pay_throw":
        raise RuntimeError("handler crashed")
    return HttpResponse(status=204)


urlpatterns = [path("yoon/webhook", yoon_events)]


@pytest.fixture(autouse=True)
def reset():
    reached.clear()
    cache.clear()


def deliver(body: bytes, signature: str | None = None):
    client = Client(enforce_csrf_checks=True, raise_request_exception=False)
    return client.post(
        "/yoon/webhook", data=body, content_type="application/json",
        headers={"Yoon-Signature": signature or sign_signature(SECRET, body)},
    )


def test_a_verified_event_reaches_the_view():
    assert deliver(event_body("evt_1")).status_code == 204
    assert reached == ["evt_1"]


def test_a_bad_signature_is_refused():
    response = deliver(event_body("evt_2"), "t=1,v1=" + "0" * 64)
    assert response.status_code == 401
    assert reached == []


def test_a_re_encoded_body_is_refused():
    body = event_body("evt_3")
    signature = sign_signature(SECRET, body)
    assert deliver(json.dumps(json.loads(body), indent=2).encode(), signature).status_code == 401


def test_a_duplicate_is_acknowledged_without_reaching_the_view():
    assert deliver(event_body("evt_4")).status_code == 204
    second = deliver(event_body("evt_4"))
    assert second.status_code == 200 and second.json() == {"duplicate": True}
    assert reached == ["evt_4"]


def test_a_failing_view_is_not_remembered_so_the_retry_reaches_it():
    assert deliver(event_body("evt_5", "pay_fail")).status_code == 500
    assert deliver(event_body("evt_5", "pay_fail")).status_code == 500
    assert reached == ["evt_5", "evt_5"]


def test_a_crashing_view_is_not_remembered_either():
    assert deliver(event_body("evt_6", "pay_throw")).status_code == 500
    assert deliver(event_body("evt_6", "pay_throw")).status_code == 500
    assert reached == ["evt_6", "evt_6"]


def test_only_post_is_accepted():
    assert Client().get("/yoon/webhook").status_code == 405
