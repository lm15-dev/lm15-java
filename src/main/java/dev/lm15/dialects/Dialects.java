package dev.lm15.dialects;

import dev.lm15.types.ValidationException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The dialect implementations by key: the four wire ids, plus {@code xai}
 * (the chat dialect with xAI's own image/video surfaces). Implementations
 * register themselves here; a missing one is a loud ValueError.
 */
public final class Dialects {
    private Dialects() {}

    private static final Map<String, Supplier<Dialect>> FACTORIES = new LinkedHashMap<>();
    private static final Map<String, Dialect> INSTANCES = new LinkedHashMap<>();

    static {
        // Each dialect lives in its own package and is looked up reflectively so
        // the core compiles before a dialect lands.
        register("openai-responses", "dev.lm15.dialects.openai.OpenAIResponsesDialect");
        register("openai-chat", "dev.lm15.dialects.openaichat.OpenAIChatDialect");
        register("xai", "dev.lm15.dialects.openaichat.XaiDialect");
        register("anthropic", "dev.lm15.dialects.anthropic.AnthropicDialect");
        register("gemini", "dev.lm15.dialects.gemini.GeminiDialect");
    }

    private static void register(String key, String className) {
        FACTORIES.put(key, () -> {
            try {
                return (Dialect) Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw ValidationException.value("dialect '" + key + "' is not available in this build (" + className + ")");
            }
        });
    }

    public static synchronized void register(String key, Dialect dialect) {
        INSTANCES.put(key, dialect);
    }

    public static synchronized Dialect lookup(String key) {
        Dialect d = INSTANCES.get(key);
        if (d != null) return d;
        Supplier<Dialect> factory = FACTORIES.get(key);
        if (factory == null) throw ValidationException.value("unknown dialect: " + key);
        d = factory.get();
        INSTANCES.put(key, d);
        return d;
    }
}
