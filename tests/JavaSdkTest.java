import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** 只访问临时回环 mock，不读取生产凭据、不查询生产接口或下单。 */
public class JavaSdkTest {
    private static int checks;
    private static final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private static final List<Captured> captured = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private static final String SUCCESS = "{\"code\":0,\"msg\":\"OK\",\"request_id\":\"TEST_ID\","
            + "\"credit_used\":0,\"data\":{\"price\":12.30,\"missing\":null,\"legs\":[],"
            + "\"bills\":[{\"bill_code\":\"OWN_BILL\",\"total_by_currency\":{\"USD\":100.25,\"CNY\":30},\"quotes\":[]}]}}";

    public static void main(String[] args) throws Exception {
        jsonChecks();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", JavaSdkTest::handle);
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port + "/api";
        try {
            AwiceClient client = new AwiceClient("TEST_KEY", "TEST_SECRET", base, Duration.ofSeconds(3), 2);
            endpointChecks(client);
            fclSurchargeChecks(client);
            errorAndRetryChecks(client, base);
            check(serverFailure.get() == null, "Mock server signature/body checks: " + serverFailure.get());
            check(replies.isEmpty(), "All mock replies consumed");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
        try (Socket ignored = new Socket()) {
            boolean closed = false;
            try { ignored.connect(new InetSocketAddress("127.0.0.1", port), 200); }
            catch (IOException expected) { closed = true; }
            check(closed, "Mock listener released");
        }
        System.out.println("PASS Java " + System.getProperty("java.version") + ": " + checks
                + " checks, " + captured.size() + " independently verified signed requests; loopback only.");
    }

    private static void endpointChecks(AwiceClient client) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pol", "上海");
        payload.put("pod", "USLAX");
        payload.put("client_order_no", "JAVA_TEST_ONLY");
        payload.put("consignee", Map.of("address", "测试地址 / A\"B\n😀"));
        payload.put("boxes", Arrays.asList(Map.of("weight", new BigDecimal("12.30")), null));
        List<String> expected = Arrays.asList(
                "freight/fcl/search", "freight/lcl/search", "freight/air/search", "freight/express/search",
                "freight/fba/search", "schedule/search", "schedule/detail", "orders/ocean/create",
                "orders/air/create", "orders/express/create", "orders/fba/create", "orders/options",
                "orders/detail", "orders/tracking", "orders/update", "orders/cancel", "dict/port", "dict/airport", "dict/carrier", "dict/airline",
                "dict/country", "dict/popular", "ping");
        for (int i = 0; i < expected.size(); i++) replies.add(new Reply(200, SUCCESS, null));
        client.fcl(payload); client.lcl(payload); client.air(payload); client.express(payload); client.fba(payload);
        client.schedule(payload); client.scheduleDetail("APP_BOUND_SCHEDULE");
        client.createOceanOrder(payload); client.createAirOrder(payload);
        client.createExpressOrder(payload); client.createFbaOrder(payload);
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("keyword", "上海 A&B+港"); query.put("page", 2); query.put("optional", null);
        client.orderOptions(query);
        AwiceClient.ApiResponse detail = client.orderDetail(payload);
        Map<?, ?> detailData = (Map<?, ?>) detail.getData();
        Map<?, ?> detailBill = (Map<?, ?>) ((List<?>) detailData.get("bills")).get(0);
        check(new BigDecimal("100.25").equals(((Map<?, ?>) detailBill.get("total_by_currency")).get("USD")), "Order bill data and decimal precision retained");
        client.orderTracking(payload); client.updateOrder(payload); client.cancelOrder(payload);
        client.ports(query); client.airports(query); client.carriers(query);
        client.airlines(query); client.countries(query); client.popular(query);
        AwiceClient.ApiResponse result = client.ping();
        check("TEST_ID".equals(result.getRequestId()), "Request ID retained");
        check(new BigDecimal("0").equals(result.getEnvelope().get("credit_used")), "Credits retained");
        check(new BigDecimal("12.30").equals(((Map<?, ?>) result.getData()).get("price")), "Money precision retained");
        for (int i = 0; i < expected.size(); i++) {
            Captured request = captured.get(i);
            check(request.path.equals("/api/openapi/v1/" + expected.get(i)), "Endpoint " + expected.get(i));
            if (request.path.contains("/dict/") || request.path.endsWith("/options")) {
                check("GET".equals(request.method) && request.body.isEmpty(), "GET sends empty body");
                check(request.query.equals("keyword=%E4%B8%8A%E6%B5%B7+A%26B%2B%E6%B8%AF&page=2"), "UTF-8 query, null omitted");
            } else if (request.path.endsWith("/schedule/detail")) {
                check(request.body.equals("{\"schedule_id\":\"APP_BOUND_SCHEDULE\"}"), "Schedule ID passed exactly");
            } else if (!request.path.endsWith("/ping")) {
                check(request.body.contains("JAVA_TEST_ONLY") && request.body.contains("测试地址"), "Nested body and reference retained");
            }
        }
        check(new HashSet<>(nonces(0)).size() == expected.size(), "Every endpoint has a unique nonce");
    }

    private static void fclSurchargeChecks(AwiceClient client) throws Exception {
        String fixture = Files.readString(Path.of("tests", "fixtures", "fcl-surcharges.json"), StandardCharsets.UTF_8);
        replies.add(new Reply(200, fixture, null));
        AwiceClient.ApiResponse response = client.fcl(Map.of("pol", "CNSHA", "pod", "USLAX"));
        check(response.getEnvelope().equals(AwiceClient.Json.parse(fixture)), "Complete FCL response preserved");
        Map<?, ?> data = (Map<?, ?>) response.getData();
        List<?> rows = (List<?>) data.get("list");
        List<?> fees = (List<?>) ((Map<?, ?>) rows.get(0)).get("surcharges");
        List<String> units = Arrays.asList("BILL", "CONTAINER", "MIXED", "UNKNOWN", "CONTAINER");
        for (int i = 0; i < units.size(); i++) {
            Map<?, ?> fee = (Map<?, ?>) fees.get(i);
            check(units.get(i).equals(fee.get("charge_unit")) && fee.size() == 10, "All surcharge fields and unit retained");
        }
        check(new BigDecimal("450").equals(((Map<?, ?>) fees.get(0)).get("singleprice")), "Per-bill amount retained");
        check(new BigDecimal("30.25").equals(((Map<?, ?>) fees.get(2)).get("singleprice"))
                && new BigDecimal("12.3").equals(((Map<?, ?>) fees.get(2)).get("20GP")), "Mixed decimal amounts retained");
        check(((List<?>) ((Map<?, ?>) rows.get(1)).get("surcharges")).isEmpty(), "Empty surcharges preserved");
    }

    private static void errorAndRetryChecks(AwiceClient client, String base) throws Exception {
        int before = captured.size();
        replies.add(new Reply(429, "{\"code\":4001,\"msg\":\"limited\",\"data\":[]}", "0"));
        replies.add(new Reply(429, "{\"code\":4004,\"msg\":\"busy\",\"data\":[]}",
                ZonedDateTime.now().minusSeconds(2).format(DateTimeFormatter.RFC_1123_DATE_TIME)));
        replies.add(new Reply(200, SUCCESS, null));
        client.createOceanOrder(Map.of("client_order_no", "KEEP_REFERENCE", "address", "原地址"));
        check(captured.size() == before + 3, "Retries honor both rate-limit codes and date header");
        check(new HashSet<>(nonces(before)).size() == 3, "Retry nonces regenerated");
        check(captured.get(before).body.equals(captured.get(before + 1).body)
                && captured.get(before).body.equals(captured.get(before + 2).body), "Retry bytes and order reference unchanged");
        for (int i = 0; i < 3; i++) replies.add(new Reply(429, "{\"code\":4001,\"msg\":\"limited\"}", "0"));
        before = captured.size();
        try { client.ping(); throw new AssertionError("Expected exhausted retries"); }
        catch (AwiceClient.ApiException ex) { check(ex.code == 4001, "Final rate-limit error retained"); }
        check(captured.size() == before + 3, "Exactly maxRetries + 1 attempts");
        AwiceClient noRetry = new AwiceClient("TEST_KEY", "TEST_SECRET", base, Duration.ofSeconds(3), 0);
        replies.add(new Reply(429, "{\"code\":4004,\"msg\":\"busy\"}", "0"));
        before = captured.size();
        try { noRetry.ping(); throw new AssertionError("Expected limit error"); }
        catch (AwiceClient.ApiException ex) { check(ex.code == 4004, "Retry disabled"); }
        check(captured.size() == before + 1, "No retry when configured zero");
        replies.add(new Reply(400, "{\"code\":1002,\"msg\":\"ambiguous\",\"request_id\":\"ERR_ID\","
                + "\"data\":{\"candidates\":[{\"code\":\"USOAK\"},{\"code\":\"NZAUC\"}]}}", null));
        try { client.fcl(Map.of()); throw new AssertionError("Expected ambiguity"); }
        catch (AwiceClient.ApiException ex) {
            check(ex.code == 1002 && ex.httpStatus == 400 && ex.candidates.size() == 2
                    && "ERR_ID".equals(ex.requestId), "Structured ambiguity retained");
        }
        replies.add(new Reply(400, "{\"code\":1001,\"msg\":\"invalid\",\"data\":{\"errors\":[]},"
                + "\"errors\":[{\"field\":\"client_order_no\",\"message\":\"required\"}]}", null));
        try { client.createAirOrder(Map.of()); throw new AssertionError("Expected validation"); }
        catch (AwiceClient.ApiException ex) { check(ex.errors.size() == 1, "Top-level validation errors retained"); }
        replies.add(new Reply(400, "{\"code\":1001,\"msg\":\"invalid\",\"data\":{\"errors\":["
                + "{\"field\":\"pol\"},{\"field\":\"pod\"}]}}", null));
        try { client.fcl(Map.of()); throw new AssertionError("Expected nested validation"); }
        catch (AwiceClient.ApiException ex) { check(ex.errors.size() == 2, "Nested validation errors retained"); }
        replies.add(new Reply(409, "{\"code\":1003,\"msg\":\"conflict\",\"data\":[]}", null));
        before = captured.size();
        try { client.createFbaOrder(Map.of()); throw new AssertionError("Expected conflict"); }
        catch (AwiceClient.ApiException ex) { check(ex.code == 1003, "Order conflict retained"); }
        check(captured.size() == before + 1, "Order conflict never retried");
        for (String malformed : Arrays.asList("<html>502 secret</html>", "[]", "{\"data\":[]}",
                "{\"code\":0.1}", "{\"code\":0,\"code\":1}")) {
            replies.add(new Reply(502, malformed, null));
            try { client.ping(); throw new AssertionError("Expected bad response"); }
            catch (AwiceClient.TransportException ex) {
                check(ex.httpStatus == 502 && !ex.getMessage().contains("secret"), "Malformed response safely rejected");
            }
        }
        replies.add(new Reply(302, "{\"code\":0}", null));
        try { client.ping(); throw new AssertionError("Expected status mismatch"); }
        catch (AwiceClient.TransportException ex) { check(ex.httpStatus == 302, "Redirect not followed or treated as success"); }
        replies.add(new Reply(200, "{\"code\":0,\"data\":[]}", null));
        check(client.ping().getData() instanceof List, "Empty array data preserved");
        replies.add(new Reply(200, "{\"code\":0,\"data\":null}", null));
        check(client.ping().getData() == null, "Null data preserved");
        for (String path : Arrays.asList("/api/openapi/v1/ping", "/openapi/v1/ping?q=x", "https://elsewhere.invalid", "/openapi/v1/../ping")) {
            try { client.get(path, Map.of()); throw new AssertionError("Expected invalid path"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        AwiceClient impatient = new AwiceClient("TEST_KEY", "TEST_SECRET", base, Duration.ofSeconds(1), 2);
        replies.add(new Reply(200, SUCCESS, null));
        impatient.ping(); // 先建立连接，避免冷启动时间被误当作“请求已送达”的超时。
        replies.add(new Reply(200, SUCCESS, null, 3000));
        before = captured.size();
        try { impatient.createExpressOrder(Map.of("client_order_no", "TIMEOUT")); throw new AssertionError("Expected timeout"); }
        catch (HttpTimeoutException expected) { checks++; }
        check(captured.size() == before + 1, "Network timeout never retries orders");
    }

    private static void jsonChecks() {
        String text = "{\"text\":\"中文\\n\\t\\b\\f\\r\\\"\\\\/\\uD83D\\uDE00\",\"amount\":123.450,"
                + "\"negative\":-1.2e-3,\"large\":9223372036854775808,\"items\":[true,false,null,[],{}]}";
        Object parsed = AwiceClient.Json.parse(text);
        check(parsed.equals(AwiceClient.Json.parse(AwiceClient.Json.stringify(parsed))), "JSON Unicode/null/arrays/numbers round trip");
        check(AwiceClient.Json.stringify(new int[]{1, 2}).equals("[1,2]"), "Primitive arrays serialized");
        for (String bad : Arrays.asList("", "[1,]", "{\"a\":1,}", "{\"x\":1,\"x\":2}", "01", "NaN",
                "+1", "1.", "1e", "truefalse", "\"\\u+123\"", "\"\\q\"", "\"\n\"")) {
            try { AwiceClient.Json.parse(bad); throw new AssertionError("Accepted invalid JSON"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        for (Object bad : Arrays.asList(Double.NaN, Double.POSITIVE_INFINITY, Map.of(1, "key"), new Object())) {
            try { AwiceClient.Json.stringify(bad); throw new AssertionError("Accepted invalid JSON value"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        try { AwiceClient.Json.parse("[".repeat(130) + "0" + "]".repeat(130)); throw new AssertionError("Expected depth limit"); }
        catch (IllegalArgumentException expected) { checks++; }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        Reply reply = replies.poll();
        try {
            if (reply == null) throw new AssertionError("Unexpected HTTP request");
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bytes, StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            String nonce = exchange.getRequestHeaders().getFirst("X-Awice-Nonce");
            String timestamp = exchange.getRequestHeaders().getFirst("X-Awice-Timestamp");
            String message = exchange.getRequestMethod() + "\n" + path.substring(4) + "\n" + timestamp
                    + "\n" + nonce + "\n" + hex(MessageDigest.getInstance("MD5").digest(bytes));
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec("TEST_SECRET".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!hex(hmac.doFinal(message.getBytes(StandardCharsets.UTF_8)))
                    .equals(exchange.getRequestHeaders().getFirst("X-Awice-Signature"))) {
                throw new AssertionError("HMAC does not match transmitted bytes / path");
            }
            if (!"TEST_KEY".equals(exchange.getRequestHeaders().getFirst("X-Awice-AppKey"))) throw new AssertionError("AppKey missing");
            if (!nonce.matches("[0-9a-f]{32}")) throw new AssertionError("Invalid nonce");
            if (Math.abs(System.currentTimeMillis() / 1000 - Long.parseLong(timestamp)) > 5) throw new AssertionError("Timestamp not seconds");
            if (body.contains("TEST_SECRET")) throw new AssertionError("Secret leaked in body");
            captured.add(new Captured(path, exchange.getRequestMethod(), body, exchange.getRequestURI().getRawQuery(), nonce));
            if (reply.delay > 0) Thread.sleep(reply.delay);
            if (reply.retryAfter != null) exchange.getResponseHeaders().add("Retry-After", reply.retryAfter);
            byte[] output = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status, output.length);
            exchange.getResponseBody().write(output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Throwable ex) {
            serverFailure.compareAndSet(null, ex);
            if (exchange.getResponseCode() == -1) exchange.sendResponseHeaders(500, -1);
        } finally { exchange.close(); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format("%02x", b & 255));
        return result.toString();
    }
    private static List<String> nonces(int start) {
        List<String> result = new ArrayList<>();
        for (int i = start; i < captured.size(); i++) result.add(captured.get(i).nonce);
        return result;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final class Captured {
        final String path, method, body, query, nonce;
        Captured(String path, String method, String body, String query, String nonce) {
            this.path = path; this.method = method; this.body = body; this.query = query; this.nonce = nonce;
        }
    }
    private static final class Reply {
        final int status;
        final String body, retryAfter;
        final long delay;
        Reply(int status, String body, String retryAfter) { this(status, body, retryAfter, 0); }
        Reply(int status, String body, String retryAfter, long delay) {
            this.status = status; this.body = body; this.retryAfter = retryAfter; this.delay = delay;
        }
    }
}
