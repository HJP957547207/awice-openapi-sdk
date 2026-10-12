# Java SDK（JDK 11+）

[English](./README.en.md) | 简体中文

单文件、无第三方依赖。使用 JDK 自带 HttpClient、HMAC-SHA256 与 JSON 数据转换，按 Java 11 API 编写，适用于 JDK 11 及以上版本（包括 17、21）。Java 11、17、21 已通过本地签名、HTTP、JSON、限流及异常测试。不使用预览特性；无需 Maven/Gradle 依赖，也不要求 Spring Boot。

- [GitHub 主仓库](https://github.com/HJP957547207/awice-openapi-sdk/tree/main/java)
- [Gitee 国内镜像](https://gitee.com/baige95/awice-openapi-sdk/tree/main/java)
- [接口文档](https://www.5688.cn/open/docs/start)

## 快速开始

复制 `AwiceClient.java` 到项目；已有 package 的项目，在文件顶部添加自己的 package 声明。也可将本目录两个 Java 文件放在同一目录运行：

```bash
javac --release 11 -encoding UTF-8 AwiceClient.java Example.java
```

在 PowerShell 设置凭据并运行免费连通性自检：

```powershell
$env:AWICE_APP_KEY = '你的AppKey'
$env:AWICE_APP_SECRET = '你的AppSecret'
java Example
```

Linux/macOS 使用 `export AWICE_APP_KEY='你的AppKey'`、`export AWICE_APP_SECRET='你的AppSecret'`。凭据在[控制台](https://www.5688.cn/user/openapi/apps)创建应用后获取，不要提交到代码仓库。

需要查价时运行 `java Example --fcl`，按实际接口计费。例子默认只 ping，不会提交真实订单。

```java
AwiceClient client = new AwiceClient(key, secret);
AwiceClient.ApiResponse result = client.fcl(
    Map.of("pol", "CNSHA", "pod", "USLAX", "limit", 10));
System.out.println(AwiceClient.Json.stringify(result.getData()));
System.out.println(result.getEnvelope().get("credit_used"));
```

## 接口与返回值

全部方法返回 `ApiResponse`，`getData()` 获取业务数据，`getEnvelope()` 获取完整响应（含 code/data/credit_used），`getRequestId()` 用于报障。JSON 对象为 `Map<String,Object>`，数组为 `List<Object>`，数字为 `BigDecimal`，空值为 null；船期详情查无时 data 可能是空数组，不要固定强转为 Map。

| 方法 | 用途 |
| --- | --- |
| fcl(params) / lcl(params) / air(params) / express(params) / fba(params) | 五类运价 |
| schedule(params) / scheduleDetail(scheduleId) | 船期搜索与详情 |
| ports(query) / airports(query) / carriers(query) / airlines(query) / countries(query) / popular(query) | 基础数据 |
| createOceanOrder(payload) / createAirOrder(payload) / createExpressOrder(payload) / createFbaOrder(payload) | 四类真实下单，0 积分 |
| orderOptions(query) / orderDetail(params) | 下单选项、本人订单详情，0 积分 |
| ping() | 免费连通性自检 |

参数传 `Map<String, ?>`，不需要参数时传 `Map.of()`。body 支持嵌套 Map、List、数组、字符串、数字、布尔值和 null；不直接序列化 Java Bean。可用 `AwiceClient.Json.stringify()` / `parse()` 转换这些 JSON 数据。

下单前阅读[完整参数](https://www.5688.cn/open/docs/orders)，客户须完成实名认证。`client_order_no` 在同一客户、同一订单类型中跨应用唯一；超时后先查原单号，再使用原单号和原内容重试。

## 超时和异常

```java
AwiceClient client = new AwiceClient(key, secret, AwiceClient.DEFAULT_BASE_URL,
    java.time.Duration.ofSeconds(90), 3);
```

默认单次请求超时 60 秒、连接超时 5 秒、限流最多重试 3 次。快递查价可能超过 30 秒。只有明确的业务码 4001/4004 才按服务端 `Retry-After` 重试（支持秒数或 HTTP 日期）；无有效头时等待 1 秒。每次重试生成新 nonce 和时间戳，body 不变。网络超时/断连和其他业务错误不自动重试。

`ApiException` 保留 `code`、`httpStatus`、`requestId`、`candidates`、`errors` 和完整 `response`，参数错误不会丢失多字段明细。`IOException` 表示网络或响应格式错误；`InterruptedException` 由调用方处理，应恢复线程中断状态或向上抛出。

签名路径不含 `/api` 前缀或 query；`baseUrl` 默认 `https://www.5688.cn/api`。客户端可复用，避免每次请求重新创建。

源码按 MIT 协议发布。本次提供源码，未发布 Maven Central 包。

在仓库根目录运行离线验证（仅临时回环 mock，自动关闭监听）：

```bash
javac --release 11 -encoding UTF-8 -d .java-test java/AwiceClient.java tests/JavaSdkTest.java
java -cp .java-test JavaSdkTest
```
