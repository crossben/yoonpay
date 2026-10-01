# Changelog

## 0.1.0 (unreleased)

- First release of the Python client: `Yoon` with `create_payment`, `get_payment`, `refund`,
  `create_payout` and `export_csv` (explicit idempotency key on every write, no retries,
  `YoonException` with Yoon's problem `code`), the generated API objects for everything else,
  webhook signature verification (`verify_signature`, `Event`), and webhook helpers for Django
  (`yoonpay.django`), FastAPI (`yoonpay.fastapi`) and Flask (`yoonpay.flask`).
