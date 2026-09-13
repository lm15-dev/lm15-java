package dev.lm15.auth;

import java.nio.file.Path;

/**
 * Registers the stored-login loaders and the offline probe with
 * {@link CredentialStores} (AUTH-1 oauth policies, AUTH-8). The policy says
 * THAT a login is used; these loaders say HOW. Loaded from
 * {@code CredentialStores}' static initializer, so every path that binds a
 * provider (the adapter builder, the chain, the doctor, the vet) sees them.
 */
public final class AuthModule {
    private AuthModule() {}

    static {
        CredentialStores.registerLoader("claude-code", AuthModule::loadClaudeCode);
        CredentialStores.registerLoader("openai-codex", AuthModule::loadOpenAICodex);
        CredentialStores.registerLoader("xai", AuthModule::loadXai);
        CredentialStores.registerProbe("xai", () -> XaiStore.usable((Path) null));
    }

    /** Force class initialization (the registrations above). */
    public static void init() {}

    private static LoadedCredential loadClaudeCode(Path path) {
        // Validate now (typed, re-login-guided errors), then re-resolve per request so a long-lived client always sends a fresh token.
        ClaudeCodeStore.accessToken(path);
        return new LoadedCredential(() -> new Credential.BearerToken(ClaudeCodeStore.accessToken(path)), null, "stored");
    }

    private static LoadedCredential loadOpenAICodex(Path path) {
        LocalOAuthCredential initial = CodexStore.credential(path, true);
        String accountId = initial.accountId() != null ? initial.accountId() : CodexStore.extractChatgptAccountId(initial.accessToken());
        return new LoadedCredential(() -> new Credential.BearerToken(CodexStore.accessToken(path)), accountId, "stored");
    }

    private static LoadedCredential loadXai(Path path) {
        XaiStore.accessToken(path);
        return new LoadedCredential(() -> new Credential.BearerToken(XaiStore.accessToken(path)), null, "stored");
    }
}
