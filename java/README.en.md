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
| ping | Free connectivity check |

Methods accept `Map<String, ?>`, except `ping()` and `scheduleDetail(String)`. Use `Map.of()` for empty parameters. Request bodies support nested Maps, Lists, arrays, strings, numbers, booleans and null; Java Beans are not serialized automatically. `AwiceClient.Json.stringify()` and `parse()` convert JSON data.

Read the [order parameters](https://www.5688.cn/en/open/docs/orders) before submitting real orders. Your app must be linked to a verified customer. Keep `client_order_no` and the original payload after a timeout: query the original reference before retrying. References are unique per customer and order type across apps.

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
