# Java SDK (JDK 11+)

English | [简体中文](./README.md)

Single file, no third-party dependencies. Uses the JDK HttpClient and HMAC-SHA256, with built-in JSON data conversion. Targets Java 11 APIs and runs on JDK 11 or later, including 17 and 21. Signing, HTTP, JSON, rate-limit and error tests have passed locally on Java 11, 17 and 21. No preview features, Maven/Gradle dependency or Spring Boot required.

- [GitHub](https://github.com/HJP957547207/awice-openapi-sdk/tree/main/java)
- [Gitee mirror](https://gitee.com/baige95/awice-openapi-sdk/tree/main/java)
- [API documentation](https://www.5688.cn/en/open/docs/start)

## Quick start

Copy `AwiceClient.java` into your project, adding your package declaration if needed. To run the included example, put both Java files in the same directory:

```bash
javac --release 11 -encoding UTF-8 AwiceClient.java Example.java
export AWICE_APP_KEY='your AppKey'
export AWICE_APP_SECRET='your AppSecret'
java Example
```

PowerShell: use `$env:AWICE_APP_KEY = 'your AppKey'` and `$env:AWICE_APP_SECRET = 'your AppSecret'`. Obtain credentials in the [developer console](https://www.5688.cn/user/openapi/apps). Never commit them.

The example runs only a free ping by default. `java Example --fcl` also queries rates, subject to endpoint pricing. It never submits orders.

```java
AwiceClient client = new AwiceClient(key, secret);
AwiceClient.ApiResponse result = client.fcl(
    Map.of("pol", "CNSHA", "pod", "USLAX", "limit", 10));
System.out.println(AwiceClient.Json.stringify(result.getData()));
System.out.println(result.getEnvelope().get("credit_used"));
```

## API and responses

Every method returns `ApiResponse`: `getData()` for business data, `getEnvelope()` for the full response, and `getRequestId()` for support. JSON objects become Maps, arrays become Lists, numbers become BigDecimal, and null remains null. An unavailable schedule detail can return an empty array; do not assume data is always a Map.

| Methods | Purpose |
| --- | --- |
| fcl / lcl / air / express / fba | Freight rates |
| schedule / scheduleDetail | Sailing searches and details |
| ports / airports / carriers / airlines / countries / popular | Dictionaries |
| createOceanOrder / createAirOrder / createExpressOrder / createFbaOrder | Real orders, 0 credits |
| orderOptions / orderDetail | Options and own-order details, 0 credits |
| orderTracking | Saved tracking for your own order, 0 credits |
| updateOrder / cancelOrder | Update / void your own order, 0 credits |
| ping | Free connectivity check |

Methods accept `Map<String, ?>`, except `ping()` and `scheduleDetail(String)`. Use `Map.of()` for empty parameters. Request bodies support nested Maps, Lists, arrays, strings, numbers, booleans and null; Java Beans are not serialized automatically. `AwiceClient.Json.stringify()` and `parse()` convert JSON data.

Read the [order parameters](https://www.5688.cn/en/open/docs/orders) before submitting real orders. Your app must be linked to a verified customer. Keep `client_order_no` and the original payload after a timeout: query the original reference before retrying. References are unique per customer and order type across apps.

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

## Configuration and errors

```java
AwiceClient client = new AwiceClient(key, secret, AwiceClient.DEFAULT_BASE_URL,
    java.time.Duration.ofSeconds(90), 3);
```

Defaults: 60-second request timeout, 5-second connection timeout, and up to 3 rate-limit retries. Courier queries may take over 30 seconds. Only business codes 4001/4004 are retried, using Retry-After seconds or an HTTP date (1 second if absent/invalid). Retries use new timestamps/nonces with identical body bytes. Network failures and other API errors are not retried automatically.

`ApiException` exposes `code`, `httpStatus`, `requestId`, `candidates`, `errors` and the full `response`. `IOException` covers transport/response errors. Handle `InterruptedException` by restoring the interrupt flag or propagating it.

Signature paths exclude `/api` and query strings. The default base URL is `https://www.5688.cn/api`. Reuse the client across requests.

MIT licensed source; no Maven Central package has been published.

Run offline checks from the repository root (temporary loopback mock, closed automatically):

```bash
javac --release 11 -encoding UTF-8 -d .java-test java/AwiceClient.java tests/JavaSdkTest.java
java -cp .java-test JavaSdkTest
```

`updateOrder(params)` / `cancelOrder(params)` call the free order update/void endpoints. Require order_type and client_order_no or order_no. Updates use a changes object and allow only pending (0) or forecast (1) orders; omitted fields retain current values. Cancellation allows only pending (0) orders and sets void (7). Invalid states return HTTP409 / code1004. Identical retries return replayed=true.

### Order detail bills

orderDetail / order_detail now returns data.bills: only approved/written-off receivable leaves issued to the current customer, after checking order ownership. Other customers’ bills, payables, drafts, reversed bills, and split parent/intermediate bills are excluded. Each bill includes its reference, bill/payment status, totals by original currency, reference CNY total, and customer quote details. Split children retain their own amount; parent details are shown only for an approved receivable parent of the same order and payer with matching amounts. No visible bills returns an empty list. Internal notes, preparers, suppliers, and parent identifiers are omitted. Existing SDK detail methods retain the new field automatically.

### Own-order tracking

POST `/api/openapi/v1/orders/tracking` costs 0 credits. Use the same selectors as details: `order_type` and `client_order_no` or `order_no` (external reference takes precedence). PHP/Java: `orderTracking(params)`; Python: `order_tracking(**params)`. Only saved events for your own order are returned, with time, place, original description, tracking_no, system/manual source, is_forecast, has_time and recorded_at. An empty list means no saved events. No live carrier request or collection is initiated. Forecasts are not completed events. No time zone is inferred; recorded_at is storage time, not a carrier query time.
