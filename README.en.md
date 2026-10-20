# Awice Open Platform SDK

English | [简体中文](./README.md)

> Hosted on [GitHub](https://github.com/HJP957547207/awice-openapi-sdk) (primary), mirrored to [Gitee](https://gitee.com/baige95/awice-openapi-sdk) for users in mainland China. Both stay in sync.

Official SDKs for the Awice (5688.cn) international logistics rate API. Query live **FCL ocean freight, LCL consolidation, air freight, international courier and Amazon FBA first-mile rates**, plus sailing schedules, four logistics order types and reference data for seaports, airports, carriers, airlines and countries — and plug them straight into your TMS, ERP or quoting page.

- Website: <https://www.5688.cn/open>
- Docs: <https://www.5688.cn/open/docs/start>
- Console: <https://www.5688.cn/user/openapi>

- [Postman collection (including all 9 order endpoints)](./postman/awice-openapi.postman_collection.json): import and set your own app credentials. Create, update and void requests affect real orders.
- Current source version: **1.1.0**. See the [changelog](./CHANGELOG.md).

## Why use the SDK

- **Single file, zero dependencies.** PHP needs curl (PHP 7.1+); Python uses the standard library (3.7+); Java uses JDK libraries (11+, including 17 and 21). Copy one file into your project; no Composer, pip or Maven dependency required.
- **Signing handled for you.** The two things people get wrong about HMAC-SHA256 here (the signed PATH excludes the `/api` prefix; the body must be MD5'd and sent as the *same* JSON string) are already taken care of.
- **Automatic backoff on rate limits.** When you hit the QPS ceiling the SDK retries according to the server's `Retry-After`, so batch jobs don't need their own retry loop.
- **Error messages that tell you what to do.** "Insufficient credits", for example, points you at the console where the free trial pack is waiting — instead of leaving you staring at a numeric code.

## Quick start

Create an app in the [console](https://www.5688.cn/user/openapi/apps) to get your AppKey / AppSecret. Claim the free trial pack for rate queries that require credits. Ping and the order APIs do not require a credit balance; orders require a verified customer.

### PHP

```php
require 'AwiceClient.php';

$client = new \Awice\OpenApi\AwiceClient('YOUR_APP_KEY', 'YOUR_APP_SECRET');

$client->ping();   // free, verifies your signature works

$res = $client->fcl([
    'pol'   => 'CNSHA',   // port of loading: UN/LOCODE, Chinese or English name
    'pod'   => 'USLAX',   // port of discharge
    'limit' => 10,
]);

foreach ($res['list'] as $rate) {
    $p = $rate['prices'];
    echo "{$rate['carrier']['name']}  20GP {$p['20GP']}  40HQ {$p['40HQ']} {$p['currency']}"
        . "  transit {$rate['transit']['days']}d  valid until {$rate['valid_until']}\n";
}
```

### Python

```python
from awice_client import AwiceClient

client = AwiceClient("YOUR_APP_KEY", "YOUR_APP_SECRET")
client.ping()

res = client.fcl(pol="CNSHA", pod="USLAX", limit=10)
for rate in res["list"]:
    p = rate["prices"]
    print(rate["carrier"]["name"], p["20GP"], p["40HQ"], p["currency"],
          f'transit {rate["transit"]["days"]}d', rate["valid_until"])
```

### Java (JDK 11+)

Copy [java/AwiceClient.java](./java/AwiceClient.java); no third-party dependencies.

```java
AwiceClient client = new AwiceClient(key, secret);
AwiceClient.ApiResponse res = client.fcl(
    java.util.Map.of("pol", "CNSHA", "pod", "USLAX", "limit", 10));
System.out.println(AwiceClient.Json.stringify(res.getData()));
```

[Java setup, responses and errors](./java/README.en.md). Java returns ApiResponse with the full envelope; PHP/Python single-file clients return data directly.

## FCL surcharges: per container and per bill

`list[].surcharges[]` is returned unchanged, including `code`, `name`, `name_en`, `20GP`, `40GP`, `40HQ`, `currency`, `remark`, the billing basis `charge_unit`, and the per-bill amount `singleprice`. The SDK does not combine fees or calculate order receivables.

Let `C = 20GP rate × 20GP count + 40GP rate × 40GP count + 40HQ rate × 40HQ count`:

| charge_unit | Billing basis | Amount for one bill |
| --- | --- | --- |
| `CONTAINER` | Per container | `C` |
| `BILL` | Per bill | `singleprice` once; ignore legacy container amounts |
| `MIXED` | Containers plus a bill fee | `C + singleprice`, with the bill portion counted once |
| `UNKNOWN` / missing / unrecognized | Unclear | Stop automatic calculation and confirm the basis |

Illustrative amounts, not a current quotation:

```json
[
  {"code":"DOC","name":"文件费","name_en":"Documentation Fee","20GP":450,"40GP":450,"40HQ":450,"currency":"CNY","remark":"","charge_unit":"BILL","singleprice":450},
  {"code":"THC","name":"码头费","name_en":"Terminal Handling Charge","20GP":908,"40GP":1362,"40HQ":1362,"currency":"CNY","remark":"","charge_unit":"CONTAINER","singleprice":0}
]
```

For two 20GP containers, DOC is 450 CNY and THC is 1816 CNY, totaling 2266 CNY for these two fees. Legacy per-bill fees can repeat the same amount in all three container fields: do not multiply them by the container count. Equal container amounts alone do not establish the billing basis.

Group totals by `currency`. `prices` contains USD ocean freight separately from surcharges; do not add CNY or other currencies directly to it. An absent fee does not mean it is free, and the response may not cover every destination charge.

PHP/Python single-file clients return `result["list"]` directly. Java exposes `list` through `response.getData()` (Map / List, with BigDecimal amounts). The PHP/Python directory packages under the website download folder return the full envelope: use `result["data"]["list"]`.

[Complete FCL fields and coverage](https://www.5688.cn/en/open/docs/fcl)

## Endpoints

Available endpoints: **Anything not listed here does not exist.**

| Method | Endpoint | Description |
|---|---|---|
| `fcl()` | `POST /openapi/v1/freight/fcl/search` | FCL ocean freight rates |
| `lcl()` | `POST /openapi/v1/freight/lcl/search` | LCL consolidation rates |
| `air()` | `POST /openapi/v1/freight/air/search` | Air freight rates |
| `express()` | `POST /openapi/v1/freight/express/search` | International courier / line-haul rates |
| `fba()` | `POST /openapi/v1/freight/fba/search` | Amazon FBA first-mile rates |
| `schedule()` | `POST /openapi/v1/schedule/search` | Sailing schedules by port pair, carrier and ETD range |
| `schedule_detail()` / `scheduleDetail()` | `POST /openapi/v1/schedule/detail` | Details from a schedule ID obtained by the same app |
| `ports()` | `GET /openapi/v1/dict/port` | Seaport dictionary (UN/LOCODE) |
| `airports()` | `GET /openapi/v1/dict/airport` | Airport dictionary (IATA) |
| `carriers()` | `GET /openapi/v1/dict/carrier` | Ocean carrier dictionary |
| `airlines()` | `GET /openapi/v1/dict/airline` | Airline dictionary |
| `countries()` | `GET /openapi/v1/dict/country` | Country dictionary |
| `popular()` | `GET /openapi/v1/dict/popular` | Common subset, for populating dropdowns |
| `ping()` | `ANY /openapi/v1/ping` | Connectivity check, not billed |

> Vessel space and arbitrary-number cargo tracking are not available. Four order types, own-order details including approved receivables, and saved tracking events for your own orders are documented below.

## Specifying ports

`pol` / `pod` accept three forms, most reliable first:

1. **UN/LOCODE** (recommended): `CNSHA`, `USLAX`, `MYPKG`
2. **English name**: `shanghai`, `port klang`
3. **Chinese name**: `上海`, `钦州港`

Ambiguous names return `1002` — "Auckland" exists in both the US (`USOAK`) and New Zealand (`NZAUC`). Two ways out:

```python
# Disambiguate with a country code
client.fcl(pol="CNSHA", pod="Auckland", pod_country="US")

# Or look up the exact code first
client.ports(keyword="Auckland")
```

The SDK exposes the alternatives on the exception's `candidates` attribute, ready to show the user.

## Two things to get right if you sign requests yourself

**1. The signed PATH excludes the `/api` prefix**

```
Request URL:  https://www.5688.cn/api/openapi/v1/freight/fcl/search
Signed PATH:                     /openapi/v1/freight/fcl/search
```

Query strings are excluded too. This is the number one cause of `2002 signature verification failed`.

**2. MD5 and send the *same* JSON string**

Serialising once for the signature and again for the request body will break the signature as soon as key order or whitespace differs.

The signature itself is:

```
message   = METHOD \n PATH \n TIMESTAMP \n NONCE \n MD5(BODY)
signature = hmac_sha256(message, app_secret)   // 64-char lowercase hex
```

sent as four headers: `X-Awice-AppKey`, `X-Awice-Timestamp`, `X-Awice-Nonce`, `X-Awice-Signature`.

## Error codes

| Code | Meaning | What to do |
|---|---|---|
| `0` | Success | — |
| `1001` | Invalid parameter / unknown port name | Look up the code via `ports()` |
| `1002` | Ambiguous name | Add `pol_country` / `pod_country`, or use the code |
| `2002` | Signature verification failed | See the two pitfalls above |
| `2003` | Timestamp outside ±300s | Sync your server clock with NTP |
| `2004` | Nonce reused | A nonce is valid once per 10 minutes |
| `2005` | IP not whitelisted | Add your egress IP in the console |
| `3001` | Insufficient credits | Claim the free trial pack in the console |
| `3003` | No active subscription | Same as above |
| `3004` | Subscription expired | Renew |
| `4001` | QPS limit exceeded | SDK retries automatically; otherwise lower concurrency or upgrade |
| `4004` | Courier concurrency limit | Wait for the previous request to return |
| `5002` | Upstream temporarily unavailable | Retry later |

Business failures (`code != 0`) are **not** charged against your credits.

## Timeouts

The courier endpoint queries several upstream providers in real time; **30+ seconds is normal** on a cache miss. The SDK defaults to a 60-second read timeout — if you lower it, or sit behind a gateway, make sure both ends allow enough time.

```php
$client->setTimeout(90, 5);   // 90s read, 5s connect
```

```python
client = AwiceClient(key, secret, timeout=90)
```

## Pricing

You buy a plan, which grants credits; each call deducts credits based on the endpoint type (ocean cheapest, courier highest since it polls upstream live), and dictionary endpoints come with a free allowance. Current pricing lives in the [console](https://www.5688.cn/user/openapi/plans) — deliberately not duplicated here so it can never go stale.

## License

MIT

## Sailing schedules and details

Non-empty searches default to 1 credit per call with up to 50 records. Empty results are free. Each record has an app-bound `schedule_id` and `terminals`. Details default to no additional charge, configurable in the dashboard, and accept only an ID obtained by the same app. Missing fields are null; unavailable legs/port calls are []. Removed sailings return empty data.

```python
page = client.schedule(pol="CNSZX", pod="USLAX", carrier="COSCO", limit=50)
rows = page.get("data", [])
if rows:
    detail = client.schedule_detail(rows[0]["schedule_id"])
```

```php
$page = $client->schedule(["pol" => "CNSZX", "pod" => "USLAX", "limit" => 50]);
if (!empty($page['data'])) {
    $detail = $client->scheduleDetail($page['data'][0]['schedule_id']);
}
```

Endpoints: POST /openapi/v1/schedule/search and POST /openapi/v1/schedule/detail.
Reference: https://www.5688.cn/en/open/docs/schedule#detail

## Logistics Order APIs (0 Credits)

Four order types follow the website customer center workflow. Use the same
/api/openapi/v1/ prefix and a verified website customer linked to your app.
Creation, options and own-order details cost 0 credits and still require signing
and rate limiting. Submission creates a real order awaiting operational processing.

| PHP method | Python method | Java method | Relative path |
| --- | --- | --- | --- |
| createOceanOrder($payload) | create_ocean_order(**payload) | createOceanOrder(payload) | POST /orders/ocean/create |
| createAirOrder($payload) | create_air_order(**payload) | createAirOrder(payload) | POST /orders/air/create |
| createExpressOrder($payload) | create_express_order(**payload) | createExpressOrder(payload) | POST /orders/express/create |
| createFbaOrder($payload) | create_fba_order(**payload) | createFbaOrder(payload) | POST /orders/fba/create |
| orderOptions(['order_type'=>'ocean']) | order_options(order_type="ocean") | orderOptions(Map.of("order_type", "ocean")) | GET /orders/options |
| orderDetail(['order_type'=>'ocean','client_order_no'=>'MY-ORDER-001']) | order_detail(order_type="ocean", client_order_no="MY-ORDER-001") | orderDetail(params) | POST /orders/detail |
| orderTracking(params) | order_tracking(**params) | orderTracking(params) | POST /orders/tracking |
| updateOrder(params) | update_order(**params) | updateOrder(params) | POST /orders/update |
| cancelOrder(params) | cancel_order(**params) | cancelOrder(params) | POST /orders/cancel |

Fetch options before filling a payload. Ocean orders use the internal port
dictionary code, while air orders use airport IATA codes. Contacts may be your
contact IDs or inline objects. See [all parameters and four examples](https://www.5688.cn/open/docs/orders).

client_order_no is required and unique per customer and order type (across apps).
Retry with the same reference and payload to get the original order
(replayed=true). A changed payload under the same reference returns HTTP409 /
code1003. Do not generate a new reference after a timeout: query the original
reference and retry the original payload. A new nonce/timestamp is generated by
the SDK on each request.

The PHP/Python single-file SDKs return data directly. Java returns ApiResponse,
with getData() / getEnvelope() for data or the complete response. PHP/Python
packaged SDKs return the full envelope. This source update does not publish a
new package to public registries.

### Order updates and cancellation

Both endpoints are free (0 credits) and accept order_type plus client_order_no or order_no. update accepts a non-empty changes object; omitted fields/records are retained, supplied objects/lists replace the full value. Only pending (0) or forecast (1) orders can be updated. Only pending (0) orders can be cancelled; cancellation marks the order void (7). Repeating an identical update or cancellation returns replayed=true. Invalid order states return HTTP409 / code1004. The original create request remains replayable after updates; it never overwrites the edited order.

### Order detail bills

orderDetail / order_detail now returns data.bills: only approved/written-off receivable leaves issued to the current customer, after checking order ownership. Other customers’ bills, payables, drafts, reversed bills, and split parent/intermediate bills are excluded. Each bill includes its reference, bill/payment status, totals by original currency, reference CNY total, and customer quote details. Split children retain their own amount; parent details are shown only for an approved receivable parent of the same order and payer with matching amounts. No visible bills returns an empty list. Internal notes, preparers, suppliers, and parent identifiers are omitted. Existing SDK detail methods retain the new field automatically.

### Own-order tracking

POST `/api/openapi/v1/orders/tracking` costs 0 credits. Use the same selectors as details: `order_type` and `client_order_no` or `order_no` (external reference takes precedence). PHP/Java: `orderTracking(params)`; Python: `order_tracking(**params)`. Only saved events for your own order are returned, with time, place, original description, tracking_no, system/manual source, is_forecast, has_time and recorded_at. An empty list means no saved events. No live carrier request or collection is initiated. Forecasts are not completed events. No time zone is inferred; recorded_at is storage time, not a carrier query time.
