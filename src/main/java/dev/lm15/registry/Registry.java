package dev.lm15.registry;

import dev.lm15.auth.Access;
import dev.lm15.auth.AccessPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one table of named providers (lm15-python {@code lm15/registry.py}).
 * A routable provider string names a dialect, an access policy and, for the
 * chat and Messages dialects, the compat preset naming the server's quirks.
 * Declaration order is presentation order.
 */
public final class Registry {
    private Registry() {}

    /** Provider strings are hyphenated; the underscore spelling is a permanent input alias. */
    public static String canonicalProvider(String name) {
        return name.replace('_', '-');
    }

    private static ProviderDefinition owned(String id, DialectId dialect, String impl, AccessPolicy access, String consoleUrl, String note) {
        return new ProviderDefinition(id, dialect, impl, access, null, null, consoleUrl, note);
    }

    private static ProviderDefinition chatBound(AccessPolicy access, String compat, String placeholderKey, String consoleUrl, String note) {
        return new ProviderDefinition(access.provider(), DialectId.OPENAI_CHAT, null, access, compat == null ? access.provider() : compat, placeholderKey, consoleUrl, note);
    }

    private static ProviderDefinition responsesBound(AccessPolicy access, String compat, String consoleUrl, String note) {
        return new ProviderDefinition(access.provider(), DialectId.OPENAI_RESPONSES, null, access, compat, null, consoleUrl, note);
    }

    private static ProviderDefinition anthropicBound(AccessPolicy access, String compat, String consoleUrl, String note) {
        return new ProviderDefinition(access.provider(), DialectId.ANTHROPIC, null, access, compat, null, consoleUrl, note);
    }

    private static ProviderDefinition hosted(AccessPolicy access, DialectId dialect, String compat, String consoleUrl, String note) {
        return new ProviderDefinition(access.provider(), dialect, null, access, compat, null, consoleUrl, note);
    }

    private static final List<ProviderDefinition> DEFINITIONS = List.of(
        owned("openai", DialectId.OPENAI_RESPONSES, null, Access.OPENAI_API, "https://platform.openai.com/api-keys", "OpenAI Responses API"),
        owned("openai-chat", DialectId.OPENAI_CHAT, null, Access.OPENAI_CHAT_API, "https://platform.openai.com/api-keys", "OpenAI Chat Completions dialect (the de-facto standard other servers speak)"),
        owned("anthropic", DialectId.ANTHROPIC, null, Access.ANTHROPIC_API, "https://console.anthropic.com", "Anthropic Messages API"),
        owned("gemini", DialectId.GEMINI, null, Access.GEMINI_API, "https://aistudio.google.com/apikey", "Google Gemini API"),
        owned("xai", DialectId.OPENAI_CHAT, "xai", Access.XAI, "https://console.x.ai", "xAI Grok (Chat Completions dialect; XAI_API_KEY or subscription OAuth)"),
        owned("claude-code", DialectId.ANTHROPIC, null, Access.CLAUDE_CODE, null, "Claude subscription through the local `claude` CLI login"),
        owned("openai-codex", DialectId.OPENAI_RESPONSES, null, Access.OPENAI_CODEX, null, "ChatGPT subscription through the local `codex` CLI login"),
        chatBound(Access.GROQ, null, null, "https://console.groq.com/keys", "Groq Cloud (Chat Completions dialect)"),
        chatBound(Access.OPENROUTER, null, null, "https://openrouter.ai/keys", "OpenRouter (Chat Completions dialect)"),
        chatBound(Access.DEEPSEEK, null, null, "https://platform.deepseek.com/api_keys", "DeepSeek (Chat Completions dialect; thinking mode on by default)"),
        anthropicBound(Access.DEEPSEEK_ANTHROPIC, "deepseek", "https://platform.deepseek.com/api_keys", "DeepSeek over the Anthropic Messages wire (same key as `deepseek`; no model listing)"),
        chatBound(Access.ZAI, null, null, "https://z.ai/manage-apikey/apikey-list", "Z.AI GLM (Chat Completions dialect; general endpoint, not the Coding Plan)"),
        chatBound(Access.MOONSHOTAI, null, null, "https://platform.kimi.ai/console/api-keys", "Moonshot AI Kimi (Chat Completions dialect)"),
        responsesBound(Access.MOONSHOTAI_RESPONSES, "moonshotai", "https://platform.kimi.ai/console/api-keys", "Moonshot AI Kimi over the Responses wire (same key as `moonshotai`; kimi-k3 only; stateless; web_search built-in)"),
        anthropicBound(Access.MOONSHOTAI_ANTHROPIC, "moonshotai", "https://platform.kimi.ai/console/api-keys", "Moonshot AI Kimi over the Anthropic Messages wire (same key as `moonshotai`, bearer token; kimi-k3 only)"),
        responsesBound(Access.META, "meta", "https://dev.meta.ai/", "Meta Model API over the Responses wire, plus Files, Images and Models"),
        chatBound(Access.META_CHAT, "meta", null, "https://dev.meta.ai/", "Meta Model API over the Chat Completions wire (same key as `meta`; no cross-turn reasoning)"),
        anthropicBound(Access.META_ANTHROPIC, "meta", "https://dev.meta.ai/", "Meta Model API over the Anthropic Messages wire (same key as `meta`; bearer token)"),
        hosted(Access.AZURE, DialectId.OPENAI_RESPONSES, null, "https://portal.azure.com/", "Azure OpenAI v1 Responses wire ({resource}.openai.azure.com; model = deployment name; api-key or Entra token)"),
        hosted(Access.AZURE_CHAT, DialectId.OPENAI_CHAT, "openai", "https://portal.azure.com/", "Azure OpenAI v1 Chat Completions wire (same resource)"),
        hosted(Access.AZURE_ANTHROPIC, DialectId.ANTHROPIC, null, "https://ai.azure.com/", "Claude in Microsoft Foundry ({resource}.services.ai.azure.com/anthropic)"),
        hosted(Access.AWS_ANTHROPIC, DialectId.ANTHROPIC, null, "https://console.aws.amazon.com/", "Claude Platform on AWS (Anthropic-operated; SigV4 or ANTHROPIC_AWS_API_KEY)"),
        hosted(Access.BEDROCK_ANTHROPIC, DialectId.ANTHROPIC, null, "https://console.aws.amazon.com/bedrock/", "Claude in Amazon Bedrock (bedrock-mantle; SigV4 or AWS_BEARER_TOKEN_BEDROCK)"),
        hosted(Access.BEDROCK_CHAT, DialectId.OPENAI_CHAT, "bedrock", "https://console.aws.amazon.com/bedrock/", "Amazon Bedrock over the OpenAI Chat Completions wire (bedrock-runtime /openai/v1)"),
        hosted(Access.BEDROCK_MANTLE_CHAT, DialectId.OPENAI_CHAT, "bedrock-mantle", "https://console.aws.amazon.com/bedrock/", "Amazon Bedrock Chat Completions on bedrock-mantle (un-versioned ids, GET /v1/models)"),
        hosted(Access.VERTEX, DialectId.GEMINI, null, "https://console.cloud.google.com/vertex-ai", "Gemini on Google Cloud (Agent Platform); ADC chain"),
        hosted(Access.VERTEX_EXPRESS, DialectId.GEMINI, null, "https://console.cloud.google.com/vertex-ai/studio", "Agent Platform express mode: GOOGLE_API_KEY as ?key=, no project or location"),
        hosted(Access.VERTEX_ANTHROPIC, DialectId.ANTHROPIC, null, "https://console.cloud.google.com/vertex-ai/model-garden", "Claude on Google Cloud (rawPredict; model in the path, anthropic_version in the body)"),
        chatBound(Access.OLLAMA, null, "ollama", null, "local ollama server (keyless)"),
        chatBound(Access.VLLM, null, "EMPTY", null, "local vLLM server (keyless)"),
        chatBound(Access.SGLANG, null, "EMPTY", null, "local SGLang server (keyless)"));

    public static final Map<String, ProviderDefinition> PROVIDERS;

    static {
        LinkedHashMap<String, ProviderDefinition> m = new LinkedHashMap<>();
        for (ProviderDefinition d : DEFINITIONS) m.put(d.id(), d);
        PROVIDERS = Collections.unmodifiableMap(m);
    }

    /** The definition for a provider string in either spelling, or null. */
    public static ProviderDefinition lookup(String name) {
        return PROVIDERS.get(canonicalProvider(name));
    }

    /** The definition, or a ValueError naming the string. */
    public static ProviderDefinition require(String name) {
        ProviderDefinition d = lookup(name);
        if (d == null) throw dev.lm15.types.ValidationException.value("unknown provider: " + name);
        return d;
    }

    public static List<ProviderDefinition> all() { return DEFINITIONS; }
}
