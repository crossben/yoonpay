import json

import pytest

pytest.importorskip("fastapi")

from fastapi import APIRouter, Depends, FastAPI, HTTPException  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from conftest import SECRET, event_body  # noqa: E402
from yoonpay import Event, MemoryEventStore, sign_signature  # noqa: E402
from yoonpay.fastapi import yoon_event, yoon_webhook_route  # noqa: E402

reached: list[str] = []


def make_app() -> FastAPI:
    webhooks = APIRouter(route_class=yoon_webhook_route(secret=SECRET, store=MemoryEventStore()))

    @webhooks.post("/yoon/webhook", status_code=204)
    def yoon_events(event: Event = Depends(yoon_event)):
        reached.append(event.id)
        if event.object["id"] == "pay_fail":
            return JSONResponse({"error": "handler failed"}, status_code=500)
        if event.object["id"] == "pay_http":
            raise HTTPException(status_code=503)
        if event.object["id"] == "pay_throw":
            raise RuntimeError("handler crashed")
        return None

    app = FastAPI()
    app.include_router(webhooks)
    return app


@pytest.fixture
def client():
    reached.clear()
    return TestClient(make_app(), raise_server_exceptions=False)


def deliver(client, body: bytes, signature: str | None = None):
    return client.post(
        "/yoon/webhook", content=body,
        headers={"Content-Type": "application/json", "Yoon-Signature": signature or sign_signature(SECRET, body)},
    )


def test_a_verified_event_reaches_the_endpoint(client):
    assert deliver(client, event_body("evt_1")).status_code == 204
    assert reached == ["evt_1"]


def test_a_bad_signature_is_refused(client):
    assert deliver(client, event_body("evt_2"), "t=1,v1=" + "0" * 64).status_code == 401
    assert reached == []


def test_a_re_encoded_body_is_refused(client):
    body = event_body("evt_3")
    signature = sign_signature(SECRET, body)
    assert deliver(client, json.dumps(json.loads(body), indent=2).encode(), signature).status_code == 401


def test_a_duplicate_is_acknowledged_without_reaching_the_endpoint(client):
    assert deliver(client, event_body("evt_4")).status_code == 204
    second = deliver(client, event_body("evt_4"))
    assert second.status_code == 200 and second.json() == {"duplicate": True}
    assert reached == ["evt_4"]


@pytest.mark.parametrize("payment_id, status", [("pay_fail", 500), ("pay_http", 503), ("pay_throw", 500)])
def test_a_failing_endpoint_is_not_remembered_so_the_retry_reaches_it(client, payment_id, status):
    assert deliver(client, event_body("evt_5", payment_id)).status_code == status
    assert deliver(client, event_body("evt_5", payment_id)).status_code == status
    assert reached == ["evt_5", "evt_5"]


def test_a_secret_is_required():
    with pytest.raises(ValueError):
        yoon_webhook_route(secret="")
