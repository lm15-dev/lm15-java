package dev.lm15.auth;

/** What a stored-login loader resolves: the provider (re-read per request), the account id when the token carries one, and the source. */
public record LoadedCredential(CredentialProvider credential, String accountId, String source) {
    public static LoadedCredential explicit(CredentialProvider credential) { return new LoadedCredential(credential, null, "explicit"); }
}
