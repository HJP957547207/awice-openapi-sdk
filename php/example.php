<?php
/**
 * 物流巴巴开放平台 PHP SDK 示例
 *
 * 跑之前把下面两行换成你自己的密钥（控制台 https://www.5688.cn/user/openapi/apps 创建应用获取），
 * 然后：php example.php
 */

require __DIR__ . '/AwiceClient.php';

use Awice\OpenApi\AwiceApiException;
use Awice\OpenApi\AwiceClient;
use Awice\OpenApi\AwiceTransportException;

$APP_KEY = getenv('AWICE_APP_KEY') ?: '你的AppKey';
$APP_SECRET = getenv('AWICE_APP_SECRET') ?: '你的AppSecret';

$client = new AwiceClient($APP_KEY, $APP_SECRET);

// ── 1. 先确认签名通了 ─────────────────────────────────────────
// ping 不消耗积分，对接第一步永远先跑它：通了说明密钥和签名都没问题，
// 后面再出错就一定是业务参数的事，能省掉大量瞎猜。
try {
    $pong = $client->ping();
    echo "[ping] 连通正常，应用：{$pong['app']['app_name']}\n\n";
} catch (AwiceApiException $e) {
    exit("[ping] 失败（{$e->getCode()}）：{$e->getMessage()}\n");
} catch (AwiceTransportException $e) {
    exit("[ping] 网络不通：{$e->getMessage()}\n");
}

// ── 2. 查一条整柜运价 ─────────────────────────────────────────
try {
    $res = $client->fcl([
        'pol'   => 'CNSHA',    // 起运港：LOCODE / 中文 / 英文都行
        'pod'   => '洛杉矶',    // 目的港
        'limit' => 5,
    ]);

    echo "[fcl] 上海 → 洛杉矶，共 {$res['total']} 条：\n";
    foreach ($res['list'] as $rate) {
        $p = $rate['prices'];
        printf(
            "  %-10s 20GP %-7s 40HQ %-7s %s  航程 %s 天  有效期至 %s\n",
            $rate['carrier']['name'],
            $p['20GP'], $p['40HQ'], $p['currency'],
            $rate['transit']['days'],
            $rate['valid_until']
        );
    }
    echo "\n";
} catch (AwiceApiException $e) {
    echo "[fcl] 失败（{$e->getCode()}）：{$e->getMessage()}\n\n";
}

// ── 3. 处理同名港口歧义 ───────────────────────────────────────
// 「奥克兰」美国(USOAK)和新西兰(NZAUC)都有，服务端会返回 1002 并给出候选，
// 拿 candidates 让用户选，或者直接用 pod_country 指定国家。
try {
    $client->fcl(['pol' => 'CNSHA', 'pod' => '奥克兰', 'limit' => 3]);
} catch (AwiceApiException $e) {
    if ($e->getCode() === 1002) {
        echo "[歧义] 候选港口：\n";
        foreach ($e->candidates as $c) {
            echo "  {$c['code']}  {$c['name']} ({$c['country_code']})\n";
        }
        // 用国家码消歧后重查
        $res = $client->fcl(['pol' => 'CNSHA', 'pod' => '奥克兰', 'pod_country' => 'US', 'limit' => 3]);
        echo "  指定 US 后拿到 {$res['total']} 条报价\n\n";
    }
}

// ── 4. 批量跑多条航线 ─────────────────────────────────────────
// 撞到 QPS 上限(4001) 时 SDK 会自动按 Retry-After 退避重试，这里不用写重试逻辑。
$routes = [
    ['CNSHA', 'USLAX'],
    ['CNYTN', 'DEHAM'],
    ['CNNGB', 'NLRTM'],
];
echo "[批量] 三条航线最低价：\n";
foreach ($routes as list($pol, $pod)) {
    try {
        $res = $client->fcl(['pol' => $pol, 'pod' => $pod, 'limit' => 50]);
        $min = null;
        foreach ($res['list'] as $rate) {
            $v = $rate['prices']['40HQ'] ?? null;
            if ($v !== null && ($min === null || $v < $min)) $min = $v;
        }
        echo "  {$pol} → {$pod}：40HQ 最低 " . ($min ?? '无报价') . "\n";
    } catch (AwiceApiException $e) {
        // 3001/3004 是额度问题，值得单独提示——这是新用户最常卡住的地方
        echo "  {$pol} → {$pod}：{$e->getMessage()}\n";
        if (in_array($e->getCode(), [3001, 3003, 3004], true)) {
            echo "  （额度用完了，去 https://www.5688.cn/user/openapi 领取或续费）\n";
            break;
        }
    }
}
