import json
from pathlib import Path

import pytest

VECTOR = Path(__file__).resolve().parents[3] / "api" / "test-vectors" / "webhook-signature.json"
SECRET = "python-test-webhook-secret-0123456789"


@pytest.fixture(scope="session")
def vector() -> dict:
    return json.loads(VECTOR.read_text(encoding="utf-8"))


def event_body(event_id: str, payment_id: str = "pay_1", event_type: str = "payment.succeeded") -> bytes:
    return json.dumps(
        {
            "id": event_id,
            "object": "event",
            "type": event_type,
            "created_at": "2026-09-30T10:00:00Z",
            "data": {"object": {"id": payment_id, "status": "succeeded"}},
        }
    ).encode()
