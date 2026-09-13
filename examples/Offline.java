import dev.lm15.ProviderLM;
import dev.lm15.router.LMRouter;
import dev.lm15.router.RouterConfig;
import dev.lm15.sse.Sse;
import dev.lm15.types.Request;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A standalone JAR example: build a request without contacting a provider. */
public class Offline {
    public static void main(String[] args) {
        var router = new LMRouter(RouterConfig.builder().env(Map.of())
            .apiKey("openai", "offline-placeholder").build());
        var request = Request.builder("openai:gpt-4.1-mini").user("Hello from Java").build();
        try (ProviderLM lm = router.lm(request.model())) {
            var wire = lm.buildRequest(request, false);
            if (!"gpt-4.1-mini".equals(wire.body().asObject().get("model").asString())) throw new AssertionError("model was not routed");
            if (!"Bearer offline-placeholder".equals(wire.header("authorization"))) throw new AssertionError("credential was not selected");
            System.out.println(wire.method() + " " + wire.url());
        }
        if (!Sse.parse("data: bundled\r\r".getBytes(StandardCharsets.UTF_8)).get(0).data().equals("bundled")) {
            throw new AssertionError("SSE parser is missing from the package");
        }
    }
}
