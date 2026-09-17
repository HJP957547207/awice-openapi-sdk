# -*- coding: utf-8 -*-
"""
物流巴巴开放平台 Python SDK 示例

跑之前设好环境变量（或直接改下面两行），然后：python example.py

    set AWICE_APP_KEY=你的AppKey
    set AWICE_APP_SECRET=你的AppSecret
"""

import os

from awice_client import AwiceApiError, AwiceClient, AwiceTransportError

APP_KEY = os.getenv("AWICE_APP_KEY", "你的AppKey")
APP_SECRET = os.getenv("AWICE_APP_SECRET", "你的AppSecret")

client = AwiceClient(APP_KEY, APP_SECRET)

# ── 1. 先确认签名通了 ─────────────────────────────────────────
# ping 不消耗积分，对接第一步永远先跑它：通了说明密钥和签名都没问题，
# 后面再出错就一定是业务参数的事，能省掉大量瞎猜。
try:
    pong = client.ping()
    print(f"[ping] 连通正常，应用：{pong['app']['app_name']}\n")
except AwiceApiError as e:
    raise SystemExit(f"[ping] 失败（{e.code}）：{e}")
except AwiceTransportError as e:
    raise SystemExit(f"[ping] 网络不通：{e}")

# ── 2. 查一条整柜运价 ─────────────────────────────────────────
try:
    res = client.fcl(pol="CNSHA", pod="洛杉矶", limit=5)
    print(f"[fcl] 上海 → 洛杉矶，共 {res['total']} 条：")
    for rate in res["list"]:
        p = rate["prices"]
        print(
            f"  {rate['carrier']['name']:<10} "
            f"20GP {p['20GP']:<7} 40HQ {p['40HQ']:<7} {p['currency']}  "
            f"航程 {rate['transit']['days']} 天  有效期至 {rate['valid_until']}"
        )
    print()
except AwiceApiError as e:
    print(f"[fcl] 失败（{e.code}）：{e}\n")

# ── 3. 处理同名港口歧义 ───────────────────────────────────────
# 「奥克兰」美国(USOAK)和新西兰(NZAUC)都有，服务端返回 1002 并给出候选，
# 拿 candidates 让用户选，或者直接用 pod_country 指定国家。
try:
    client.fcl(pol="CNSHA", pod="奥克兰", limit=3)
except AwiceApiError as e:
    if e.code == 1002:
        print("[歧义] 候选港口：")
        for c in e.candidates:
            print(f"  {c['code']}  {c['name']} ({c['country_code']})")
        res = client.fcl(pol="CNSHA", pod="奥克兰", pod_country="US", limit=3)
        print(f"  指定 US 后拿到 {res['total']} 条报价\n")

# ── 4. 批量跑多条航线 ─────────────────────────────────────────
# 撞到 QPS 上限(4001) 时 SDK 会自动按 Retry-After 退避重试，这里不用写重试逻辑。
routes = [("CNSHA", "USLAX"), ("CNYTN", "DEHAM"), ("CNNGB", "NLRTM")]
print("[批量] 三条航线最低价：")
for pol, pod in routes:
    try:
        res = client.fcl(pol=pol, pod=pod, limit=50)
        prices = [r["prices"].get("40HQ") for r in res["list"] if r["prices"].get("40HQ")]
        print(f"  {pol} → {pod}：40HQ 最低 {min(prices) if prices else '无报价'}")
    except AwiceApiError as e:
        # 3001/3004 是额度问题，值得单独提示——这是新用户最常卡住的地方
        print(f"  {pol} → {pod}：{e}")
        if e.code in (3001, 3003, 3004):
            print("  （额度用完了，去 https://www.5688.cn/user/openapi 领取或续费）")
            break
