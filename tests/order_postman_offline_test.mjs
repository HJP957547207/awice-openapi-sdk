import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const collection = JSON.parse(readFileSync(process.argv[2] ?? new URL("../postman/awice-openapi.postman_collection.json", import.meta.url), "utf8"));
const source = collection.event.find(event => event.listen === "prerequest").script.exec.join("\n");
const orders = collection.item.find(item => item.name.startsWith("物流下单"));
assert.equal(orders.item.length, 9);
assert.deepEqual(orders.item.map(item => item.request.url.path.join("/")), [
  "orders/ocean/create", "orders/air/create", "orders/express/create", "orders/fba/create",
  "orders/options", "orders/detail", "orders/tracking", "orders/update", "orders/cancel",
]);
for (const item of orders.item) {
  const headers = {};
  const pm = {
    environment: { get: key => ({ app_key: "OFFLINE_KEY", app_secret: "OFFLINE_SECRET" })[key] },
    collectionVariables: { get: key => ({ app_key: "OFFLINE_KEY", app_secret: "OFFLINE_SECRET" })[key] },
    variables: { get: key => ({ app_key: "OFFLINE_KEY", app_secret: "OFFLINE_SECRET" })[key], replaceIn: value => value },
    request: { ...item.request, headers: { upsert: ({ key, value }) => { headers[key] = value; } } },
  };
  const CryptoJS = {
    MD5: value => ({ toString: () => createHash("md5").update(value).digest("hex") }),
    HmacSHA256: (value, secret) => ({ toString: () => createHmac("sha256", secret).update(value).digest("hex") }),
  };
  vm.runInNewContext(source, { pm, CryptoJS, console: { log() {}, warn() {} }, Date, Math });
  const body = item.request.method === "POST" ? item.request.body.raw : "";
  const message = [item.request.method, "/openapi/v1/" + item.request.url.path.join("/"),
    headers["X-Awice-Timestamp"], headers["X-Awice-Nonce"], createHash("md5").update(body).digest("hex")].join("\n");
  assert.equal(headers["X-Awice-Signature"], createHmac("sha256", "OFFLINE_SECRET").update(message).digest("hex"));
}
console.log("PASS Postman: nine order endpoints inherit the correct HMAC signature; no network.");
