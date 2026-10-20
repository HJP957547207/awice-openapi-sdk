"""
物流巴巴开放平台 Python SDK（单文件，零依赖，Python 3.7+）

官网  https://www.5688.cn/open
文档  https://www.5688.cn/open/docs/start

用法:
    from awice_client import AwiceClient

    client = AwiceClient("你的AppKey", "你的AppSecret")
    data = client.fcl(pol="CNSHA", pod="USLAX")

只用标准库（urllib），不需要 pip install 任何东西——直接把这个文件丢进项目就能用。

这个 SDK 存在的意义不是少写几行 urllib，而是把几个最容易踩的坑挡在外面：
  1. 签名的 PATH **不含 `/api` 前缀** —— 请求打的是 /api/openapi/v1/...，
     签名只能用 /openapi/v1/...，自己实现时最常错的就是这里
  2. body 必须用**同一个 JSON 字符串**算 MD5 和发送，重新 dumps 一次就可能对不上
  3. QPS 超限(4001) 自动退避重试，不用自己写重试逻辑
  4. 常见业务错误给出人话提示，比如「积分不足」会告诉你去控制台领免费额度
"""

import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional

__version__ = "1.1.0"

DEFAULT_BASE_URL = "https://www.5688.cn/api"

# 服务端错误码 → 该怎么办
#
# 线上日志显示 70% 的失败其实是额度问题而不是代码问题，最典型的是注册了却没点
# 「领取免费体验包」，然后代码写得再对也一路被拒。与其让人对着「配额不足」发呆，
# 不如直接告诉他去哪点一下。
_HINTS: Dict[int, str] = {
    2001: "AppKey 不存在或应用已禁用，检查是否用错了环境的密钥",
    2002: "签名校验失败。九成是这三种：签名的 PATH 带上了 /api 前缀或 query string；"
          "body 算 MD5 和实际发送的不是同一个字符串；AppSecret 复制时多了空格",
    2003: "时间戳超出 ±300 秒，检查服务器时间是否与 NTP 同步",
    2004: "Nonce 重复，同一个随机串 10 分钟内只能用一次",
    2005: "来源 IP 不在白名单，去控制台应用设置里添加当前出口 IP",
    3001: "积分不足。如果还没领过免费体验包，去 https://www.5688.cn/user/openapi 点「领取」即可继续调用",
    3003: "当前没有有效订阅，去控制台领取免费体验包或购买套餐",
    3004: "套餐已到期，续费后继续使用",
    4001: "QPS 超限，降低并发或升级套餐（SDK 已自动重试仍未成功）",
    4004: "快递接口并发超限，等上一个请求返回再发起下一个",
    1001: "参数无效或港口/机场名无法识别，可先用 ports()/airports() 查正确代码",
    1002: "名称有歧义匹配到多个，用 pol_country / pod_country 传 2 位国家码消歧，"
          "候选见异常的 candidates 属性",
    5002: "上游数据源暂时不可用，稍后重试",
}


class AwiceApiError(Exception):
    """接口返回业务错误（code != 0）"""

    def __init__(self, code: int, msg: str, request_id: str = "", candidates: Optional[List[Any]] = None):
        hint = _HINTS.get(code, "")
        super().__init__(f"{msg}｜{hint}" if hint else msg)
        self.code = code
        self.msg = msg
        #: 请求 ID，报障时提供给客服能直接定位
        self.request_id = request_id
        #: 多匹配(1002)时的候选列表
        self.candidates = candidates or []


class AwiceTransportError(Exception):
    """网络层错误（超时、DNS、连不上）"""


class AwiceClient:
    def __init__(
        self,
        app_key: str,
        app_secret: str,
        base_url: str = DEFAULT_BASE_URL,
        timeout: int = 60,
        max_retries: int = 3,
    ):
        """
        :param app_key:    控制台创建应用后获得
        :param app_secret: 同上；只在本地参与签名计算，不会随请求发出
        :param timeout:    读取超时（秒）。快递接口要实时问 4 家上游，
                           未命中缓存时 30 秒以上正常，别设太小
        :param max_retries: 撞到 QPS 限制(4001) 时的最大重试次数，0 = 关闭
        """
        if not app_key or not app_secret:
            raise ValueError(
                "app_key / app_secret 不能为空，"
                "请到 https://www.5688.cn/user/openapi/apps 创建应用获取"
            )
        self.app_key = app_key
        self.app_secret = app_secret
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries

    # ───────────────────────── 运价接口 ─────────────────────────

    def fcl(self, **params: Any) -> Any:
        """海运整柜 FCL 运价

        list[].surcharges 保留 charge_unit / singleprice；按票只计一次，计费规则见 README。

        :keyword pol: 起运港（必填）。支持 UN/LOCODE、中文名、英文名，如 CNSHA / 上海 / shanghai
        :keyword pod: 目的港（必填）
        :keyword carrier: 船公司（可选）
        :keyword pol_country / pod_country: 2 位国家码，用于同名港口消歧（如「奥克兰」美国和新西兰都有）
        :keyword page / limit: 分页，limit 最大 50
        """
        return self.post("/openapi/v1/freight/fcl/search", params)

    def lcl(self, **params: Any) -> Any:
        """海运拼箱 LCL 运价。参数同 FCL，另可传 warehouse 仓库代码"""
        return self.post("/openapi/v1/freight/lcl/search", params)

    def air(self, **params: Any) -> Any:
        """国际空运运价。pol/pod 为机场（IATA 三字码/中文/英文），可选 airline"""
        return self.post("/openapi/v1/freight/air/search", params)

    def express(self, **params: Any) -> Any:
        """国际快递 / 专线运价

        ⚠️ 会实时询问多家上游，未命中缓存时耗时 30 秒以上属正常，
           调用前确认 timeout 留够（默认 60 秒）。
        """
        return self.post("/openapi/v1/freight/express/search", params)

    def fba(self, **params: Any) -> Any:
        """FBA 头程运价。按亚马逊仓库代码（如 ONT8、FTW1）查询"""
        return self.post("/openapi/v1/freight/fba/search", params)

    # ───────────────────────── 基础数据字典 ─────────────────────────

    def schedule(self, **params: Any) -> Any:
        """港口对船期；可传 carrier、etd_start/etd_end、page/limit。"""
        return self.post("/openapi/v1/schedule/search", params)

    def schedule_detail(self, schedule_id: str) -> Any:
        """凭本应用船期搜索返回的 schedule_id 获取该航次详情。"""
        return self.post("/openapi/v1/schedule/detail", {"schedule_id": schedule_id})

    def create_ocean_order(self, **params: Any) -> Any:
        """免费下单；client_order_no 必填，重试保留原单号和内容。"""
        return self.post("/openapi/v1/orders/ocean/create", params)

    def create_air_order(self, **params: Any) -> Any:
        """免费下单；client_order_no 必填，重试保留原单号和内容。"""
        return self.post("/openapi/v1/orders/air/create", params)

    def create_express_order(self, **params: Any) -> Any:
        """免费下单；client_order_no 必填，重试保留原单号和内容。"""
        return self.post("/openapi/v1/orders/express/create", params)

    def create_fba_order(self, **params: Any) -> Any:
        """免费下单；client_order_no 必填，重试保留原单号和内容。"""
        return self.post("/openapi/v1/orders/fba/create", params)

    def order_detail(self, **params: Any) -> Any:
        return self.post("/openapi/v1/orders/detail", params)

    def order_tracking(self, **params: Any) -> Any:
        """Own-order saved tracking events; same selectors as order_detail, 0 credits."""
        return self.post("/openapi/v1/orders/tracking", params)

    def update_order(self, **params: Any) -> Any:
        """Modify own pending/forecast order; submit changed fields in changes."""
        return self.post("/openapi/v1/orders/update", params)

    def cancel_order(self, **params: Any) -> Any:
        """Void own pending order; repeating a void request is safe."""
        return self.post("/openapi/v1/orders/cancel", params)

    def order_options(self, **params: Any) -> Any:
        return self.get("/openapi/v1/orders/options", params)

    def ports(self, **query: Any) -> Any:
        """港口字典。keyword 模糊搜索，返回 code / 中英文名 / 国家"""
        return self.get("/openapi/v1/dict/port", query)

    def airports(self, **query: Any) -> Any:
        """机场字典"""
        return self.get("/openapi/v1/dict/airport", query)

    def carriers(self, **query: Any) -> Any:
        """船公司字典"""
        return self.get("/openapi/v1/dict/carrier", query)

    def airlines(self, **query: Any) -> Any:
        """航空公司字典"""
        return self.get("/openapi/v1/dict/airline", query)

    def countries(self, **query: Any) -> Any:
        """国家字典"""
        return self.get("/openapi/v1/dict/country", query)

    def popular(self, **query: Any) -> Any:
        """常用数据集（港口/机场各 500 条 + 船司/航司/国家全量）

        做下拉选择器就拉这个，本地缓存起来，别高频打搜索接口。
        """
        return self.get("/openapi/v1/dict/popular", query)

    def ping(self) -> Any:
        """连通性自检，不消耗积分。对接第一步先调它确认签名正确"""
        return self.get("/openapi/v1/ping")

    # ───────────────────────── 底层请求 ─────────────────────────

    def post(self, path: str, body: Optional[Dict[str, Any]] = None) -> Any:
        """:param path: 以 / 开头、**不含** /api 前缀的接口路径"""
        # 关键：先定下这个 JSON 字符串，签名和发送必须用同一份。
        # 若签名时 dumps 一次、发送时再 dumps 一次，只要分隔符或转义有差异，
        # 服务端算出的 MD5(BODY) 就对不上，直接 2002 签名失败。
        payload = json.dumps(body or {}, ensure_ascii=False, separators=(",", ":"))
        return self._request("POST", path, payload, None)

    def get(self, path: str, query: Optional[Dict[str, Any]] = None) -> Any:
        """:param query: URL 查询参数；注意签名时**不含** query string"""
        return self._request("GET", path, "", query)

    def _request(self, method: str, path: str, payload: str, query: Optional[Dict[str, Any]]) -> Any:
        path = "/" + path.lstrip("/")
        attempt = 0

        while True:
            attempt += 1
            status, raw, headers = self._send(method, path, payload, query)

            try:
                result = json.loads(raw)
            except ValueError:
                raise AwiceTransportError(
                    f"响应不是合法 JSON（HTTP {status}）：{raw[:200]}"
                )

            code = int(result.get("code", -1))
            if code == 0:
                return result.get("data", {})

            # 4001 QPS 超限 / 4004 并发超限：按服务端给的 Retry-After 退避后重试。
            # 批量跑数据时这两个几乎必然会撞上，自动退避比让调用方自己写循环靠谱。
            if code in (4001, 4004) and attempt <= self.max_retries:
                wait = headers.get("retry-after")
                try:
                    delay = int(wait) if wait else 1
                except (TypeError, ValueError):
                    delay = 1
                time.sleep(max(1, min(delay, 10)))
                continue

            data = result.get("data") or {}
            raise AwiceApiError(
                code,
                str(result.get("msg", "")),
                str(result.get("request_id", "")),
                data.get("candidates") if isinstance(data, dict) else None,
            )

    def _send(self, method: str, path: str, payload: str, query: Optional[Dict[str, Any]]):
        timestamp = str(int(time.time()))
        nonce = os.urandom(8).hex()
        body_md5 = hashlib.md5(payload.encode("utf-8")).hexdigest()

        # ⚠️ 签名用的 PATH 不含 /api 前缀、不含 query string。
        #    请求 URL 是 base_url(.../api) + path，两者刻意不同，这是最高频的踩坑点。
        message = f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_md5}"
        signature = hmac.new(
            self.app_secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256
        ).hexdigest()

        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(
                {k: v for k, v in query.items() if v is not None}
            )

        req = urllib.request.Request(
            url,
            data=payload.encode("utf-8") if method.upper() != "GET" else None,
            method=method.upper(),
            headers={
                "Content-Type": "application/json",
                "X-Awice-AppKey": self.app_key,
                "X-Awice-Timestamp": timestamp,
                "X-Awice-Nonce": nonce,
                "X-Awice-Signature": signature,
                # 带上 UA：某些网关会拦截空 User-Agent 的请求
                "User-Agent": f"awice-python-sdk/{__version__}",
            },
        )

        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                # header 名统一转小写：HTTP 头大小写不敏感，但下面按 "retry-after" 取值，
                # 两条返回路径必须用同一种键形，否则退避时间会静默丢失
                return (
                    resp.status,
                    resp.read().decode("utf-8"),
                    {k.lower(): v for k, v in resp.headers.items()},
                )
        except urllib.error.HTTPError as e:
            # 4xx/5xx 的响应体里同样是标准 JSON，要读出来交给上层判业务码，
            # 不能当成传输错误直接抛掉——否则 4001 的 Retry-After 和 2002 的
            # 具体原因全都丢了，客户只看得到一个「HTTP 401」。
            body = e.read().decode("utf-8", errors="replace")
            return e.code, body, {k.lower(): v for k, v in dict(e.headers or {}).items()}
        except urllib.error.URLError as e:
            raise AwiceTransportError(f"请求失败：{e.reason}")
        except TimeoutError:
            raise AwiceTransportError(f"请求超时（{self.timeout} 秒）")
