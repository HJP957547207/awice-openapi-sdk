"""Offline transport interception for both Python SDK variants."""
import hashlib, hmac, importlib.util, json, sys
from pathlib import Path
from urllib.parse import urlsplit, parse_qs
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("single_awice", ROOT / "python/awice_client.py")
single = importlib.util.module_from_spec(spec)
spec.loader.exec_module(single)
fixture = json.loads((ROOT / "tests/fixtures/fcl-surcharges.json").read_text(encoding="utf-8"))
paths = []
def capture(method, url, headers, body):
    parts = urlsplit(url)
    assert parts.path.startswith("/api/openapi/v1/")
    path = parts.path[4:]
    hdr = {k.lower(): v for k, v in headers.items()}
    message = "\n".join([method, path, hdr["x-awice-timestamp"], hdr["x-awice-nonce"], hashlib.md5(body).hexdigest()])
    assert hmac.new(b"OFFLINE_SECRET", message.encode(), hashlib.sha256).hexdigest() == hdr["x-awice-signature"]
    paths.append(path)
    if path == "/openapi/v1/freight/fcl/search":
        assert method == "POST"
        return fixture
    assert method == ("GET" if path.endswith("/options") else "POST")
    params = json.loads(body) if body else {k: v[0] for k, v in parse_qs(parts.query).items()}
    if path.endswith("/create"):
        assert params["client_order_no"] == "OFFLINE_REFERENCE"
        assert params["consignee"]["address"] == "测试地址"
    if path.endswith("/options"):
        assert params == {"order_type": "fba", "country": "US"}
    if path.endswith("/update"):
        assert params["changes"] == {"note": "测试修改"}
    if path.endswith("/cancel"):
        assert params["reason"] == "测试作废"
    data = {"order_no": "OFFLINE_ORDER"}
    if path.endswith("/detail"):
        data["bills"] = [{"bill_code": "OWN_BILL", "total_by_currency": {"USD": 100, "CNY": 30}, "quotes": [{"item": "DOC", "amount": 30}]}]
    if path.endswith("/tracking"):
        data["events"] = [{"description": "Saved event", "is_forecast": False, "source": "manual"}]
    return {"code": 0, "data": data, "credit_used": 0}
class Response:
    headers = {}
    status = 200
    status_code = 200
    def __init__(self, value): self.value = value
    def read(self): return json.dumps(self.value).encode()
    def json(self): return self.value
    def __enter__(self): return self
    def __exit__(self, *args): return False
def urlopen(req, **kwargs):
    return Response(capture(req.get_method(), req.full_url, dict(req.header_items()), req.data or b""))
class Session:
    def post(self, url, data, headers, **kwargs):
        return Response(capture("POST", url, headers, data))
    def request(self, method, url, headers, **kwargs):
        return Response(capture(method, url, headers, b""))
clients = [
    single.AwiceClient("OFFLINE_KEY", "OFFLINE_SECRET", base_url="https://offline.invalid/api"),
]
# Optional directory package; the published repository runs without it or requests.
if len(sys.argv) > 1:
    package = Path(sys.argv[1]).resolve()
    assert (package / "awice_openapi/client.py").is_file(), "Invalid package path"
    sys.path.insert(0, str(package))
    from awice_openapi import Client
    clients.append(Client("OFFLINE_KEY", "OFFLINE_SECRET", base_url="https://offline.invalid/api/openapi/v1"))
    clients[1].session = Session()
with patch("urllib.request.urlopen", urlopen):
    for index, client in enumerate(clients):
        for order_type in ("ocean", "air", "express", "fba"):
            result = getattr(client, "create_" + order_type + "_order")(client_order_no="OFFLINE_REFERENCE", consignee={"address": "测试地址"})
            assert (result if index == 0 else result["data"])["order_no"] == "OFFLINE_ORDER"
        client.order_options(order_type="fba", country="US")
        detail = client.order_detail(order_type="ocean", client_order_no="OFFLINE_REFERENCE")
        assert (detail if index == 0 else detail["data"])["bills"][0]["total_by_currency"] == {"USD": 100, "CNY": 30}
        tracking = client.order_tracking(order_type="ocean", client_order_no="OFFLINE_REFERENCE")
        assert (tracking if index == 0 else tracking["data"])["events"] == [{"description": "Saved event", "is_forecast": False, "source": "manual"}]
        client.update_order(order_type="ocean", client_order_no="OFFLINE_REFERENCE", changes={"note": "测试修改"})
        client.cancel_order(order_type="ocean", client_order_no="OFFLINE_REFERENCE", reason="测试作废")
        fcl = client.fcl(pol="CNSHA", pod="USLAX")
        assert fcl == (fixture["data"] if index == 0 else fixture)
        fees = (fcl if index == 0 else fcl["data"])["list"][0]["surcharges"]
        assert [fee["charge_unit"] for fee in fees] == ["BILL", "CONTAINER", "MIXED", "UNKNOWN", "CONTAINER"]
        assert fees[0]["singleprice"] == 450 and fees[2]["singleprice"] == 30.25 and fees[2]["20GP"] == 12.3
expected = ["ocean/create", "air/create", "express/create", "fba/create", "options", "detail", "tracking", "update", "cancel"]
assert paths == (["/openapi/v1/orders/" + p for p in expected] + ["/openapi/v1/freight/fcl/search"]) * len(clients)
print("PASS Python: {} signed requests; 9 order APIs and exact FCL surcharge fields/shapes; no network.".format(len(paths)))
