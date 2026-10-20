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
| orderTracking(params) | 本人订单已保存轨迹，0 积分 |
| updateOrder(params) / cancelOrder(params) | 修改/作废订单，0 积分 |
| ping() | 免费连通性自检 |

参数传 `Map<String, ?>`，不需要参数时传 `Map.of()`。body 支持嵌套 Map、List、数组、字符串、数字、布尔值和 null；不直接序列化 Java Bean。可用 `AwiceClient.Json.stringify()` / `parse()` 转换这些 JSON 数据。

下单前阅读[完整参数](https://www.5688.cn/open/docs/orders)，客户须完成实名认证。`client_order_no` 在同一客户、同一订单类型中跨应用唯一；超时后先查原单号，再使用原单号和原内容重试。

## FCL 附加费：按箱与按票

`fcl()` 的 `list[].surcharges[]` 原样保留 `code`、`name`、`name_en`、`20GP`、`40GP`、`40HQ`、`currency`、`remark`，以及计费单位 `charge_unit` 和单票金额 `singleprice`。SDK 不会替你合并费用或计算订单应收。

设按箱金额 `C = 20GP单价 × 20GP箱数 + 40GP单价 × 40GP箱数 + 40HQ单价 × 40HQ箱数`：

| charge_unit | 计费方式 | 一票费用 |
| --- | --- | --- |
| `CONTAINER` | 按箱 | `C` |
| `BILL` | 按票 | `singleprice`，只计一次，忽略旧柜型金额 |
| `MIXED` | 按箱 + 按票 | `C + singleprice`，单票部分只计一次 |
| `UNKNOWN` / 缺字段 / 不认识的值 | 单位不明确 | 停止自动计算，先确认计费方式 |

示例（演示金额，不是当前报价）：

```json
[
  {"code":"DOC","name":"文件费","name_en":"Documentation Fee","20GP":450,"40GP":450,"40HQ":450,"currency":"CNY","remark":"","charge_unit":"BILL","singleprice":450},
  {"code":"THC","name":"码头费","name_en":"Terminal Handling Charge","20GP":908,"40GP":1362,"40HQ":1362,"currency":"CNY","remark":"","charge_unit":"CONTAINER","singleprice":0}
]
```

2 个 20GP 箱：DOC 为 450 CNY，THC 为 1816 CNY；这两项合计 2266 CNY。历史按票费用可能在三个柜型中重复保存同一金额，不能再乘箱数；也不能因三个柜型金额相同就推断为按票。

按 `currency` 分币种汇总。`prices` 是 USD 海运费，与附加费分列，不能直接与 CNY 等币种相加。未返回某项附加费不代表免费，也不保证包含目的港所有费用。

PHP/Python 单文件 SDK 直接读取 `result["list"]`；Java 从 `response.getData()` 中读取 `list`（Map / List，金额为 BigDecimal）。官网 PHP/Python 分目录下载包保留完整响应，从 `result["data"]["list"]` 读取。

[FCL 完整字段与限制](https://www.5688.cn/open/docs/fcl)

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

`updateOrder(params)` / `cancelOrder(params)` 为免费改单/作废接口，传 order_type 与 client_order_no 或 order_no。改单用 changes 对象，仅待确认（0）或预报（1）可改，未提交字段保留；仅待确认（0）可取消，取消后作废（7）。状态不允许返回 HTTP409 / code1004，相同重试返回 replayed=true。

### 订单详情账单

orderDetail / order_detail 新增 data.bills：先核对本人订单，再仅返回开给当前客户、已核单/已核销的应收叶子账单。其他客户账单、应付、未核/反核、已拆母单/中间节点不返回。每张账单含账单号、账单/付款状态、原币种汇总、人民币参考金额和客户费用明细。拆分金额以子单为准，母单明细仅在同订单、同付款客户且为已核应收、金额对应时展示。无可见账单返回空数组，不返回内部备注、制单人、供应商和母单标识。已有SDK详情方法自动保留新增字段。

### 本人订单轨迹

POST `/api/openapi/v1/orders/tracking` 固定0积分，参数与订单详情一致：`order_type`及`client_order_no`或`order_no`（同时传时优先外部单号）。PHP/Java使用`orderTracking(params)`，Python使用`order_tracking(**params)`。只返回当前客户订单已保存的`events`，包括时间、地点、原始描述、运单号、system/manual来源、is_forecast、has_time及recorded_at。没有数据返回空数组，不实时查询或触发抓取；预计节点不能当作已经发生。时间不推断时区，入库时间不是承运商查询时间。
