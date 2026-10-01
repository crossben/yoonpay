import json

import pytest

pytest.importorskip("flask")

from flask import Flask, jsonify  # noqa: E402

from conftest import SECRET, event_body  # noqa: E402
from yoonpay import sign_signature  # noqa: E402
from yoonpay.flask import yoon_webhook  # noqa: E402

reached: list[str] = []


@pytest.fixture
def client():
    reached.clear()
    app = Flask(__name__)
    app.config["YOON_WEBHOOK_SECRET"] = SECRET

    @app.post("/yoon/webhook")
    @yoon_webhook
    def yoon_events(event):
        reached.append(event.id)
        if event.object["id"] == "pay_fail":
            return jsonify(error="handler failed"), 500
        if event.object["id"] == "pay_throw":
            raise RuntimeError("handler crashed")
        return "", 204

    return app.test_client()


def deliver(client, body: bytes, signature: str | None = None):
    return client.post(
        "/yoon/webhook", data=body,
        headers={"Content-Type": "application/json", "Yoon-Signature": signature or sign_signature(SECRET, body)},
    )


def test_a_verified_event_reaches_the_view(client):
    assert deliver(client, event_body("evt_1")).status_code == 204
    assert reached == ["evt_1"]


def test_a_bad_signature_is_refused(client):
    assert deliver(client, event_body("evt_2"), "t=1,v1=" + "0" * 64).status_code == 401
    assert reached == []


def test_a_re_encoded_body_is_refused(client):
    body = event_body("evt_3")
    signature = sign_signature(SECRET, body)
    assert deliver(client, json.dumps(json.loads(body), indent=2).encode(), signature).status_code == 401


def test_a_duplicate_is_acknowledged_without_reaching_the_view(client):
    assert deliver(client, event_body("evt_4")).status_code == 204
    second = deliver(client, event_body("evt_4"))
    assert second.status_code == 200 and second.get_json() == {"duplicate": True}
    assert reached == ["evt_4"]


@pytest.mark.parametrize("payment_id", ["pay_fail", "pay_throw"])
def test_a_failing_view_is_not_remembered_so_the_retry_reaches_it(client, payment_id):
    assert deliver(client, event_body("evt_5", payment_id)).status_code == 500
    assert deliver(client, event_body("evt_5", payment_id)).status_code == 500
    assert reached == ["evt_5", "evt_5"]
