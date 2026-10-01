from __future__ import annotations

import json
from typing import Any, Optional


class YoonException(Exception):
    """An error answered by Yoon (``application/problem+json``), or a transport failure.

    - ``problem_code`` is Yoon's stable code, e.g. ``no_provider_for_method``; ``None`` when
      Yoon could not be reached — then retry with the same idempotency key;
      ``unreadable_response`` when Yoon answered 2xx with something this client could not
      read (not retryable: the call may have succeeded, look it up before trying again).
    - ``http_status`` is ``None`` when Yoon was not reached.
    - ``problem`` is the full problem document when Yoon sent one.

    The API key never appears in the message or the problem document.
    """

    def __init__(
        self,
        message: str,
        *,
        http_status: Optional[int] = None,
        problem_code: Optional[str] = None,
        problem: Optional[dict[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.http_status = http_status
        self.problem_code = problem_code
        self.problem = problem

    def is_retryable(self) -> bool:
        """True when retrying with the same idempotency key is the right move."""
        return (
            self.problem_code is None
            or self.problem_code == "idempotency_in_progress"
            or (self.http_status is not None and self.http_status >= 500)
        )

    @classmethod
    def transport(cls, cause: BaseException) -> "YoonException":
        return cls(f"Yoon unreachable: {cause}")

    @classmethod
    def unreadable(cls, http_status: int, reason: str) -> "YoonException":
        return cls(
            f"Unreadable answer from Yoon: {reason}",
            http_status=http_status,
            problem_code="unreadable_response",
        )

    @classmethod
    def from_response(cls, http_status: int, body: Optional[str]) -> "YoonException":
        problem: Optional[dict[str, Any]] = None
        try:
            parsed = json.loads(body) if body else None
            problem = parsed if isinstance(parsed, dict) else None
        except ValueError:
            pass  # not a problem document (e.g. a proxy's HTML error page)
        code = problem.get("code") if problem else None
        detail = problem.get("detail") if problem else None
        return cls(
            detail if isinstance(detail, str) else f"Yoon answered HTTP {http_status}",
            http_status=http_status,
            problem_code=code if isinstance(code, str) else None,
            problem=problem,
        )
