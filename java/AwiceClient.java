import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 物流巴巴开放平台 Java SDK，JDK 11+，单文件、无第三方依赖，MIT。
 * 复制到项目即可使用；有 package 的项目可在文件顶部添加自己的 package 声明。
 * 文档：https://www.5688.cn/open/docs/start
 * 签名 PATH 不含 /api 和 query；签名与发送共用同一份 UTF-8 JSON 字节。
 * 仅对明确的 4001/4004 限流响应自动重试，网络失败不自动重试真实下单。
 */
public final class AwiceClient {
    public static final String VERSION = "1.0.0";
    public static final String DEFAULT_BASE_URL = "https://www.5688.cn/api";
    private final String appKey;
    private final String appSecret;
    private final String baseUrl;
    private final Duration timeout;
    private final int maxRetries;
    private final HttpClient http;
    private final SecureRandom random = new SecureRandom();

    public AwiceClient(String appKey, String appSecret) {
        this(appKey, appSecret, DEFAULT_BASE_URL, Duration.ofSeconds(60), 3);
    }

    /** timeout 为单次请求超时；maxRetries=0 关闭限流重试。 */
    public AwiceClient(String appKey, String appSecret, String baseUrl,
                       Duration timeout, int maxRetries) {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalArgumentException("AppKey / AppSecret 不能为空，请到控制台创建应用");
        }
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl").replaceAll("/+$", "");
        URI base = URI.create(this.baseUrl);
        if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl 必须为 HTTP(S) 地址，不含凭据、query 或 fragment");
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative() || maxRetries < 0) {
            throw new IllegalArgumentException("timeout 必须大于 0，maxRetries 不能为负数");
        }
        this.maxRetries = maxRetries;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public ApiResponse fcl(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/freight/fcl/search", params);
    }
    public ApiResponse lcl(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/freight/lcl/search", params);
    }
    public ApiResponse air(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/freight/air/search", params);
    }
    public ApiResponse express(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/freight/express/search", params);
    }
    public ApiResponse fba(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/freight/fba/search", params);
    }
    public ApiResponse schedule(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/schedule/search", params);
    }
    public ApiResponse scheduleDetail(String scheduleId) throws IOException, InterruptedException {
        return post("/openapi/v1/schedule/detail", Map.of("schedule_id", scheduleId));
    }
    public ApiResponse createOceanOrder(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/orders/ocean/create", params);
    }
    public ApiResponse createAirOrder(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/orders/air/create", params);
    }
    public ApiResponse createExpressOrder(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/orders/express/create", params);
    }
    public ApiResponse createFbaOrder(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/orders/fba/create", params);
    }
    public ApiResponse orderOptions(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/orders/options", query);
    }
    public ApiResponse orderDetail(Map<String, ?> params) throws IOException, InterruptedException {
        return post("/openapi/v1/orders/detail", params);
    }
    public ApiResponse ports(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/port", query);
    }
    public ApiResponse airports(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/airport", query);
    }
    public ApiResponse carriers(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/carrier", query);
    }
    public ApiResponse airlines(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/airline", query);
    }
    public ApiResponse countries(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/country", query);
    }
    public ApiResponse popular(Map<String, ?> query) throws IOException, InterruptedException {
        return get("/openapi/v1/dict/popular", query);
    }
    public ApiResponse ping() throws IOException, InterruptedException {
        return get("/openapi/v1/ping", Collections.emptyMap());
    }

    public ApiResponse post(String path, Map<String, ?> body) throws IOException, InterruptedException {
        // 只序列化一次。即使限流重试，也保留原始单号和完全相同的 body。
        byte[] payload = Json.stringify(body == null ? Collections.emptyMap() : body)
                .getBytes(StandardCharsets.UTF_8);
        return request("POST", path, payload, Collections.emptyMap());
    }

    public ApiResponse get(String path, Map<String, ?> query) throws IOException, InterruptedException {
        return request("GET", path, new byte[0], query == null ? Collections.emptyMap() : query);
    }

    private ApiResponse request(String method, String path, byte[] payload, Map<String, ?> query)
            throws IOException, InterruptedException {
        if (path == null || !path.matches("/openapi/v1/[A-Za-z0-9_/-]+")
                || path.contains("//")) {
            throw new IllegalArgumentException("path 必须以 /openapi/v1/ 开头，不含 /api 前缀或 query");
        }
        StringBuilder url = new StringBuilder(baseUrl).append(path);
        boolean first = true;
        for (Map.Entry<String, ?> entry : query.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
                throw new IllegalArgumentException("query 参数必须为字符串、数字或布尔值");
            }
            url.append(first ? '?' : '&');
            first = false;
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=').append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
        }
        URI uri = URI.create(url.toString());
        for (int attempt = 0; ; attempt++) {
            String ts = Long.toString(Instant.now().getEpochSecond());
            byte[] nonceBytes = new byte[16];
            random.nextBytes(nonceBytes);
            String nonce = hex(nonceBytes);
            String signature;
            try {
                String message = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n"
                        + hex(MessageDigest.getInstance("MD5").digest(payload));
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                signature = hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
            } catch (GeneralSecurityException ex) {
                throw new IOException("无法计算 HMAC-SHA256 签名", ex);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "awice-java-sdk/" + VERSION)
                    .header("X-Awice-AppKey", appKey)
                    .header("X-Awice-Timestamp", ts)
                    .header("X-Awice-Nonce", nonce)
                    .header("X-Awice-Signature", signature);
            builder.method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(payload));
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> envelope;
            int code;
            try {
                Object parsed = Json.parse(response.body());
                if (!(parsed instanceof Map)) throw new IllegalArgumentException("响应必须为对象");
                envelope = object(parsed);
                Object rawCode = envelope.get("code");
                if (!(rawCode instanceof Number)) throw new IllegalArgumentException("缺少数字 code");
                code = new BigDecimal(rawCode.toString()).intValueExact();
            } catch (IllegalArgumentException | ArithmeticException ex) {
                // 不回显响应正文，避免网关错误页包含敏感信息。
                throw new TransportException("响应不是有效的开放平台 JSON（HTTP "
                        + response.statusCode() + "）", response.statusCode(), ex);
            }
            if (code == 0) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new TransportException("HTTP 状态与成功响应不一致（HTTP "
                            + response.statusCode() + "）", response.statusCode(), null);
                }
                return new ApiResponse(envelope);
            }
            if ((code == 4001 || code == 4004) && attempt < maxRetries) {
                String retryAfter = response.headers().firstValue("Retry-After").orElse("");
                Thread.sleep(retryDelayMillis(retryAfter));
                continue;
            }
            throw new ApiException(code, response.statusCode(), envelope);
        }
    }

    private static long retryDelayMillis(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return Math.multiplyExact(Math.max(0, seconds), 1000L);
        } catch (NumberFormatException | ArithmeticException ex) {
            try {
                Instant until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0, Duration.between(Instant.now(), until).toMillis());
            } catch (DateTimeParseException | ArithmeticException invalid) {
                return 1000L;
            }
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = digits[(bytes[i] & 0xff) >>> 4];
            out[i * 2 + 1] = digits[bytes[i] & 0x0f];
        }
        return new String(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    /** 保留完整响应；data 可以是 Map、List 或 null，金额按 BigDecimal 解析。 */
    public static final class ApiResponse {
        private final Map<String, Object> envelope;
        private ApiResponse(Map<String, Object> envelope) {
            this.envelope = Collections.unmodifiableMap(envelope);
        }
        public Object getData() { return envelope.get("data"); }
        public String getRequestId() { return String.valueOf(envelope.getOrDefault("request_id", "")); }
        public Map<String, Object> getEnvelope() { return envelope; }
        @Override public String toString() { return Json.stringify(envelope); }
    }

    /** code、HTTP 状态、request_id、歧义候选和批量参数 errors 都保留。 */
    public static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int code;
        public final int httpStatus;
        public final String requestId;
        public final List<?> candidates;
        public final List<?> errors;
        public final Map<String, Object> response;
        private ApiException(int code, int status, Map<String, Object> envelope) {
            super(String.valueOf(envelope.getOrDefault("msg", "请求失败")) + hint(code));
            this.code = code;
            this.httpStatus = status;
            this.requestId = String.valueOf(envelope.getOrDefault("request_id", ""));
            this.response = Collections.unmodifiableMap(envelope);
            Object data = envelope.get("data");
            Map<String, Object> details = data instanceof Map ? object(data) : Collections.emptyMap();
            Object choices = details.get("candidates");
            this.candidates = choices instanceof List ? Collections.unmodifiableList((List<?>) choices)
                    : Collections.emptyList();
            Object fields = envelope.get("errors");
            if (!(fields instanceof List) || ((List<?>) fields).isEmpty()) fields = details.get("errors");
            this.errors = fields instanceof List ? Collections.unmodifiableList((List<?>) fields)
                    : Collections.emptyList();
        }
    }

    public static final class TransportException extends IOException {
        private static final long serialVersionUID = 1L;
        public final int httpStatus;
        private TransportException(String message, int status, Throwable cause) {
            super(message, cause);
            this.httpStatus = status;
        }
    }

    private static String hint(int code) {
        switch (code) {
            case 1002: return "｜名称有歧义，请使用候选港口代码或 pol_country / pod_country 消歧";
            case 1003: return "｜外部单号内容冲突，请按原 client_order_no 查询订单，不要换单号重复下单";
            case 2002: return "｜检查 AppSecret；签名 PATH 不含 /api 和 query，body 必须与发送一致";
            case 2003: return "｜检查服务器时间，时间戳容差为 ±300 秒";
            case 2004: return "｜Nonce 重复，请为每次请求生成新随机串";
            case 2005: return "｜请在控制台应用设置添加当前出口 IP 白名单";
            case 2007: return "｜请到 https://www.5688.cn/user/profile 完成实名认证并检查账号状态";
            case 3001:
            case 3003: return "｜请到 https://www.5688.cn/user/openapi 领取免费体验包或购买套餐";
            case 3004: return "｜套餐已到期，请在控制台续费";
            case 4001:
            case 4004: return "｜限流重试已结束，请降低并发后再试";
            default: return "";
        }
    }

    /** 简单 JSON 数据转换：Map / Iterable / 数组 / 字符串 / 数字 / 布尔值 / null。 */
    public static final class Json {
        private Json() {}
        public static String stringify(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, 0);
            return out.toString();
        }
        private static void write(Object value, StringBuilder out, int depth) {
            if (depth > 128) throw new IllegalArgumentException("JSON 嵌套过深");
            if (value == null) { out.append("null"); return; }
            if (value instanceof String || value instanceof Character) {
                quote(value.toString(), out); return;
            }
            if (value instanceof Boolean) { out.append(value); return; }
            if (value instanceof Number) {
                String number = value.toString();
                if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                    throw new IllegalArgumentException("无效 JSON 数字");
                }
                out.append(number); return;
            }
            if (value instanceof Map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON key 必须为字符串");
                    if (!first) out.append(',');
                    first = false;
                    quote((String) entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out, depth + 1);
                }
                out.append('}'); return;
            }
            if (value instanceof Iterable || value.getClass().isArray()) {
                out.append('[');
                boolean first = true;
                Iterable<?> values;
                if (value instanceof Iterable) values = (Iterable<?>) value;
                else {
                    List<Object> array = new ArrayList<>();
                    for (int i = 0; i < Array.getLength(value); i++) array.add(Array.get(value, i));
                    values = array;
                }
                for (Object item : values) {
                    if (!first) out.append(',');
                    first = false;
                    write(item, out, depth + 1);
                }
                out.append(']'); return;
            }
            throw new IllegalArgumentException("不支持的 JSON 类型：" + value.getClass().getName());
        }
        private static void quote(String value, StringBuilder out) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 0x20 || Character.isSurrogate(c)) {
                            out.append("\\u");
                            String digits = Integer.toHexString(c);
                            out.append("0000", 0, 4 - digits.length()).append(digits);
                        } else out.append(c);
                }
            }
            out.append('"');
        }
        public static Object parse(String text) {
            Parser parser = new Parser(Objects.requireNonNull(text, "JSON"));
            Object value = parser.value(0);
            parser.space();
            if (parser.pos != text.length()) throw parser.invalid();
            return value;
        }
        private static final class Parser {
            private final String text;
            private int pos;
            private Parser(String text) { this.text = text; }
            private IllegalArgumentException invalid() {
                return new IllegalArgumentException("无效 JSON，位置 " + pos);
            }
            private void space() {
                while (pos < text.length() && " \n\r\t".indexOf(text.charAt(pos)) >= 0) pos++;
            }
            private boolean take(char c) {
                space();
                if (pos < text.length() && text.charAt(pos) == c) { pos++; return true; }
                return false;
            }
            private Object value(int depth) {
                if (depth > 128) throw invalid();
                space();
                if (pos >= text.length()) throw invalid();
                char c = text.charAt(pos);
                if (c == '"') return string();
                if (c == '{') {
                    pos++;
                    Map<String, Object> result = new LinkedHashMap<>();
                    if (take('}')) return result;
                    do {
                        space();
                        String key = string();
                        if (!take(':') || result.containsKey(key)) throw invalid();
                        result.put(key, value(depth + 1));
                    } while (take(','));
                    if (!take('}')) throw invalid();
                    return result;
                }
                if (c == '[') {
                    pos++;
                    List<Object> result = new ArrayList<>();
                    if (take(']')) return result;
                    do { result.add(value(depth + 1)); } while (take(','));
                    if (!take(']')) throw invalid();
                    return result;
                }
                for (String literal : new String[]{"true", "false", "null"}) {
                    if (text.startsWith(literal, pos)) {
                        pos += literal.length();
                        return "null".equals(literal) ? null : Boolean.valueOf(literal);
                    }
                }
                int start = pos;
                while (pos < text.length() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) pos++;
                String number = text.substring(start, pos);
                if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
                return new BigDecimal(number);
            }
            private String string() {
                if (pos >= text.length() || text.charAt(pos++) != '"') throw invalid();
                StringBuilder out = new StringBuilder();
                while (pos < text.length()) {
                    char c = text.charAt(pos++);
                    if (c == '"') return out.toString();
                    if (c < 0x20) throw invalid();
                    if (c != '\\') { out.append(c); continue; }
                    if (pos >= text.length()) throw invalid();
                    char escape = text.charAt(pos++);
                    switch (escape) {
                        case '"': case '\\': case '/': out.append(escape); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (pos + 4 > text.length()) throw invalid();
                            String digits = text.substring(pos, pos + 4);
                            if (!digits.matches("[0-9a-fA-F]{4}")) throw invalid();
                            out.append((char) Integer.parseInt(digits, 16));
                            pos += 4;
                            break;
                        default: throw invalid();
                    }
                }
                throw invalid();
            }
        }
    }
}
