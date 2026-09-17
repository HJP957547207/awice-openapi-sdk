<?php
/**
 * 物流巴巴开放平台 PHP SDK（单文件，零依赖，PHP 7.1+）
 *
 * 官网   https://www.5688.cn/open
 * 文档   https://www.5688.cn/open/docs/start
 *
 * 用法：
 *   require 'AwiceClient.php';
 *   $client = new \Awice\OpenApi\AwiceClient('你的AppKey', '你的AppSecret');
 *   $res = $client->fcl(['pol' => 'CNSHA', 'pod' => 'USLAX']);
 *
 * 这个 SDK 存在的意义不是少写几行 curl，而是把几个最容易踩的坑挡在外面：
 *   1. 签名的 PATH **不含 `/api` 前缀** —— 请求打的是 /api/openapi/v1/...，
 *      签名只能用 /openapi/v1/...，自己实现时最常错的就是这里
 *   2. body 必须用**同一个 JSON 字符串**算 MD5 和发送，重新 encode 一次就对不上
 *   3. QPS 超限(4001) 自动退避重试，不用自己写重试逻辑
 *   4. 常见业务错误给出人话提示，比如「积分不足」会告诉你去控制台领免费额度
 */

namespace Awice\OpenApi;

/**
 * 接口返回业务错误（code != 0）时抛出
 */
class AwiceApiException extends \Exception
{
    /** @var string 请求 ID，报障时提供给客服能直接定位 */
    public $requestId = '';

    /** @var array 多匹配(1002)时的候选列表 */
    public $candidates = [];

    public function __construct($message, $code = 0, $requestId = '', array $candidates = [])
    {
        parent::__construct($message, $code);
        $this->requestId = $requestId;
        $this->candidates = $candidates;
    }
}

/** 网络层错误（超时、DNS、连不上） */
class AwiceTransportException extends \Exception
{
}

class AwiceClient
{
    const VERSION = '1.0.0';

    /** 默认网关。私有化部署改这里 */
    const DEFAULT_BASE_URL = 'https://www.5688.cn/api';

    private $appKey;
    private $appSecret;
    private $baseUrl;

    /** @var int 连接超时（秒） */
    private $connectTimeout = 5;

    /**
     * @var int 读取超时（秒）
     *
     * 快递接口要实时问 4 家上游，未命中缓存时 30 秒以上是正常的，别设太小。
     */
    private $timeout = 60;

    /** @var int 撞到 QPS 限制(4001)时的最大重试次数 */
    private $maxRetries = 3;

    /**
     * @param string $appKey    控制台创建应用后获得
     * @param string $appSecret 同上；只在本地参与签名计算，不会随请求发出
     * @param string $baseUrl   网关地址，一般不用改
     */
    public function __construct($appKey, $appSecret, $baseUrl = self::DEFAULT_BASE_URL)
    {
        if (!is_string($appKey) || $appKey === '' || !is_string($appSecret) || $appSecret === '') {
            throw new \InvalidArgumentException('AppKey / AppSecret 不能为空，请到 https://www.5688.cn/user/openapi/apps 创建应用获取');
        }
        $this->appKey = $appKey;
        $this->appSecret = $appSecret;
        $this->baseUrl = rtrim($baseUrl, '/');
    }

    /** 设置超时（秒）。快递接口慢，读取超时不建议低于 40 */
    public function setTimeout($timeout, $connectTimeout = null)
    {
        $this->timeout = max(1, (int)$timeout);
        if ($connectTimeout !== null) {
            $this->connectTimeout = max(1, (int)$connectTimeout);
        }
        return $this;
    }

    /** 撞到限流时重试几次，设 0 关闭自动重试 */
    public function setMaxRetries($n)
    {
        $this->maxRetries = max(0, (int)$n);
        return $this;
    }

    // ───────────────────────── 运价接口 ─────────────────────────

    /**
     * 海运整柜 FCL 运价
     *
     * @param array $params pol 起运港(必填) / pod 目的港(必填) / carrier 船公司(可选)
     *                      / page 页码 / limit 每页(最大50)
     *                      pol、pod 支持 UN/LOCODE、中文名、英文名，例如 CNSHA、上海、shanghai
     *                      同名港口有歧义时（如「奥克兰」美国和新西兰都有）会返回 1002，
     *                      用 pol_country / pod_country 传 2 位国家码消歧
     */
    public function fcl(array $params)
    {
        return $this->post('/openapi/v1/freight/fcl/search', $params);
    }

    /** 海运拼箱 LCL 运价。参数同 FCL，另可传 warehouse 仓库代码 */
    public function lcl(array $params)
    {
        return $this->post('/openapi/v1/freight/lcl/search', $params);
    }

    /** 国际空运运价。pol/pod 为机场（IATA 三字码/中文/英文），可选 airline */
    public function air(array $params)
    {
        return $this->post('/openapi/v1/freight/air/search', $params);
    }

    /**
     * 国际快递 / 专线运价
     *
     * ⚠️ 这个接口会实时询问多家上游，未命中缓存时耗时 30 秒以上属正常，
     *    调用前确认你的 HTTP 超时和 PHP max_execution_time 都留够。
     */
    public function express(array $params)
    {
        return $this->post('/openapi/v1/freight/express/search', $params);
    }

    /** FBA 头程运价。按亚马逊仓库代码（如 ONT8、FTW1）查询 */
    public function fba(array $params)
    {
        return $this->post('/openapi/v1/freight/fba/search', $params);
    }

    // ───────────────────────── 基础数据字典 ─────────────────────────

    /** 港口字典。keyword 模糊搜索，返回 code / 中英文名 / 国家 */
    public function ports(array $query = [])
    {
        return $this->get('/openapi/v1/dict/port', $query);
    }

    /** 机场字典 */
    public function airports(array $query = [])
    {
        return $this->get('/openapi/v1/dict/airport', $query);
    }

    /** 船公司字典 */
    public function carriers(array $query = [])
    {
        return $this->get('/openapi/v1/dict/carrier', $query);
    }

    /** 航空公司字典 */
    public function airlines(array $query = [])
    {
        return $this->get('/openapi/v1/dict/airline', $query);
    }

    /** 国家字典 */
    public function countries(array $query = [])
    {
        return $this->get('/openapi/v1/dict/country', $query);
    }

    /**
     * 常用数据集（港口/机场各 500 条 + 船司/航司/国家全量）
     *
     * 做下拉选择器就拉这个，本地缓存起来，别高频打搜索接口。
     */
    public function popular(array $query = [])
    {
        return $this->get('/openapi/v1/dict/popular', $query);
    }

    /** 连通性自检，不消耗积分。对接第一步先调它确认签名正确 */
    public function ping()
    {
        return $this->get('/openapi/v1/ping');
    }

    // ───────────────────────── 底层请求 ─────────────────────────

    /**
     * @param string $path 以 / 开头、**不含** /api 前缀的接口路径
     */
    public function post($path, array $body = [])
    {
        // 关键：先定下这个 JSON 字符串，签名和发送必须用同一份。
        // 若签名时 encode 一次、发送时再 encode 一次，只要键顺序或转义有任何差异，
        // 服务端算出的 MD5(BODY) 就对不上，直接 2002 签名失败。
        $payload = empty($body) ? '{}' : json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        return $this->request('POST', $path, $payload, []);
    }

    /**
     * @param string $path  以 / 开头、**不含** /api 前缀的接口路径
     * @param array  $query URL 查询参数；注意签名时**不含** query string
     */
    public function get($path, array $query = [])
    {
        return $this->request('GET', $path, '', $query);
    }

    /**
     * @return array 业务数据（已剥掉外层 code/msg 包装）
     * @throws AwiceApiException|AwiceTransportException
     */
    private function request($method, $path, $payload, array $query)
    {
        $path = '/' . ltrim($path, '/');
        $attempt = 0;

        while (true) {
            $attempt++;
            list($httpCode, $rawBody, $headers, $curlErr) = $this->send($method, $path, $payload, $query);

            if ($curlErr !== '') {
                throw new AwiceTransportException('请求失败：' . $curlErr);
            }

            $json = json_decode($rawBody, true);
            if (!is_array($json)) {
                throw new AwiceTransportException(
                    '响应不是合法 JSON（HTTP ' . $httpCode . '）：' . mb_substr((string)$rawBody, 0, 200)
                );
            }

            $code = isset($json['code']) ? (int)$json['code'] : -1;
            $msg = isset($json['msg']) ? (string)$json['msg'] : '';
            $requestId = isset($json['request_id']) ? (string)$json['request_id'] : '';

            if ($code === 0) {
                return isset($json['data']) ? $json['data'] : [];
            }

            // 4001 QPS 超限 / 4004 并发超限：按服务端给的 Retry-After 退避后重试。
            // 批量跑数据时这两个几乎必然会撞上，自动退避比让调用方自己写循环靠谱。
            if (($code === 4001 || $code === 4004) && $attempt <= $this->maxRetries) {
                $wait = isset($headers['retry-after']) ? (int)$headers['retry-after'] : 1;
                sleep(max(1, min($wait, 10)));
                continue;
            }

            throw new AwiceApiException(
                $this->explain($code, $msg),
                $code,
                $requestId,
                isset($json['data']['candidates']) ? $json['data']['candidates'] : []
            );
        }
    }

    /**
     * @return array [httpCode, body, headersLowerCase, curlError]
     */
    private function send($method, $path, $payload, array $query)
    {
        $timestamp = (string)time();
        $nonce = $this->nonce();

        // ⚠️ 签名用的 PATH 不含 /api 前缀、不含 query string。
        //    请求 URL 是 baseUrl(.../api) + path，两者刻意不同，这是最高频的踩坑点。
        $message = strtoupper($method) . "\n" . $path . "\n" . $timestamp . "\n" . $nonce . "\n" . md5($payload);
        $signature = hash_hmac('sha256', $message, $this->appSecret);

        $url = $this->baseUrl . $path;
        if (!empty($query)) {
            $url .= '?' . http_build_query($query);
        }

        $ch = curl_init($url);
        curl_setopt_array($ch, array(
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CUSTOMREQUEST => strtoupper($method),
            CURLOPT_CONNECTTIMEOUT => $this->connectTimeout,
            CURLOPT_TIMEOUT => $this->timeout,
            CURLOPT_HEADER => true,
            CURLOPT_HTTPHEADER => array(
                'Content-Type: application/json',
                'X-Awice-AppKey: ' . $this->appKey,
                'X-Awice-Timestamp: ' . $timestamp,
                'X-Awice-Nonce: ' . $nonce,
                'X-Awice-Signature: ' . $signature,
                // 带上 UA：某些网关会拦截空 User-Agent 的请求
                'User-Agent: awice-php-sdk/' . self::VERSION,
            ),
        ));
        if (strtoupper($method) !== 'GET') {
            curl_setopt($ch, CURLOPT_POSTFIELDS, $payload);
        }

        $raw = curl_exec($ch);
        $err = curl_error($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $headerSize = (int)curl_getinfo($ch, CURLINFO_HEADER_SIZE);
        curl_close($ch);

        if ($raw === false) {
            return array($httpCode, '', array(), $err !== '' ? $err : '未知网络错误');
        }

        $headers = $this->parseHeaders(substr($raw, 0, $headerSize));
        $body = substr($raw, $headerSize);

        return array($httpCode, $body, $headers, '');
    }

    private function parseHeaders($rawHeaders)
    {
        $out = array();
        foreach (explode("\r\n", (string)$rawHeaders) as $line) {
            $pos = strpos($line, ':');
            if ($pos === false) continue;
            $out[strtolower(trim(substr($line, 0, $pos)))] = trim(substr($line, $pos + 1));
        }
        return $out;
    }

    /** 8-32 字节随机串，10 分钟内不可重复 */
    private function nonce()
    {
        if (function_exists('random_bytes')) {
            return bin2hex(random_bytes(8));
        }
        return md5(uniqid((string)mt_rand(), true));
    }

    /**
     * 把服务端错误翻译成「该怎么办」
     *
     * 线上日志显示 70% 的失败其实是额度问题而不是代码问题，最典型的是
     * 注册了却没点「领取免费体验包」，然后代码写得再对也一路被拒。
     * 与其让人对着「配额不足」发呆，不如直接告诉他去哪点一下。
     */
    private function explain($code, $msg)
    {
        $hints = array(
            2001 => 'AppKey 不存在或应用已禁用，检查是否用错了环境的密钥',
            2002 => '签名校验失败。九成是这三种：签名的 PATH 带上了 /api 前缀或 query string；'
                . 'body 算 MD5 和实际发送的不是同一个字符串；AppSecret 复制时多了空格',
            2003 => '时间戳超出 ±300 秒，检查服务器时间是否与 NTP 同步',
            2004 => 'Nonce 重复，同一个随机串 10 分钟内只能用一次',
            2005 => '来源 IP 不在白名单，去控制台应用设置里添加当前出口 IP',
            3001 => '积分不足。如果还没领过免费体验包，去 https://www.5688.cn/user/openapi 点「领取」即可继续调用',
            3003 => '当前没有有效订阅，去控制台领取免费体验包或购买套餐',
            3004 => '套餐已到期，续费后继续使用',
            4001 => 'QPS 超限，降低并发或升级套餐（SDK 已自动重试仍未成功）',
            4004 => '快递接口并发超限，等上一个请求返回再发起下一个',
            1001 => '参数无效或港口/机场名无法识别，可先用 ports()/airports() 查正确代码',
            1002 => '名称有歧义匹配到多个，用 pol_country / pod_country 传 2 位国家码消歧，'
                . '候选见异常对象的 candidates 属性',
            5002 => '上游数据源暂时不可用，稍后重试',
        );
        $hint = isset($hints[$code]) ? $hints[$code] : '';
        return $hint === '' ? $msg : $msg . '｜' . $hint;
    }
}
