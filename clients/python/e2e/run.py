"""The shared e2e scenario (clients/e2e/scenario.md) against a real Yoon server.

Usage: YOON_URL=http://localhost:8080 YOON_API_KEY=yk_… python clients/python/e2e/run.py
Install the package first (pip install clients/python).
"""

import os
import re
import sys
import time

from yoonpay import Yoon, YoonException

url = os.environ.get("YOON_URL")
api_key = os.environ.get("YOON_API_KEY")
if not url or not api_key:
    sys.exit("Set YOON_URL and YOON_API_KEY (see clients/e2e/scenario.md).")

step = 0


def ok(name: str) -> None:
    global step
    step += 1
    print(f"  {step}. {name}")


def fail(name: str, error: BaseException) -> None:
    print(f"  x step {step + 1}: {name}: {error!r}", file=sys.stderr)
    sys.exit(1)


def expect_problem(call, code: str) -> None:
    try:
        call()
    except YoonException as error:
        if error.problem_code != code:
            raise AssertionError(f"expected problem_code {code}, got {error.problem_code}") from error
        return
    raise AssertionError(f"expected problem_code {code}, but the call succeeded")


yoon = Yoon(url, api_key)
reference = f"e2e_py_{int(time.time() * 1000)}"
request = {
    "amount": 5000,
    "currency": "XOF",
    "country": "SN",
    "method": "wave",
    "customer": {"phone": "+221771234567"},
    "reference": reference,
}

# 1. create_payment → pending, phone masked
try:
    payment = yoon.create_payment(request, reference)
    if payment.status != "pending":
        raise AssertionError(f"status {payment.status}, expected pending")
    phone = payment.customer.phone if payment.customer else None
    if not re.fullmatch(r"\+\d+\*\*\*\d+", phone or ""):
        raise AssertionError(f"phone not masked: {phone}")
    ok(f"create_payment → {payment.status.value}, phone masked ({phone})")
except Exception as error:  # noqa: BLE001
    fail("create_payment", error)

# 2. same idempotency key → the same payment id
try:
    replay = yoon.create_payment(request, reference)
    if replay.id != payment.id:
        raise AssertionError(f"replay returned {replay.id}, expected {payment.id}")
    ok("same idempotency key → same payment id")
except Exception as error:  # noqa: BLE001
    fail("idempotent replay", error)

# 3. get_payment, then list by reference → found
try:
    if yoon.get_payment(payment.id).id != payment.id:
        raise AssertionError("get_payment returned another payment")
    listed = yoon.call(lambda: yoon.payments().list_payments(reference=reference))
    if not any(p.id == payment.id for p in listed.data):
        raise AssertionError("payment not found by reference")
    ok("get_payment + list by reference → found")
except Exception as error:  # noqa: BLE001
    fail("get/list", error)

# 4. refund of a pending payment → payment_not_refundable
try:
    expect_problem(lambda: yoon.refund(payment.id, f"{reference}_refund"), "payment_not_refundable")
    ok("refund of pending payment → payment_not_refundable")
except Exception as error:  # noqa: BLE001
    fail("refund", error)

# 5. bad API key → unauthorized
try:
    expect_problem(lambda: Yoon(url, "yk_not_a_real_key").get_payment(payment.id), "unauthorized")
    ok("bad API key → unauthorized")
except Exception as error:  # noqa: BLE001
    fail("bad key", error)

# 6. export_csv('payments') → starts with id,created_at
try:
    csv = yoon.export_csv("payments")
    if not csv.startswith("id,created_at"):
        raise AssertionError(f"unexpected CSV header: {csv.splitlines()[0] if csv else '(empty)'}")
    ok("export_csv → id,created_at…")
except Exception as error:  # noqa: BLE001
    fail("export_csv", error)

# 7. create_payout → processing
try:
    payout = yoon.create_payout(
        {
            "amount": 2000,
            "currency": "XOF",
            "country": "SN",
            "method": "wave",
            "recipient": {"phone": "+221771234567"},
            "reference": f"{reference}_payout",
        },
        f"{reference}_payout",
    )
    if payout.status != "processing":
        raise AssertionError(f"status {payout.status}, expected processing")
    ok("create_payout → processing")
except Exception as error:  # noqa: BLE001
    fail("create_payout", error)

print(f"e2e: all {step} steps passed against {url}")
