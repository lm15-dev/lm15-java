package dev.lm15.auth;

import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static dev.lm15.auth.AuthScheme.API_KEY;
import static dev.lm15.auth.AuthScheme.BEARER;
import static dev.lm15.auth.AuthScheme.QUERY_KEY;
import static dev.lm15.auth.AuthScheme.SIGV4;
import static dev.lm15.auth.AuthScheme.X_API_KEY;

/**
 * The access-policy table (lm15-python {@code lm15/access.py}; spec/auth.md
 * AUTH-10), copied as data, plus scheme selection (AUTH-2).
 */
public final class Access {
    private Access() {}

    // ─── login hints (AUTH-6/AUTH-9) ─────────────────────────────────────
    public static final String CLAUDE_CODE_LOGIN_HINT = "Log in again: run `claude` and use /login (Claude subscription auth)";
    public static final String OPENAI_CODEX_LOGIN_HINT = "Log in again: run `codex login` (ChatGPT subscription auth)";
    public static final String XAI_LOGIN_HINT = "Log in again: run Login.xai() (SuperGrok / X Premium subscription auth)";

    public static final String DEFAULT_CLAUDE_CODE_VERSION = "2.1.285";
    public static final String DEFAULT_CLAUDE_CODE_SYSTEM_PROMPT = "You are Claude Code, Anthropic's official CLI for Claude.";
    public static final String DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex";
    public static final String DEFAULT_CODEX_ORIGINATOR = "lm15";
    public static final String DEFAULT_CODEX_INSTRUCTIONS = "You are a helpful assistant.";
    public static final String DEFAULT_CODEX_CLIENT_VERSION = "0.147.0";
    public static final String DEFAULT_XAI_BASE_URL = "https://api.x.ai/v1";

    // ─── preset base URLs (one copy of each URL; the compat tables read these) ───
    public static final Map<String, String> OPENAI_CHAT_PRESET_BASE_URLS = Map.ofEntries(
        Map.entry("openai", "https://api.openai.com/v1"),
        Map.entry("ollama", "http://localhost:11434/v1"),
        Map.entry("lmstudio", "http://localhost:1234/v1"),
        Map.entry("groq", "https://api.groq.com/openai/v1"),
        Map.entry("openrouter", "https://openrouter.ai/api/v1"),
        Map.entry("xai", "https://api.x.ai/v1"),
        Map.entry("vllm", "http://localhost:8000/v1"),
        Map.entry("sglang", "http://localhost:30000/v1"),
        Map.entry("deepseek", "https://api.deepseek.com"),
        Map.entry("zai", "https://api.z.ai/api/paas/v4"),
        Map.entry("meta", "https://api.meta.ai/v1"),
        Map.entry("moonshotai", "https://api.moonshot.ai/v1"));

    public static final Map<String, String> OPENAI_RESPONSES_PRESET_BASE_URLS = Map.ofEntries(
        Map.entry("openai", "https://api.openai.com/v1"),
        Map.entry("ollama", "http://localhost:11434/v1"),
        Map.entry("lmstudio", "http://localhost:1234/v1"),
        Map.entry("vllm", "http://localhost:8000/v1"),
        Map.entry("sglang", "http://localhost:30000/v1"),
        Map.entry("openrouter", "https://openrouter.ai/api/v1"),
        Map.entry("meta", "https://api.meta.ai/v1"),
        Map.entry("moonshotai", "https://api.moonshot.ai/v1"));

    public static final Map<String, String> ANTHROPIC_PRESET_BASE_URLS = Map.ofEntries(
        Map.entry("anthropic", "https://api.anthropic.com/v1"),
        Map.entry("deepseek", "https://api.deepseek.com/anthropic/v1"),
        Map.entry("meta", "https://api.meta.ai/v1"),
        Map.entry("moonshotai", "https://api.moonshot.ai/anthropic/v1"));

    private static EndpointSupport.Builder s() { return EndpointSupport.builder(); }

    // ─── the table ───────────────────────────────────────────────────────

    public static final AccessPolicy ANTHROPIC_API = AccessPolicy.builder("anthropic")
        .supports(s().files(true).batches(true).models(true).build()).authModes("x-api-key").envKeys("ANTHROPIC_API_KEY").authScheme(X_API_KEY).build();

    public static final AccessPolicy CLAUDE_CODE = AccessPolicy.builder("claude-code")
        .supports(s().models(true).build()).credentialPolicy(CredentialPolicy.OAUTH).authModes("claude-code-oauth", "bearer-oauth").authScheme(BEARER)
        .headers(List.of(Map.entry("anthropic-dangerous-direct-browser-access", "true"),
            Map.entry("anthropic-beta", "claude-code-20250219,oauth-2025-04-20"), Map.entry("x-app", "cli"),
            Map.entry("user-agent", "claude-cli/" + DEFAULT_CLAUDE_CODE_VERSION)))
        .loginHint(CLAUDE_CODE_LOGIN_HINT).backend("claude-code").systemPrefix(DEFAULT_CLAUDE_CODE_SYSTEM_PROMPT).build();

    public static final AccessPolicy OPENAI_API = AccessPolicy.builder("openai")
        .supports(s().live(true).files(true).batches(true).images(true).speech(true).video(true).responsesApi(true).models(true).build())
        .authModes("bearer").envKeys("OPENAI_API_KEY").enterpriseVariants("azure-openai").build();

    public static final AccessPolicy OPENAI_CODEX = AccessPolicy.builder("openai-codex")
        .supports(s().models(true).build()).credentialPolicy(CredentialPolicy.OAUTH).authModes("chatgpt-oauth", "bearer-oauth")
        .headers(List.of(Map.entry("OpenAI-Beta", "responses=experimental"), Map.entry("originator", DEFAULT_CODEX_ORIGINATOR)))
        .loginHint(OPENAI_CODEX_LOGIN_HINT).backend("chatgpt-codex").backendOptions(Map.of("client_version", DEFAULT_CODEX_CLIENT_VERSION))
        .systemPrefix(DEFAULT_CODEX_INSTRUCTIONS).baseUrl(DEFAULT_CODEX_BASE_URL).build();

    public static final AccessPolicy OPENAI_CHAT_API = AccessPolicy.builder("openai-chat")
        .supports(s().models(true).build()).authModes("bearer").envKeys("OPENAI_API_KEY").build();

    public static final AccessPolicy XAI = AccessPolicy.builder("xai")
        .supports(s().models(true).images(true).video(true).build()).credentialPolicy(CredentialPolicy.OAUTH_UNLESS_EXPLICIT)
        .authModes("bearer", "xai-oauth").envKeys("XAI_API_KEY").loginHint(XAI_LOGIN_HINT).baseUrl(DEFAULT_XAI_BASE_URL).build();

    public static final AccessPolicy GEMINI_API = AccessPolicy.builder("gemini")
        .supports(s().live(true).files(true).batches(true).images(true).speech(true).video(true).models(true).caches(true).build())
        .authModes("query-api-key", "x-goog-api-key").envKeys("GEMINI_API_KEY", "GOOGLE_API_KEY").authScheme(X_API_KEY).build();

    public static final List<String> META_ENV_KEYS = List.of("META_API_KEY");
    public static final List<String> MOONSHOTAI_ENV_KEYS = List.of("MOONSHOTAI_API_KEY", "MOONSHOT_API_KEY");

    public static final AccessPolicy META = AccessPolicy.builder("meta")
        .supports(s().files(true).images(true).responsesApi(true).models(true).build()).authModes("bearer").envKeys("META_API_KEY")
        .baseUrl(OPENAI_RESPONSES_PRESET_BASE_URLS.get("meta")).build();

    public static final AccessPolicy GROQ = AccessPolicy.builder("groq").supports(s().models(true).build()).authModes("bearer").envKeys("GROQ_API_KEY")
        .baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("groq")).build();
    public static final AccessPolicy OPENROUTER = AccessPolicy.builder("openrouter").supports(s().models(true).build()).authModes("bearer").envKeys("OPENROUTER_API_KEY")
        .baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("openrouter")).build();
    public static final AccessPolicy DEEPSEEK = AccessPolicy.builder("deepseek").supports(s().models(true).build()).authModes("bearer").envKeys("DEEPSEEK_API_KEY")
        .baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("deepseek")).build();
    public static final AccessPolicy ZAI = AccessPolicy.builder("zai").supports(s().models(true).build()).authModes("bearer").envKeys("ZAI_API_KEY")
        .baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("zai")).build();
    public static final AccessPolicy MOONSHOTAI = AccessPolicy.builder("moonshotai").supports(s().models(true).build()).authModes("bearer")
        .envKeys("MOONSHOTAI_API_KEY", "MOONSHOT_API_KEY").baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("moonshotai")).build();
    public static final AccessPolicy MOONSHOTAI_RESPONSES = AccessPolicy.builder("moonshotai-responses").supports(s().responsesApi(true).models(true).build())
        .authModes("bearer").envKeys("MOONSHOTAI_API_KEY", "MOONSHOT_API_KEY").baseUrl(OPENAI_RESPONSES_PRESET_BASE_URLS.get("moonshotai")).build();
    public static final AccessPolicy META_CHAT = AccessPolicy.builder("meta-chat").supports(s().models(true).build()).authModes("bearer").envKeys("META_API_KEY")
        .baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("meta")).build();
    public static final AccessPolicy DEEPSEEK_ANTHROPIC = AccessPolicy.builder("deepseek-anthropic").supports(s().build()).authModes("x-api-key")
        .envKeys("DEEPSEEK_API_KEY").authScheme(X_API_KEY).baseUrl(ANTHROPIC_PRESET_BASE_URLS.get("deepseek")).build();
    public static final AccessPolicy META_ANTHROPIC = AccessPolicy.builder("meta-anthropic").supports(s().models(true).build()).authModes("bearer")
        .envKeys("META_API_KEY").authScheme(BEARER).baseUrl(ANTHROPIC_PRESET_BASE_URLS.get("meta")).build();
    public static final AccessPolicy MOONSHOTAI_ANTHROPIC = AccessPolicy.builder("moonshotai-anthropic").supports(s().build()).authModes("bearer")
        .envKeys("MOONSHOTAI_API_KEY", "MOONSHOT_API_KEY").authScheme(BEARER).baseUrl(ANTHROPIC_PRESET_BASE_URLS.get("moonshotai")).build();

    // ─── cloud hosts (AUTH-10 host policies) ─────────────────────────────

    public static final HostSetting AWS_REGION = new HostSetting("region", List.of("AWS_REGION", "AWS_DEFAULT_REGION"));
    public static final HostSetting AWS_WORKSPACE = new HostSetting("workspace", List.of("ANTHROPIC_AWS_WORKSPACE_ID"));
    public static final HostSetting GCP_PROJECT = new HostSetting("project", List.of("GOOGLE_CLOUD_PROJECT", "GCLOUD_PROJECT"));
    public static final HostSetting GCP_LOCATION = new HostSetting("location", List.of("GOOGLE_CLOUD_LOCATION"), "global");
    public static final HostSetting AZURE_OPENAI_RESOURCE = new HostSetting("resource", List.of("AZURE_OPENAI_RESOURCE"));
    public static final HostSetting AZURE_FOUNDRY_RESOURCE = new HostSetting("resource", List.of("ANTHROPIC_FOUNDRY_RESOURCE"));
    public static final HostSetting AZURE_AUTHORITY = new HostSetting("authority_host", List.of("AZURE_AUTHORITY_HOST"), "https://login.microsoftonline.com");
    public static final HostSetting AZURE_SCOPE = new HostSetting("scope", List.of(), "https://ai.azure.com/.default");

    public static final AccessPolicy AWS_ANTHROPIC = AccessPolicy.builder("aws-anthropic").supports(s().build()).credentialPolicy(CredentialPolicy.AWS_CHAIN)
        .authModes("sigv4", "x-api-key").envKeys("ANTHROPIC_AWS_API_KEY").authScheme(SIGV4, X_API_KEY).backend("aws-external-anthropic")
        .host(HostSpec.of("https://aws-external-anthropic.{region}.api.aws/v1", List.of(AWS_REGION, AWS_WORKSPACE))
            .withRequiredHeaders(List.of(Map.entry("anthropic-workspace-id", "workspace"))).withSigv4Service("aws-external-anthropic")).build();

    public static final AccessPolicy BEDROCK_ANTHROPIC = AccessPolicy.builder("bedrock-anthropic").supports(s().build()).credentialPolicy(CredentialPolicy.AWS_CHAIN)
        .authModes("sigv4", "x-api-key").envKeys("AWS_BEARER_TOKEN_BEDROCK").authScheme(SIGV4, X_API_KEY).backend("bedrock-mantle")
        .host(HostSpec.of("https://bedrock-mantle.{region}.api.aws/anthropic/v1", List.of(AWS_REGION)).withSigv4Service("bedrock-mantle")).build();

    public static final AccessPolicy BEDROCK_CHAT = AccessPolicy.builder("bedrock-chat").supports(s().build()).credentialPolicy(CredentialPolicy.AWS_CHAIN)
        .authModes("sigv4", "bearer").envKeys("AWS_BEARER_TOKEN_BEDROCK").authScheme(SIGV4, BEARER).backend("bedrock-runtime")
        .host(HostSpec.of("https://bedrock-runtime.{region}.amazonaws.com/openai/v1", List.of(AWS_REGION)).withSigv4Service("bedrock")).build();

    public static final AccessPolicy BEDROCK_MANTLE_CHAT = AccessPolicy.builder("bedrock-mantle-chat").supports(s().models(true).build()).credentialPolicy(CredentialPolicy.AWS_CHAIN)
        .authModes("sigv4", "bearer").envKeys("AWS_BEARER_TOKEN_BEDROCK").authScheme(SIGV4, BEARER).backend("bedrock-mantle")
        .host(HostSpec.of("https://bedrock-mantle.{region}.api.aws/v1", List.of(AWS_REGION)).withSigv4Service("bedrock-mantle")).build();

    public static final AccessPolicy AZURE = AccessPolicy.builder("azure")
        .supports(s().live(true).files(true).batches(true).speech(true).responsesApi(true).models(true).build()).credentialPolicy(CredentialPolicy.AZURE_CHAIN)
        .authModes("api-key", "entra-oauth").envKeys("AZURE_OPENAI_API_KEY").authScheme(API_KEY, BEARER).backend("azure-openai")
        .host(HostSpec.of("https://{resource}.openai.azure.com/openai/v1", List.of(AZURE_OPENAI_RESOURCE, AZURE_AUTHORITY, AZURE_SCOPE))).build();

    public static final AccessPolicy AZURE_CHAT = AccessPolicy.builder("azure-chat").supports(s().models(true).build()).credentialPolicy(CredentialPolicy.AZURE_CHAIN)
        .authModes("api-key", "entra-oauth").envKeys("AZURE_OPENAI_API_KEY").authScheme(API_KEY, BEARER).backend("azure-openai")
        .host(HostSpec.of("https://{resource}.openai.azure.com/openai/v1", List.of(AZURE_OPENAI_RESOURCE, AZURE_AUTHORITY, AZURE_SCOPE))).build();

    public static final AccessPolicy AZURE_ANTHROPIC = AccessPolicy.builder("azure-anthropic").supports(s().build()).credentialPolicy(CredentialPolicy.AZURE_CHAIN)
        .authModes("x-api-key", "entra-oauth").envKeys("ANTHROPIC_FOUNDRY_API_KEY").authScheme(X_API_KEY, BEARER).backend("azure-foundry")
        .host(HostSpec.of("https://{resource}.services.ai.azure.com/anthropic/v1", List.of(AZURE_FOUNDRY_RESOURCE, AZURE_AUTHORITY, AZURE_SCOPE))).build();

    private static final String VERTEX_BASE = "https://{location_host}/v1/projects/{project}/locations/{location}";

    public static final AccessPolicy VERTEX = AccessPolicy.builder("vertex").supports(s().build()).credentialPolicy(CredentialPolicy.GCP_CHAIN)
        .authModes("google-oauth").authScheme(BEARER).backend("vertex")
        .host(HostSpec.of(VERTEX_BASE + "/publishers/google", List.of(GCP_PROJECT, GCP_LOCATION))).build();

    public static final AccessPolicy VERTEX_EXPRESS = AccessPolicy.builder("vertex-express").supports(s().build()).credentialPolicy(CredentialPolicy.KEY)
        .authModes("query-api-key").envKeys("GOOGLE_API_KEY").authScheme(QUERY_KEY).backend("vertex-express")
        .host(HostSpec.of("https://aiplatform.googleapis.com/v1/publishers/google", List.of())).build();

    public static final AccessPolicy VERTEX_ANTHROPIC = AccessPolicy.builder("vertex-anthropic").supports(s().build()).credentialPolicy(CredentialPolicy.GCP_CHAIN)
        .authModes("google-oauth").authScheme(BEARER).backend("vertex")
        .host(HostSpec.of(VERTEX_BASE, List.of(GCP_PROJECT, GCP_LOCATION))
            .withPaths(Map.of("messages", "/publishers/anthropic/models/{model}:rawPredict", "messages/stream", "/publishers/anthropic/models/{model}:streamRawPredict"))
            .withModelIn("path").withAnthropicVersionIn("body:vertex-2023-10-16")).build();

    public static final List<AccessPolicy> CLOUD_HOST_POLICIES = List.of(AZURE, AZURE_CHAT, AZURE_ANTHROPIC, AWS_ANTHROPIC, BEDROCK_ANTHROPIC,
        BEDROCK_CHAT, BEDROCK_MANTLE_CHAT, VERTEX, VERTEX_EXPRESS, VERTEX_ANTHROPIC);

    public static final AccessPolicy OLLAMA = AccessPolicy.builder("ollama").supports(s().models(true).build()).authModes("bearer").baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("ollama")).build();
    public static final AccessPolicy VLLM = AccessPolicy.builder("vllm").supports(s().models(true).build()).authModes("bearer").baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("vllm")).build();
    public static final AccessPolicy SGLANG = AccessPolicy.builder("sglang").supports(s().models(true).build()).authModes("bearer").baseUrl(OPENAI_CHAT_PRESET_BASE_URLS.get("sglang")).build();

    // ─── scheme selection (AUTH-2, D1) ───────────────────────────────────

    private static List<AuthScheme> accepted(Credential c) {
        return switch (c) {
            case Credential.ApiKey k -> List.of(BEARER, X_API_KEY, API_KEY, QUERY_KEY);
            case Credential.BearerToken b -> List.of(BEARER, X_API_KEY);
            case Credential.AwsCredentials a -> List.of(SIGV4);
        };
    }

    /**
     * The scheme this credential kind travels under. ApiKey: the first scheme
     * in POLICY order that carries a key; BearerToken: bearer, else x-api-key
     * (the token's order); AwsCredentials: sigv4 only.
     */
    public static AuthScheme selectScheme(AccessPolicy policy, Credential credential) {
        List<AuthScheme> accepted = accepted(credential);
        if (credential instanceof Credential.BearerToken) {
            for (AuthScheme s : accepted) if (policy.authScheme().contains(s)) return s;
        } else {
            for (AuthScheme s : policy.authScheme()) if (accepted.contains(s)) return s;
        }
        throw new NotConfiguredError(policy.provider() + ": a " + credential.kind() + " credential cannot travel under "
            + String.join("/", policy.authScheme().stream().map(AuthScheme::wire).toList()) + "; it accepts "
            + String.join("/", accepted.stream().map(AuthScheme::wire).toList()), ErrorMeta.of(policy.provider()), policy.envKeys(), null);
    }

    /** Three base64url segments whose first decodes to a JSON object with {@code alg}. */
    static boolean looksLikeJwt(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 3) return false;
        for (String p : parts) if (p.isEmpty()) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
            var header = dev.lm15.json.Json.parse(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
            return header instanceof dev.lm15.json.JsonObject o && o.has("alg");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The (name, value) header carrying {@code credential} under this policy,
     * or null when the scheme is not a header (sigv4 signs, query-key is a
     * parameter). {@code apiKeyHeader} is the dialect's spelling of x-api-key.
     */
    public static Map.Entry<String, String> authHeader(AccessPolicy policy, Credential credential, String apiKeyHeader) {
        AuthScheme scheme = selectScheme(policy, credential);
        String value = switch (credential) {
            case Credential.ApiKey k -> k.value();
            case Credential.BearerToken b -> b.value();
            case Credential.AwsCredentials a -> null;
        };
        if ((scheme == API_KEY || scheme == X_API_KEY) && policy.authScheme().contains(BEARER) && value != null && looksLikeJwt(value)) {
            throw new NotConfiguredError(policy.provider() + ": the credential is a JWT (a bearer token), but a plain string travels as an"
                + " API key here (`" + (scheme == X_API_KEY ? "x-api-key" : "api-key") + "` header); wrap it: Credential.BearerToken(token)",
                ErrorMeta.of(policy.provider()), policy.envKeys(), "apiKey = () -> new Credential.BearerToken(provider.get())");
        }
        return switch (scheme) {
            case BEARER -> Map.entry("Authorization", "Bearer " + value);
            case X_API_KEY -> Map.entry(apiKeyHeader, value);
            case API_KEY -> Map.entry("api-key", value);
            default -> null;
        };
    }
}
