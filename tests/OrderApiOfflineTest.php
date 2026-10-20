<?php
namespace Awice\OpenApi {
    function curl_init($url=null) {return (object)['url'=>$url,'opts'=>[]];}
    function curl_setopt_array($ch,$opts) {$ch->opts=$opts;return true;}
    function curl_setopt($ch,$key,$value) {$ch->opts[$key]=$value;return true;}
    function curl_exec($ch) {
        $url=$ch->url ?? $ch->opts[CURLOPT_URL];$body=$ch->opts[CURLOPT_POSTFIELDS]??'';
        $method=$ch->opts[CURLOPT_CUSTOMREQUEST];$headers=[];
        foreach($ch->opts[CURLOPT_HTTPHEADER] as $h) {[$k,$v]=explode(': ',$h,2);$headers[$k]=$v;}
        $path=substr(parse_url($url,PHP_URL_PATH),4);
        $message=$method."\n".$path."\n".$headers['X-Awice-Timestamp']."\n".$headers['X-Awice-Nonce']."\n".md5($body);
        if(!hash_equals(hash_hmac('sha256',$message,'OFFLINE_SECRET'),$headers['X-Awice-Signature'])) throw new \RuntimeException('Signature mismatch');
        $GLOBALS['order_sdk_paths'][]=$path;
        if ($path==='/openapi/v1/freight/fcl/search') {
            if ($method!=='POST') throw new \RuntimeException('FCL must use POST');
            return file_get_contents(__DIR__.'/fixtures/fcl-surcharges.json');
        }
        if(strpos($path,'/openapi/v1/orders/')!==0) throw new \RuntimeException('Wrong path');
        $params=$body!==''?json_decode($body,true):[];
        if($method==='GET') parse_str(parse_url($url,PHP_URL_QUERY)??'',$params);
        if(strpos($path,'/create')!==false && ($params['client_order_no']??'')!=='OFFLINE_REFERENCE') throw new \RuntimeException('Reference lost');
        if ($method!==((substr($path,-8)==='/options')?'GET':'POST')) throw new \RuntimeException('Wrong HTTP method');
        if (strpos($path,'/create')!==false && $params['consignee']['address']!=='测试地址') throw new \RuntimeException('Address lost');
        if (substr($path,-7)==='/update' && $params['changes']!==['note'=>'测试修改']) throw new \RuntimeException('Changes lost');
        if (substr($path,-7)==='/cancel' && $params['reason']!=='测试作废') throw new \RuntimeException('Reason lost');
        $data=['order_no'=>'OFFLINE_ORDER','echo'=>$params];
        if (substr($path,-7)==='/detail') $data['bills']=[['bill_code'=>'OWN_BILL','total_by_currency'=>['USD'=>100,'CNY'=>30],'quotes'=>[['item'=>'DOC','amount'=>30]]]];
        if (substr($path,-9)==='/tracking') $data['events']=[['description'=>'Saved event','is_forecast'=>false,'source'=>'manual']];
        return json_encode(['code'=>0,'data'=>$data,'credit_used'=>0]);
    }
    function curl_error($ch) {return '';}
    function curl_errno($ch) {return 0;}
    function curl_getinfo($ch,$key) {return $key===CURLINFO_HTTP_CODE?200:0;}
    function curl_close($ch) {}
}
namespace {
    require dirname(__DIR__).'/php/AwiceClient.php';
    $clients=[
        new \Awice\OpenApi\AwiceClient('OFFLINE_KEY','OFFLINE_SECRET','https://offline.invalid/api'),
    ];
    // Optional directory package; the published repository also runs on its own.
    if (isset($argv[1])) {
        $src=rtrim($argv[1],'/\\').'/src/';
        if (!is_file($src.'Client.php')) throw new \RuntimeException('Invalid package path');
        spl_autoload_register(function($class) use($src) {
            $prefix='Awice\\OpenApi\\';
            if(strpos($class,$prefix)===0) {$file=$src.str_replace('\\','/',substr($class,strlen($prefix))).'.php';if(is_file($file)) require $file;}
        });
        $clients[]=new \Awice\OpenApi\Client('OFFLINE_KEY','OFFLINE_SECRET',['base_url'=>'https://offline.invalid/api/openapi/v1']);
    }
    $fixture=json_decode(file_get_contents(__DIR__.'/fixtures/fcl-surcharges.json'),true);
    foreach($clients as $i=>$client) {
        foreach(['Ocean','Air','Express','Fba'] as $type) {
            $result=$client->{'create'.$type.'Order'}(['client_order_no'=>'OFFLINE_REFERENCE','consignee'=>['address'=>'测试地址']]);
            $data=$i===0?$result:$result['data'];if($data['order_no']!=='OFFLINE_ORDER') throw new \RuntimeException('Response shape changed');
        }
        $result=$client->orderOptions(['order_type'=>'fba','country'=>'US']);
        $data=$i===0?$result:$result['data'];if($data['echo']['order_type']!=='fba') throw new \RuntimeException('GET query lost');
        $detail=$client->orderDetail(['order_type'=>'ocean','client_order_no'=>'OFFLINE_REFERENCE']);
        $detailData=$i===0?$detail:$detail['data'];
        if ($detailData['bills'][0]['total_by_currency']!==['USD'=>100,'CNY'=>30]) throw new \RuntimeException('Order bills lost in SDK');
        $tracking=$client->orderTracking(['order_type'=>'ocean','client_order_no'=>'OFFLINE_REFERENCE']);
        $trackingData=$i===0?$tracking:$tracking['data'];
        if ($trackingData['events'][0]['is_forecast']!==false || $trackingData['events'][0]['source']!=='manual') throw new \RuntimeException('Tracking events lost in SDK');
        $client->updateOrder(['order_type'=>'ocean','client_order_no'=>'OFFLINE_REFERENCE','changes'=>['note'=>'测试修改']]);
        $client->cancelOrder(['order_type'=>'ocean','client_order_no'=>'OFFLINE_REFERENCE','reason'=>'测试作废']);
        $fcl=$i===0?$client->fcl(['pol'=>'CNSHA','pod'=>'USLAX']):$client->freight->fcl(['pol'=>'CNSHA','pod'=>'USLAX']);
        if ($fcl!==($i===0?$fixture['data']:$fixture)) throw new \RuntimeException('FCL fields or response shape changed');
        $fees=($i===0?$fcl:$fcl['data'])['list'][0]['surcharges'];
        if (array_column($fees,'charge_unit')!==['BILL','CONTAINER','MIXED','UNKNOWN','CONTAINER']) throw new \RuntimeException('Billing basis lost');
        if ($fees[0]['singleprice']!==450 || $fees[2]['singleprice']!==30.25 || $fees[2]['20GP']!==12.3) throw new \RuntimeException('Fee amounts lost');
    }
    $expected=array_map(function($p) {return '/openapi/v1/orders/'.$p;},['ocean/create','air/create','express/create','fba/create','options','detail','tracking','update','cancel']);
    $expected[]='/openapi/v1/freight/fcl/search';
    if ($GLOBALS['order_sdk_paths']!==array_merge(...array_fill(0,count($clients),$expected))) throw new \RuntimeException('Wrong convenience method endpoint');
    echo 'PASS PHP: '.count($GLOBALS['order_sdk_paths'])." signed requests; 9 order APIs and exact FCL surcharge fields/shapes; no network.\n";
}
