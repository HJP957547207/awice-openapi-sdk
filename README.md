# 物流巴巴开放平台 SDK

[English](./README.en.md) | 简体中文

> 代码同时托管在 [GitHub](https://github.com/HJP957547207/awice-openapi-sdk)（主仓库）和 [Gitee](https://gitee.com/baige95/awice-openapi-sdk)（国内镜像），两边内容保持同步。国内访问 GitHub 不畅时用 Gitee 即可。

国际物流运价查询 API 的官方 SDK。**海运整柜（FCL）、海运拼箱（LCL）、国际空运、国际快递、FBA 头程**五类实时运价，外加港口、机场、船公司、航空公司、国家五类基础数据字典，一次对接接进你自己的 TMS、ERP 或官网询价页。

- 官网：<https://www.5688.cn/open>
- 文档：<https://www.5688.cn/open/docs/start>
- 控制台：<https://www.5688.cn/user/openapi>

## 特点

- **单文件、零依赖**。PHP 版只要 curl 扩展（PHP 7.1+），Python 版只用标准库（Python 3.7+）。复制一个文件进项目就能用，不需要 composer 或 pip。
- **签名自动处理**。HMAC-SHA256 签名里最容易错的几处（PATH 不含 `/api` 前缀、body 要用同一个 JSON 串算 MD5）已经封装好。
- **限流自动退避**。撞到 QPS 上限时按服务端返回的 `Retry-After` 自动重试，批量跑数据不用自己写重试循环。
- **错误提示说人话**。比如「积分不足」会直接告诉你去控制台哪里领免费额度，而不是让你对着一个错误码发呆。

## 快速开始

先到[控制台](https://www.5688.cn/user/openapi/apps)创建应用拿到 AppKey / AppSecret，**并在控制台点一下「领取免费体验包」**——没领的话调用会直接被拒（这是新用户最常见的卡点）。

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

## 返回长什么样

`fcl()` 返回的是剥掉 `code`/`msg` 外层之后的 `data`，结构如下（真实响应，非杜撰）：

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

## 可用接口

对外一共 10 个接口，下表就是全部——**没有的不会写在这里**。

| 方法 | 接口 | 说明 |
|---|---|---|
| `fcl()` | `POST /openapi/v1/freight/fcl/search` | 海运整柜运价 |
| `lcl()` | `POST /openapi/v1/freight/lcl/search` | 海运拼箱运价 |
| `air()` | `POST /openapi/v1/freight/air/search` | 国际空运运价 |
| `express()` | `POST /openapi/v1/freight/express/search` | 国际快递 / 专线运价 |
| `fba()` | `POST /openapi/v1/freight/fba/search` | FBA 头程运价 |
| `ports()` | `GET /openapi/v1/dict/port` | 港口字典 |
| `airports()` | `GET /openapi/v1/dict/airport` | 机场字典 |
| `carriers()` | `GET /openapi/v1/dict/carrier` | 船公司字典 |
| `airlines()` | `GET /openapi/v1/dict/airline` | 航空公司字典 |
| `countries()` | `GET /openapi/v1/dict/country` | 国家字典 |
| `popular()` | `GET /openapi/v1/dict/popular` | 常用数据集（做下拉框用） |
| `ping()` | `ANY /openapi/v1/ping` | 连通性自检，不计费 |

> 目前**没有**舱位、船期、货物轨迹、下单/订单/账单类接口。有这类需求可以到官网留言，会作为排期参考。

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
