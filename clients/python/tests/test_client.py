import json
import socket
import time

import pytest
from werkzeug import Response

from yoonpay import USER_AGENT, Yoon, YoonException
from yoonpay.generated import CreatePaymentRequest, PaymentStatus

PAYMENT = {
    "id": "pay_1", "object": "payment", "status": "pending", "amount": 5000, "amount_refunded": 0,
    "currency": "XOF", "country": "SN", "method": "wave", "reference": "order_1042", "description": None,
    "customer": {"phone": "+22177***67"}, "provider": "paydunya", "provider_reference": "tok_1",
    "checkout_url": "https://app.paydunya.com/checkout/tok_1", "instructions": None, "routing_reason": "r",
    "failure": None, "created_at": "2026-09-30T10:00:00Z", "updated_at": "2026-09-30T10:00:00Z",
}
REFUND = {
    "id": "ref_1", "object": "refund", "payment_id": "pay_1", "status": "pending", "amount": 1000,
    "currency": "XOF", "reason": None, "provider": "paydunya", "provider_reference": None, "failure": None,
    "created_at": "2026-09-30T10:00:00Z", "updated_at": "2026-09-30T10:00:00Z",
}
PAYOUT = {
    "id": "po_1", "object": "payout", "status": "processing", "amount": 2000, "currency": "XOF",
    "country": "SN", "method": "wave", "reference": "payout_1", "recipient": {"phone": "+22177***67"},
    "provider": "paydunya", "provider_reference": None, "routing_reason": None, "needs_review": False,
    "failure": None, "created_at": "2026-09-30T10:00:00Z", "updated_at": "2026-09-30T10:00:00Z",
}
PAYMENT_REQUEST = {
    "amount": 5000, "currency": "XOF", "country": "SN", "method": "wave",
    "customer": {"phone": "+221771234567"}, "reference": "order_1042",
    "return_url": "https://shop.example/orders/1042",
}


def problem(status: int, code: str, detail: str = "detail") -> Response:
    return Response(
        json.dumps({"type": "about:blank", "title": "t", "status": status, "code": code, "detail": detail}),
        status=status,
        content_type="application/problem+json",
    )


@pytest.fixture
def yoon(httpserver) -> Yoon:
    return Yoon(httpserver.url_for("/"), "yk_secret_key", timeout=2, connect_timeout=1)


def test_create_payment_sends_the_key_headers_and_snake_case_json(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments", method="POST").respond_with_json(PAYMENT, status=201)

    payment = yoon.create_payment(PAYMENT_REQUEST, "order-1042")

    assert payment.status == "pending"
    assert payment.amount == 5000
    assert payment.checkout_url == "https://app.paydunya.com/checkout/tok_1"
    assert payment.description is None and payment.failure is None
    request, _ = httpserver.log[0]
    assert request.headers["Authorization"] == "Bearer yk_secret_key"
    assert request.headers["Idempotency-Key"] == "order-1042"
    assert request.headers["User-Agent"] == USER_AGENT
    assert USER_AGENT.startswith("yoon-python/")
    body = json.loads(request.get_data())
    assert body["return_url"] == "https://shop.example/orders/1042"
    assert body["customer"] == {"phone": "+221771234567"}


def test_a_model_request_works_too(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments", method="POST").respond_with_json(PAYMENT, status=201)

    assert yoon.create_payment(CreatePaymentRequest.from_dict(PAYMENT_REQUEST), "k").id == "pay_1"


def test_the_idempotency_key_is_required(yoon):
    with pytest.raises(TypeError):
        yoon.create_payment(PAYMENT_REQUEST)  # type: ignore[call-arg]
    with pytest.raises(ValueError):
        yoon.create_payment(PAYMENT_REQUEST, "")
    with pytest.raises(TypeError):
        yoon.refund("pay_1")  # type: ignore[call-arg]


def test_a_trailing_slash_in_the_base_url_is_tolerated(httpserver):
    httpserver.expect_oneshot_request("/v1/payments/pay_1", method="GET").respond_with_json(PAYMENT)

    assert Yoon(httpserver.url_for("/") + "/", "yk_k").get_payment("pay_1").id == "pay_1"


def test_refund_omits_what_is_not_given(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1/refunds", method="POST").respond_with_json(REFUND, status=201)

    refund = yoon.refund("pay_1", "refund-1")

    assert refund.status == "pending"
    request, _ = httpserver.log[0]
    assert json.loads(request.get_data() or b"{}") == {}
    assert request.headers["Idempotency-Key"] == "refund-1"


def test_refund_sends_amount_and_reason(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1/refunds", method="POST").respond_with_json(REFUND, status=201)

    yoon.refund("pay_1", "refund-2", amount=1000, reason="damaged")

    assert json.loads(httpserver.log[0][0].get_data()) == {"amount": 1000, "reason": "damaged"}


def test_create_payout(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payouts", method="POST").respond_with_json(PAYOUT, status=201)

    payout = yoon.create_payout(
        {"amount": 2000, "currency": "XOF", "country": "SN", "method": "wave",
         "recipient": {"phone": "+221771234567"}, "reference": "payout_1"},
        "payout-1",
    )

    assert payout.status == "processing"
    assert httpserver.log[0][0].headers["Idempotency-Key"] == "payout-1"


def test_a_problem_becomes_a_yoon_exception_with_its_code(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1/refunds").respond_with_response(
        problem(409, "payment_not_refundable", "Only a succeeded payment can be refunded")
    )

    with pytest.raises(YoonException) as caught:
        yoon.refund("pay_1", "r")

    assert caught.value.http_status == 409
    assert caught.value.problem_code == "payment_not_refundable"
    assert caught.value.problem["detail"] == "Only a succeeded payment can be refunded"
    assert str(caught.value) == "Only a succeeded payment can be refunded"
    assert not caught.value.is_retryable()


@pytest.mark.parametrize(
    "response, retryable",
    [
        (problem(409, "idempotency_in_progress"), True),
        (problem(503, "provider_unavailable"), True),
        (Response("<html>bad gateway</html>", status=502, content_type="text/html"), True),
        (problem(422, "validation_failed"), False),
    ],
)
def test_retryability(httpserver, yoon, response, retryable):
    httpserver.expect_oneshot_request("/v1/payments/pay_1").respond_with_response(response)

    with pytest.raises(YoonException) as caught:
        yoon.get_payment("pay_1")

    assert caught.value.is_retryable() is retryable


def test_an_html_error_page_has_no_problem_code(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1").respond_with_response(
        Response("<html>bad gateway</html>", status=502, content_type="text/html")
    )

    with pytest.raises(YoonException) as caught:
        yoon.get_payment("pay_1")

    assert caught.value.http_status == 502
    assert caught.value.problem_code is None
    assert caught.value.problem is None


def test_the_client_never_retries(httpserver, yoon):
    httpserver.expect_request("/v1/payments", method="POST").respond_with_response(problem(503, "provider_unavailable"))

    with pytest.raises(YoonException):
        yoon.create_payment(PAYMENT_REQUEST, "k")

    assert len(httpserver.log) == 1


def test_yoon_unreachable_is_retryable_without_code():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    yoon = Yoon(f"http://127.0.0.1:{port}", "yk_secret_key", connect_timeout=1)

    with pytest.raises(YoonException) as caught:
        yoon.get_payment("pay_1")

    assert caught.value.http_status is None
    assert caught.value.problem_code is None
    assert caught.value.is_retryable()
    assert "yk_secret_key" not in str(caught.value)


def test_a_slow_answer_times_out_as_a_transport_error(httpserver):
    def slow(_request):
        time.sleep(1.5)
        return Response(json.dumps(PAYMENT), content_type="application/json")

    httpserver.expect_oneshot_request("/v1/payments/pay_1").respond_with_handler(slow)
    yoon = Yoon(httpserver.url_for("/"), "yk_k", timeout=0.3)

    with pytest.raises(YoonException) as caught:
        yoon.get_payment("pay_1")

    assert caught.value.problem_code is None and caught.value.is_retryable()


@pytest.mark.parametrize(
    "response",
    [
        Response("{not json", status=201, content_type="application/json"),
        Response("<html>proxy</html>", status=200, content_type="text/html"),
        Response('{"id": "pay_1"}', status=201, content_type="application/json"),
    ],
)
def test_an_unreadable_2xx_is_not_retryable(httpserver, yoon, response):
    httpserver.expect_oneshot_request("/v1/payments", method="POST").respond_with_response(response)

    with pytest.raises(YoonException) as caught:
        yoon.create_payment(PAYMENT_REQUEST, "k")

    assert caught.value.problem_code == "unreadable_response"
    assert not caught.value.is_retryable()


def test_an_unknown_status_from_a_newer_server_does_not_break_parsing(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1").respond_with_json({**PAYMENT, "status": "teleported", "new_field": 1})

    payment = yoon.get_payment("pay_1")

    assert payment.status == "teleported"
    assert payment.status != PaymentStatus.PENDING
    assert yoon.get_payment.__name__  # still a normal client


def test_export_csv_returns_the_text(httpserver, yoon):
    csv = "id,created_at,status\npay_1,2026-09-30T10:00:00Z,pending\n"
    httpserver.expect_oneshot_request("/v1/exports/payments.csv", query_string="from=2026-09-01T00%3A00%3A00%2B00%3A00").respond_with_response(
        Response(csv, content_type="text/csv; charset=utf-8")
    )
    from datetime import datetime, timezone

    assert yoon.export_csv("payments", from_=datetime(2026, 9, 1, tzinfo=timezone.utc)) == csv
    assert httpserver.log[0][0].headers["Authorization"] == "Bearer yk_secret_key"


def test_an_unknown_export_is_refused(yoon):
    with pytest.raises(ValueError):
        yoon.export_csv("customers")  # type: ignore[arg-type]


def test_events_with_each_object_kind_parse(httpserver, yoon):
    def event(i, obj, kind):
        return {
            "id": f"evt_{i}", "object": "event", "type": f"{kind}.created_by_a_newer_server", "resource_type": kind,
            "resource_id": obj["id"], "created_at": "2026-09-30T10:00:00Z",
            "delivery": {"status": "delivered", "attempts": 1, "next_attempt_at": None, "last_error": None},
            "payload": {"id": f"evt_{i}", "object": "event", "type": "payment.succeeded",
                        "created_at": "2026-09-30T10:00:00Z", "data": {"object": obj}},
        }

    page = {"data": [event(1, PAYMENT, "payment"), event(2, REFUND, "refund"), event(3, PAYOUT, "payout")],
            "has_more": False, "next_cursor": None}
    httpserver.expect_oneshot_request("/v1/events").respond_with_json(page)

    events = yoon.call(lambda: yoon.events().list_events())

    assert [e.payload.data.object.actual_instance.id for e in events.data] == ["pay_1", "ref_1", "po_1"]
    assert events.data[0].type == "payment.created_by_a_newer_server"


def test_the_api_key_never_shows(httpserver, yoon):
    httpserver.expect_oneshot_request("/v1/payments/pay_1").respond_with_response(problem(404, "not_found"))

    with pytest.raises(YoonException) as caught:
        yoon.get_payment("pay_1")

    assert "yk_secret_key" not in repr(yoon) + str(yoon)
    assert "yk_secret_key" not in str(caught.value) + repr(caught.value) + json.dumps(caught.value.problem)


def test_construction_needs_a_url_and_a_key():
    with pytest.raises(ValueError):
        Yoon("", "yk_k")
    with pytest.raises(ValueError):
        Yoon("https://pay.example.com", "")
