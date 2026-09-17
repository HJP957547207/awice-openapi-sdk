# Awice Open Platform SDK

English | [简体中文](./README.md)

Official SDKs for the Awice (5688.cn) international logistics rate API. Query live **FCL ocean freight, LCL consolidation, air freight, international courier and Amazon FBA first-mile rates**, plus reference data for seaports, airports, carriers, airlines and countries — and plug them straight into your TMS, ERP or quoting page.

- Website: <https://www.5688.cn/open>
- Docs: <https://www.5688.cn/open/docs/start>
- Console: <https://www.5688.cn/user/openapi>

## Why use the SDK

- **Single file, zero dependencies.** PHP needs only the curl extension (PHP 7.1+); Python uses the standard library only (Python 3.7+). Drop one file into your project — no composer, no pip.
- **Signing handled for you.** The two things people get wrong about HMAC-SHA256 here (the signed PATH excludes the `/api` prefix; the body must be MD5'd and sent as the *same* JSON string) are already taken care of.
- **Automatic backoff on rate limits.** When you hit the QPS ceiling the SDK retries according to the server's `Retry-After`, so batch jobs don't need their own retry loop.
- **Error messages that tell you what to do.** "Insufficient credits", for example, points you at the console where the free trial pack is waiting — instead of leaving you staring at a numeric code.

## Quick start

Create an app in the [console](https://www.5688.cn/user/openapi/apps) to get your AppKey / AppSecret, **and click "claim free trial pack" while you're there** — without it every call is rejected. This is by far the most common thing new users get stuck on.

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

## Endpoints

Ten public endpoints in total — this table is all of them. **Anything not listed here does not exist.**

| Method | Endpoint | Description |
|---|---|---|
| `fcl()` | `POST /openapi/v1/freight/fcl/search` | FCL ocean freight rates |
| `lcl()` | `POST /openapi/v1/freight/lcl/search` | LCL consolidation rates |
| `air()` | `POST /openapi/v1/freight/air/search` | Air freight rates |
| `express()` | `POST /openapi/v1/freight/express/search` | International courier / line-haul rates |
| `fba()` | `POST /openapi/v1/freight/fba/search` | Amazon FBA first-mile rates |
| `ports()` | `GET /openapi/v1/dict/port` | Seaport dictionary (UN/LOCODE) |
| `airports()` | `GET /openapi/v1/dict/airport` | Airport dictionary (IATA) |
| `carriers()` | `GET /openapi/v1/dict/carrier` | Ocean carrier dictionary |
| `airlines()` | `GET /openapi/v1/dict/airline` | Airline dictionary |
| `countries()` | `GET /openapi/v1/dict/country` | Country dictionary |
| `popular()` | `GET /openapi/v1/dict/popular` | Common subset, for populating dropdowns |
| `ping()` | `ANY /openapi/v1/ping` | Connectivity check, not billed |

> There are currently **no** endpoints for vessel space, sailing schedules, cargo tracking, or orders/bookings/invoices. If you need those, leave a note on the website and it will be considered for the roadmap.

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
