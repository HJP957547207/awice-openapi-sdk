# Offline SDK checks

Run from the repository root. Tests use fixed dummy credentials and intercepted transports or a temporary loopback server. No production calls, credits or real order writes are involved.

```bash
php tests/OrderApiOfflineTest.php
python tests/order_api_offline_test.py
javac --release 11 -encoding UTF-8 -d .java-test java/AwiceClient.java tests/JavaSdkTest.java
java -cp .java-test JavaSdkTest
node tests/order_postman_offline_test.mjs
```

PHP needs the curl extension for its constants. Python uses only the standard library. Java targets JDK 11+. The Postman check needs Node.js with ES modules.

PHP/Python verify all 9 order methods, request signatures, HTTP methods, external references, nested request fields, visible bills and saved tracking. All three languages verify that the FCL fixture survives decoding unchanged, including the ten surcharge fields, `BILL` / `CONTAINER` / `MIXED` / `UNKNOWN`, decimal amounts, currencies and empty surcharges. The Java suite also checks JSON, retry limits, structured errors and timeouts, and confirms its listener closes.

The PHP/Python scripts accept an optional path to the website's directory package to run the same checks against that implementation as well. The Postman test accepts an optional collection path. These optional files are not required to test this repository.
