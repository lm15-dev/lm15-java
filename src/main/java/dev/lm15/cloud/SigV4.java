package dev.lm15.cloud;

import dev.lm15.auth.AccessPolicy;
import dev.lm15.auth.Credential;
import dev.lm15.errors.ErrorMeta;
import dev.lm15.errors.NotConfiguredError;
import dev.lm15.wire.TransportRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AWS Signature Version 4 (spec/auth.md AUTH-11; the AWS test suite in
 * auth/sigv4-vectors.json). {@link #signRequest} is the seam the emit path
 * calls; the signer itself lands with the cloud module.
 */
public final class SigV4 {
    private SigV4() {}

    /** The three stages of a signature plus the headers to send. */
    public record Signature(String canonicalRequest, String stringToSign, String authorization, List<Map.Entry<String, String>> headers) {}

    /** Sign one request; {@code headers} carry no authorization/x-api-key (they are replaced). */
    public static Signature sign(String method, String url, List<Map.Entry<String, String>> headers, byte[] payload,
                                 Credential.AwsCredentials credentials, String region, String service, Instant now) {
        return SigV4Signer.sign(method, url, headers, payload, credentials, region, service, now);
    }

    /** The headers to send under a sigv4 host: the signed set (the emit path's last step). */
    public static List<Map.Entry<String, String>> signRequest(AccessPolicy policy, Map<String, String> settings, TransportRequest req,
                                                              Credential.AwsCredentials credential, Instant now) {
        if (policy.host() == null || policy.host().sigv4Service() == null) {
            throw new NotConfiguredError(policy.provider() + ": AWS credentials need a sigv4 host", ErrorMeta.of(policy.provider()));
        }
        String region = settings.get("region");
        if (region == null || region.isEmpty()) {
            throw new NotConfiguredError(policy.provider() + ": sigv4 needs the region setting", ErrorMeta.of(policy.provider()));
        }
        List<Map.Entry<String, String>> headers = req.headers().stream()
            .filter(h -> !h.getKey().equalsIgnoreCase("authorization") && !h.getKey().equalsIgnoreCase("x-api-key")).toList();
        return sign(req.method(), req.fullUrl(), headers, req.bodyBytes(), credential, region, policy.host().sigv4Service(), now).headers();
    }
}
