# Changelog

## 1.1.0 — 2026-10-20

- Publish `orderTracking` / `order_tracking`, `updateOrder` / `update_order`, and `cancelOrder` / `cancel_order` for PHP, Python and Java. Together with four create methods, options and details, all 9 order endpoints are available at 0 credits.
- Document visible customer bills in order details, saved tracking events, update/void status restrictions and idempotent requests.
- Document FCL surcharge `charge_unit` and `singleprice`, including per-container, per-bill, mixed and unknown units, legacy duplicated amounts and separate currency totals. Responses continue to pass these fields through unchanged.
- Include a Postman collection with all 9 order requests and automatic signing.
- Add standalone offline order/surcharge checks for PHP and Python, extend Java checks, and verify Postman signing.

Source release only; no Composer, PyPI or Maven Central package is published by this update.
