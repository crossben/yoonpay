"""Python client for Yoon, the self-hosted payment gateway for African payment providers.

The generated code lives in ``yoonpay.generated`` (``clients/python/generated`` in the
repository, merged into this package when it is built); the hand-written layer is here.
"""

# The generated sub-package sits in a separate source tree in the repository: let both trees
# contribute to the `yoonpay` package during development (a built wheel has one tree).
from pkgutil import extend_path

__path__ = extend_path(__path__, __name__)

from yoonpay._version import USER_AGENT, __version__  # noqa: E402
from yoonpay.client import Yoon  # noqa: E402
from yoonpay.errors import YoonException  # noqa: E402
from yoonpay.webhook import (  # noqa: E402
    SIGNATURE_HEADER,
    CacheEventStore,
    Event,
    EventStore,
    MemoryEventStore,
    sign_signature,
    verify_signature,
)

__all__ = [
    "SIGNATURE_HEADER",
    "USER_AGENT",
    "CacheEventStore",
    "Event",
    "EventStore",
    "MemoryEventStore",
    "Yoon",
    "YoonException",
    "__version__",
    "sign_signature",
    "verify_signature",
]
