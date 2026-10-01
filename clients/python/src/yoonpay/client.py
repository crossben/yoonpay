from __future__ import annotations

import json
import re
from datetime import datetime
from typing import Any, Callable, Literal, Mapping, Optional, TypeVar, Union

import urllib3

from yoonpay._version import USER_AGENT
from yoonpay.errors import YoonException
from yoonpay.generated import (
    ApiClient,
    ApiException,
    Configuration,
    CreatePaymentRequest,
    CreatePayoutRequest,
    CreateRefundRequest,
    EventsApi,
    LedgerApi,
    MetaApi,
    Payment,
    PaymentsApi,
    PayoutsApi,
    Payout,
    Refund,
    RefundsApi,
)

from yoonpay.generated.api.exports_api import ExportsApi

T = TypeVar("T")
Export = Literal["payments", "refunds", "payouts", "ledger"]

_JSON = re.compile(r"^application/([\w.+-]+\+)?json\s*(;|$)", re.IGNORECASE)
_CSV = re.compile(r"^text/csv\s*(;|$)", re.IGNORECASE)


class _ApiClient(ApiClient):
    """The generated ApiClient with Yoon's defaults: timeouts on every call, and a 2xx that
    cannot be read reported as ``unreadable_response`` instead of a parsing error."""

    def __init__(self, configuration: Configuration, timeout: tuple[float, float]) -> None:
        super().__init__(configuration)
        self.user_agent = USER_AGENT
        self._default_timeout = timeout

    def call_api(self, method, url, header_params=None, body=None, post_params=None, _request_timeout=None):  # type: ignore[no-untyped-def]
        return super().call_api(
            method,
            url,
            header_params=header_params,
            body=body,
            post_params=post_params,
            _request_timeout=_request_timeout or self._default_timeout,
        )

    def response_deserialize(self, response_data, response_types_map=None):  # type: ignore[no-untyped-def]
        status = response_data.status
        if not 200 <= status <= 299:
            return super().response_deserialize(response_data, response_types_map)
        content_type = response_data.getheader("content-type") or ""
        expected = (response_types_map or {}).get(str(status))
        if expected == "str":
            if not _CSV.match(content_type):
                raise YoonException.unreadable(status, f"expected CSV, content-type was {content_type or 'none'}")
        elif (expected is not None or response_data.data) and not _JSON.match(content_type):
            raise YoonException.unreadable(status, f"content-type was {content_type or 'none'}")
        try:
            return super().response_deserialize(response_data, response_types_map)
        except (ValueError, ApiException) as error:  # JSON or model validation failed
            raise YoonException.unreadable(status, type(error).__name__) from error


class Yoon:
    """Entry point.

    Common calls have helpers; every operation of the API contract is available on the
    generated API objects (:meth:`payments`, :meth:`events`, …), wrapped with :meth:`call` to
    get :class:`YoonException`. The client never retries: the caller retries with the same
    idempotency key.

    Every create call takes an idempotency key: use something tied to your intent (your
    order id) so that a retry after a timeout returns the original result instead of
    charging twice.
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        timeout: float = 30.0,
        connect_timeout: float = 5.0,
    ) -> None:
        """
        :param base_url: where Yoon runs, e.g. ``https://pay.example.com``
        :param api_key: the application key (``yk_…``)
        :param timeout: seconds to wait for each read of the answer (default 30)
        :param connect_timeout: seconds to establish the connection (default 5)
        """
        if not isinstance(base_url, str) or not base_url:
            raise ValueError("base_url must be a non-empty string, e.g. https://pay.example.com")
        if not isinstance(api_key, str) or not api_key:
            raise ValueError("api_key must be a non-empty string (yk_…)")
        self.base_url = base_url.rstrip("/")
        # retries=False: urllib3 would otherwise retry on its own. A retried payment must
        # be the caller's decision, with the same idempotency key.
        configuration = Configuration(host=self.base_url, access_token=api_key, retries=False)
        self._client = _ApiClient(configuration, (float(connect_timeout), float(timeout)))

    def __repr__(self) -> str:  # never shows the API key
        return f"Yoon({self.base_url!r})"

    # ------------------------------------------------------------------ helpers

    def create_payment(
        self, payment: Union[CreatePaymentRequest, Mapping[str, Any]], idempotency_key: str
    ) -> Payment:
        request = payment if isinstance(payment, CreatePaymentRequest) else CreatePaymentRequest.from_dict(dict(payment))
        return self.call(lambda: self.payments().create_payment(_key(idempotency_key), request))

    def get_payment(self, id: str) -> Payment:
        return self.call(lambda: self.payments().get_payment(id))

    def refund(
        self,
        payment_id: str,
        idempotency_key: str,
        amount: Optional[int] = None,
        reason: Optional[str] = None,
    ) -> Refund:
        """``amount=None`` refunds whatever is left."""
        request = CreateRefundRequest(amount=amount, reason=reason)
        return self.call(lambda: self.refunds().create_refund(payment_id, _key(idempotency_key), request))

    def create_payout(
        self, payout: Union[CreatePayoutRequest, Mapping[str, Any]], idempotency_key: str
    ) -> Payout:
        request = payout if isinstance(payout, CreatePayoutRequest) else CreatePayoutRequest.from_dict(dict(payout))
        return self.call(lambda: self.payouts().create_payout(_key(idempotency_key), request))

    def export_csv(
        self, kind: Export, from_: Optional[datetime] = None, to: Optional[datetime] = None
    ) -> str:
        """A CSV export: amounts in minor units, UTC timestamps, phones masked.

        :param kind: ``payments``, ``refunds``, ``payouts`` or ``ledger``
        :param from_: inclusive, or ``None``
        :param to: exclusive, or ``None``
        """
        exports = ExportsApi(self._client)
        calls: dict[str, Callable[..., str]] = {
            "payments": exports.export_payments,
            "refunds": exports.export_refunds,
            "payouts": exports.export_payouts,
            "ledger": exports.export_ledger,
        }
        if kind not in calls:
            raise ValueError(f"Unknown export {kind!r}: payments, refunds, payouts or ledger")
        return self.call(lambda: calls[kind](var_from=from_, to=to))

    def call(self, fn: Callable[[], T]) -> T:
        """Runs any generated API call, turning errors into :class:`YoonException`."""
        try:
            return fn()
        except YoonException:
            raise
        except ApiException as error:
            if not error.status:  # raised before an HTTP answer (e.g. a TLS failure)
                raise YoonException.transport(error) from error
            body = error.body if isinstance(error.body, str) else _decode(error.body)
            raise YoonException.from_response(error.status, body) from error
        except urllib3.exceptions.HTTPError as error:  # connection refused, timeout, …
            raise YoonException.transport(error) from error

    # ------------------------------------------------------------------ generated APIs

    def payments(self) -> PaymentsApi:
        return PaymentsApi(self._client)

    def refunds(self) -> RefundsApi:
        return RefundsApi(self._client)

    def payouts(self) -> PayoutsApi:
        return PayoutsApi(self._client)

    def events(self) -> EventsApi:
        return EventsApi(self._client)

    def ledger(self) -> LedgerApi:
        return LedgerApi(self._client)

    def meta(self) -> MetaApi:
        return MetaApi(self._client)


def _key(idempotency_key: str) -> str:
    if not isinstance(idempotency_key, str) or not idempotency_key:
        raise ValueError("idempotency_key is required: tie it to your intent, e.g. your order id")
    return idempotency_key


def _decode(body: Any) -> Optional[str]:
    if isinstance(body, (bytes, bytearray)):
        return body.decode("utf-8", "replace")
    return None if body is None else json.dumps(body)


__all__ = ["Export", "Yoon"]
