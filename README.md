# 物流巴巴开放平台 SDK

[English](./README.en.md) | 简体中文

> 代码同时托管在 [GitHub](https://github.com/HJP957547207/awice-openapi-sdk)（主仓库）和 [Gitee](https://gitee.com/baige95/awice-openapi-sdk)（国内镜像），两边内容保持同步。国内访问 GitHub 不畅时用 Gitee 即可。

国际物流运价查询 API 的官方 SDK。**海运整柜（FCL）、海运拼箱（LCL）、国际空运、国际快递、FBA 头程**五类实时运价、船期查询与四类物流下单，外加港口、机场、船公司、航空公司、国家五类基础数据字典，一次对接接进你自己的 TMS、ERP 或官网询价页。

- 官网：<https://www.5688.cn/open>
- 文档：<https://www.5688.cn/open/docs/start>
- 控制台：<https://www.5688.cn/user/openapi>
- [Postman 集合（含 9 个订单接口）](./postman/awice-openapi.postman_collection.json)：导入后填写自己的应用凭据，创建/修改/作废会操作真实订单。
- 当前源码版本：**1.1.0**，见[更新记录](./CHANGELOG.md)。

## 特点

- **单文件、零依赖**。PHP 版只要 curl 扩展（PHP 7.1+），Python 版只用标准库（Python 3.7+），Java 版使用 JDK 标准库（JDK 11+，兼容 17、21）。复制一个文件进项目就能用，不需要 composer、pip 或 Maven 依赖。
- **签名自动处理**。HMAC-SHA256 签名里最容易错的几处（PATH 不含 `/api` 前缀、body 要用同一个 JSON 串算 MD5）已经封装好。
- **限流自动退避**。撞到 QPS 上限时按服务端返回的 `Retry-After` 自动重试，批量跑数据不用自己写重试循环。
- **错误提示说人话**。比如「积分不足」会直接告诉你去控制台哪里领免费额度，而不是让你对着一个错误码发呆。

## 快速开始

先到[控制台](https://www.5688.cn/user/openapi/apps)创建应用拿到 AppKey / AppSecret。需要积分的查价接口可先领取免费体验包；ping 和下单接口不要求积分余额，下单须完成客户实名认证。

### PHP

```php
require 'AwiceClient.php';

$client = new \Awice\OpenApi\AwiceClient('你的AppKey', '你的AppSecret');

// 先验证签名通了（ping 不消耗积分）
$client->ping();

// 查上海到洛杉矶的整柜运价
$res = $client->fcl([
    'pol'   => 'CNSHA',   // 起运港：UN/LOCODE、中文名、英文名都认
    'pod'   => '洛杉矶',   // 目的港
    'limit' => 10,
]);

foreach ($res['list'] as $rate) {
    $p = $rate['prices'];
    echo "{$rate['carrier']['name']}  20GP: {$p['20GP']}  40HQ: {$p['40HQ']} {$p['currency']}"
        . "  航程 {$rate['transit']['days']} 天  有效期至 {$rate['valid_until']}\n";
}
```

### Python

```python
from awice_client import AwiceClient

client = AwiceClient("你的AppKey", "你的AppSecret")

client.ping()

res = client.fcl(pol="CNSHA", pod="洛杉矶", limit=10)
for rate in res["list"]:
    p = rate["prices"]
    print(rate["carrier"]["name"], p["20GP"], p["40HQ"], p["currency"],
          f'航程 {rate["transit"]["days"]} 天', rate["valid_until"])
```

### Java（JDK 11+）

复制 [java/AwiceClient.java](./java/AwiceClient.java)，无需第三方依赖。

```java
AwiceClient client = new AwiceClient(key, secret);
AwiceClient.ApiResponse res = client.fcl(
    java.util.Map.of("pol", "CNSHA", "pod", "USLAX", "limit", 10));
System.out.println(AwiceClient.Json.stringify(res.getData()));
```

[Java 运行示例、版本与异常说明](./java/README.md)。Java 返回 `ApiResponse`（保留完整响应）；PHP/Python 单文件客户端直接返回 `data`。

## 返回长什么样

PHP/Python 单文件客户端的 `fcl()` 直接返回 `data`，Java 使用 `fcl(...).getData()` 获取同一结构（真实响应，非杜撰）：

```json
{
  "resolved": {
    "pol": { "input": "CNSHA", "code": "CNSHA", "name": "上海", "name_en": "shanghai" },
    "pod": { "input": "洛杉矶", "code": "USLSA", "name": "洛杉矶", "name_en": "los angeles,ca" }
  },
  "total": 128, "page": 1, "limit": 10,
  "list": [
    {
      "id": "54698",
      "pol": { "code": "CNSHA", "name": "上海", "name_en": "shanghai" },
      "pod": { "code": "USLSA", "name": "洛杉矶", "name_en": "los angeles,ca" },
      "carrier": { "code": "CMA", "name": "达飞轮船" },
      "transit": { "is_direct": true, "days": 16, "port": "", "port_en": "" },
      "schedule": {
        "cutoff_date": "", "departure_date": "", "arrival_date": "",
        "vessel_name": "", "voyage": "", "service_code": "", "is_real": false
      },
      "prices": { "20GP": 1880, "40GP": 2110, "40HQ": 2110, "currency": "USD" },
      "surcharges": [],
      "valid_until": "2026-12-31"
    }
  ]
}
```

`resolved` 会回显你传的原值和解析结果——传中文名时用它确认系统认成了哪个港口。
`schedule.is_real` 为 `true` 表示是船公司真实船期，`false` 表示该条报价未关联具体航次。

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

## 可用接口

可用接口如下——**没有的不会写在这里**。

| 方法 | 接口 | 说明 |
|---|---|---|
| `fcl()` | `POST /openapi/v1/freight/fcl/search` | 海运整柜运价 |
| `lcl()` | `POST /openapi/v1/freight/lcl/search` | 海运拼箱运价 |
| `air()` | `POST /openapi/v1/freight/air/search` | 国际空运运价 |
| `express()` | `POST /openapi/v1/freight/express/search` | 国际快递 / 专线运价 |
| `fba()` | `POST /openapi/v1/freight/fba/search` | FBA 头程运价 |
| `schedule()` | `POST /openapi/v1/schedule/search` | 船期查询（港口对、船公司、ETD 日期范围） |
| `schedule_detail()` / `scheduleDetail()` | `POST /openapi/v1/schedule/detail` | 同一应用的航次标识获取详情 |
| `ports()` | `GET /openapi/v1/dict/port` | 港口字典 |
| `airports()` | `GET /openapi/v1/dict/airport` | 机场字典 |
| `carriers()` | `GET /openapi/v1/dict/carrier` | 船公司字典 |
| `airlines()` | `GET /openapi/v1/dict/airline` | 航空公司字典 |
| `countries()` | `GET /openapi/v1/dict/country` | 国家字典 |
| `popular()` | `GET /openapi/v1/dict/popular` | 常用数据集（做下拉框用） |
| `ping()` | `ANY /openapi/v1/ping` | 连通性自检，不计费 |

> 目前**没有**舱位和任意单号轨迹接口；四类下单与本人订单详情见下文。有这类需求可以到官网留言，会作为排期参考。

## 港口怎么传

`pol` / `pod` 三种写法都认，按可靠性排序：

1. **UN/LOCODE**（推荐）：`CNSHA`、`USLAX`、`MYPKG`
2. **中文名**：`上海`、`钦州港`、`勒阿弗尔`
3. **英文名**：`shanghai`、`port klang`

同名港口会返回 `1002`，比如「奥克兰」美国（`USOAK`）和新西兰（`NZAUC`）都有。两种解法：

```php
// 方法一：加国家码消歧
$client->fcl(['pol' => 'CNSHA', 'pod' => '奥克兰', 'pod_country' => 'US']);

// 方法二：先查字典拿准确代码
$ports = $client->ports(['keyword' => '奥克兰']);
```

SDK 会把候选放在异常的 `candidates` 属性里，可以直接拿来让用户选。

## 两个最容易踩的坑

如果你不用这个 SDK 而是自己实现签名，这两点务必注意：

**1. 签名的 PATH 不含 `/api` 前缀**

```
请求 URL:  https://www.5688.cn/api/openapi/v1/freight/fcl/search
签名 PATH:                    /openapi/v1/freight/fcl/search
```

也不含 query string。这是 `2002 签名校验失败` 最常见的原因。

**2. body 要用同一个 JSON 字符串算 MD5 和发送**

签名时序列化一次、发送时再序列化一次，只要字段顺序或空格有任何差异，服务端算出的 `MD5(BODY)` 就对不上。

## 错误码

| 码 | 含义 | 怎么办 |
|---|---|---|
| `0` | 成功 | — |
| `1001` | 参数无效 / 港口名无法识别 | 先用 `ports()` 查正确代码 |
| `1002` | 名称有歧义 | 加 `pol_country` / `pod_country`，或用代码 |
| `2002` | 签名校验失败 | 见上面「两个坑」 |
| `2003` | 时间戳超出 ±300 秒 | 检查服务器时间是否与 NTP 同步 |
| `2004` | Nonce 重复 | 同一随机串 10 分钟内只能用一次 |
| `2005` | IP 不在白名单 | 控制台添加当前出口 IP |
| `3001` | 积分不足 | 没领过就去控制台领免费体验包 |
| `3003` | 无有效订阅 | 同上 |
| `3004` | 套餐已到期 | 续费 |
| `4001` | QPS 超限 | SDK 会自动退避重试；仍失败就降并发或升套餐 |
| `4004` | 快递并发超限 | 等上一个请求返回再发下一个 |
| `5002` | 上游暂时不可用 | 稍后重试 |

业务失败（`code != 0`）**不扣积分**。

## 超时设置

快递接口要实时询问多家上游，未命中缓存时 **30 秒以上是正常的**。SDK 默认读取超时 60 秒，如果你自己改小了或者中间有网关，记得两边都留够：

```php
$client->setTimeout(90, 5);   // 读取 90 秒，连接 5 秒
```

```python
client = AwiceClient(key, secret, timeout=90)
```

## 计费

买套餐得积分，每次调用按接口类型扣分，用完或到期续。运价类接口单价不同（海运最低、快递最高，因为要实时问上游），字典类有免费额度。实时价格以[控制台](https://www.5688.cn/user/openapi/plans)为准——这里不写死数字，免得调价后对不上。

## License

MIT

## 船期查询与详情

有结果的搜索默认每次 1 积分、最多 50 条，空结果免费。每条返回 `schedule_id` 和 `terminals`。详情默认不重复收费，后台可调整；只接受同一应用搜索返回的标识，不能跨应用使用。数据字段缺失为 null，分段/挂靠港缺失为 []；船期不代表舱位。详情查无 data=[]。

```python
page = client.schedule(pol="CNSZX", pod="USLAX", carrier="COSCO", limit=50)
rows = page.get("data", [])
if rows:
    detail = client.schedule_detail(rows[0]["schedule_id"])
    print(detail.get("legs", []) if detail else [])
```

```php
$page = $client->schedule(["pol" => "CNSZX", "pod" => "USLAX", "limit" => 50]);
if (!empty($page['data'])) {
    $detail = $client->scheduleDetail($page['data'][0]['schedule_id']);
}
```

接口：POST /openapi/v1/schedule/search、POST /openapi/v1/schedule/detail。
文档：https://www.5688.cn/open/docs/schedule#detail

## 四类物流下单（0 积分）

沿用 /api/openapi/v1/，AppKey 绑定的官网客户须完成实名认证。
四类下单、下单选项、本人订单详情固定免费，零积分余额可用，仍签名鉴权并限速。
提交会生成真实订单，进入与官网相同的后台业务流程。

| PHP 方法 | Python 方法 | Java 方法 | 相对路径 |
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

先取下单选项再填 payload。海运港口使用字典内部 code，空运使用机场 IATA 码。
联系人可用自己的联系人 ID，或内嵌 company/contact/tel/address 对象。
[完整参数与四类示例](https://www.5688.cn/open/docs/orders)。

client_order_no 为必填外部单号，在同一客户、同一订单类型内唯一，跨应用共用。
原单号、原有效参数重试返回原订单（replayed=true）；原单号改变内容返回 HTTP409 / code1003。
超时后先按原单号查订单，再用原内容重试，避免换单号造成重复下单。SDK 每次请求自动生成新 nonce 和时间戳。

PHP/Python 单文件 SDK 返回 data；Java 返回 ApiResponse，可用 getData() / getEnvelope() 获取业务数据或完整响应；PHP/Python 下载包返回完整 code/data/credit_used 响应。
本次更新下载源码，不代表已向 Composer / PyPI 发布新版本。

### 修改订单与取消（作废）

两个接口固定免费（0积分），传 order_type 及 client_order_no 或 order_no。update 的 changes 为非空对象：未提交字段/明细保留，提交的对象/列表整体替换。仅待确认（0）/预报（1）订单可修改；仅待确认（0）订单可自助取消，取消后为作废（7）。重复相同修改或取消返回 replayed=true，不重复写操作日志或创建联系人。状态不允许时返回 HTTP409 / code1004。改单后重试原创建请求仅返回当前订单，不覆盖修改结果。

### 订单详情账单

orderDetail / order_detail 新增 data.bills：先核对本人订单，再仅返回开给当前客户、已核单/已核销的应收叶子账单。其他客户账单、应付、未核/反核、已拆母单/中间节点不返回。每张账单含账单号、账单/付款状态、原币种汇总、人民币参考金额和客户费用明细。拆分金额以子单为准，母单明细仅在同订单、同付款客户且为已核应收、金额对应时展示。无可见账单返回空数组，不返回内部备注、制单人、供应商和母单标识。已有SDK详情方法自动保留新增字段。

### 本人订单轨迹

POST `/api/openapi/v1/orders/tracking` 固定0积分，参数与订单详情一致：`order_type`及`client_order_no`或`order_no`（同时传时优先外部单号）。PHP/Java使用`orderTracking(params)`，Python使用`order_tracking(**params)`。只返回当前客户订单已保存的`events`，包括时间、地点、原始描述、运单号、system/manual来源、is_forecast、has_time及recorded_at。没有数据返回空数组，不实时查询或触发抓取；预计节点不能当作已经发生。时间不推断时区，入库时间不是承运商查询时间。
