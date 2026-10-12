import java.io.IOException;
import java.util.Map;

/** 默认只运行免费 ping；添加 --fcl 才查询运价。不会提交真实订单。 */
public class Example {
    public static void main(String[] args) throws IOException, InterruptedException {
        String key = System.getenv("AWICE_APP_KEY");
        String secret = System.getenv("AWICE_APP_SECRET");
        if (key == null || key.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("请设置 AWICE_APP_KEY 和 AWICE_APP_SECRET 环境变量");
        }
        AwiceClient client = new AwiceClient(key, secret);
        try {
            System.out.println("Ping: " + client.ping());
            if (args.length > 0 && "--fcl".equals(args[0])) {
                AwiceClient.ApiResponse response = client.fcl(Map.of(
                        "pol", "CNSHA", "pod", "USLAX", "limit", 10));
                System.out.println("FCL data: " + AwiceClient.Json.stringify(response.getData()));
                System.out.println("Credits: " + response.getEnvelope().get("credit_used"));
            }
        } catch (AwiceClient.ApiException ex) {
            System.err.println("API code=" + ex.code + ", request_id=" + ex.requestId);
            System.err.println(ex.getMessage());
            if (!ex.candidates.isEmpty()) System.err.println("Candidates: " + ex.candidates);
            if (!ex.errors.isEmpty()) System.err.println("Validation errors: " + ex.errors);
            throw ex;
        }
    }
}
