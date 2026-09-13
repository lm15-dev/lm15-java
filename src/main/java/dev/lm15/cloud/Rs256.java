package dev.lm15.cloud;

import dev.lm15.errors.NotConfiguredError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * RSASSA-PKCS1-v1_5 / SHA-256 signing over {@code java.security} (spec/auth.md
 * AUTH-11 {@code jwt-rs256}): PEM {@code PRIVATE KEY} (PKCS#8) or
 * {@code RSA PRIVATE KEY} (PKCS#1, wrapped into PKCS#8 here), the
 * {@code CERTIFICATE} block for {@code x5t}, and the compact JWS
 * serialization lm15 pins — compact JSON with keys in the caller's order,
 * non-ASCII escaped as the reference's {@code json.dumps} does, base64url
 * without padding — so four ports emit identical bytes.
 *
 * <p>Encrypted PEM and PKCS#12 are not parsed: the error names
 * {@code openssl pkey} / {@code openssl pkcs12 -nodes}.
 */
public final class Rs256 {
    private Rs256() {}

    // rsaEncryption 1.2.840.113549.1.1.1
    private static final byte[] RSA_OID = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01};

    public static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    static byte[] pemBlock(String text, String label) {
        String head = "-----BEGIN " + label + "-----";
        String tail = "-----END " + label + "-----";
        int start = text.indexOf(head);
        if (start < 0) return null;
        int end = text.indexOf(tail, start);
        if (end < 0) throw new IllegalArgumentException("PEM block '" + label + "' has no END line");
        String body = text.substring(start + head.length(), end).replaceAll("\\s+", "");
        return Base64.getDecoder().decode(body);
    }

    private static byte[] derLength(int length) {
        if (length < 0x80) return new byte[] {(byte) length};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int count = 0;
        int v = length;
        while (v > 0) { count++; v >>= 8; }
        out.write(0x80 | count);
        for (int i = count - 1; i >= 0; i--) out.write((length >> (8 * i)) & 0xff);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        out.writeBytes(derLength(value.length));
        out.writeBytes(value);
        return out.toByteArray();
    }

    /** PKCS#1 RSAPrivateKey → PKCS#8 PrivateKeyInfo (version 0, rsaEncryption + NULL, OCTET STRING key). */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        ByteArrayOutputStream alg = new ByteArrayOutputStream();
        alg.writeBytes(tlv(0x06, RSA_OID));
        alg.writeBytes(new byte[] {0x05, 0x00});
        ByteArrayOutputStream seq = new ByteArrayOutputStream();
        seq.writeBytes(tlv(0x02, new byte[] {0x00}));
        seq.writeBytes(tlv(0x30, alg.toByteArray()));
        seq.writeBytes(tlv(0x04, pkcs1));
        return tlv(0x30, seq.toByteArray());
    }

    /** Parse an unencrypted RSA private key from PEM (PKCS#8 or PKCS#1). */
    public static PrivateKey loadPrivateKey(String pem) {
        if (pem.contains("ENCRYPTED PRIVATE KEY") || pem.contains("Proc-Type: 4,ENCRYPTED")) {
            throw new NotConfiguredError("encrypted private keys are not supported; decrypt it first: openssl pkey -in key.pem -out key-plain.pem",
                null, java.util.List.of(), "openssl pkey -in key.pem -out key-plain.pem");
        }
        byte[] der = pemBlock(pem, "PRIVATE KEY");
        if (der == null) {
            byte[] pkcs1 = pemBlock(pem, "RSA PRIVATE KEY");
            if (pkcs1 != null) der = pkcs1ToPkcs8(pkcs1);
        }
        if (der == null) {
            if (pem.contains("BEGIN EC PRIVATE KEY")) throw new NotConfiguredError("EC private keys are not supported (RS256 needs an RSA key)");
            throw new NotConfiguredError("no PEM private key found; PKCS#12 (.pfx/.p12) is not parsed — convert with: openssl pkcs12 -in cert.pfx -nodes -out cert.pem",
                null, java.util.List.of(), "openssl pkcs12 -in cert.pfx -nodes -out cert.pem");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new NotConfiguredError("PKCS#8: not an RSA key (only rsaEncryption is supported): " + e.getMessage());
        }
    }

    /** The DER bytes of the first CERTIFICATE block (for {@code x5t} thumbprints). */
    public static byte[] certificateDer(String pem) {
        byte[] der = pemBlock(pem, "CERTIFICATE");
        if (der == null) throw new NotConfiguredError("no PEM CERTIFICATE block found");
        return der;
    }

    /** RSASSA-PKCS1-v1_5-SIGN with SHA-256 (RFC 8017 §8.2.1). */
    public static byte[] signPkcs1v15Sha256(PrivateKey key, byte[] message) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RS256 signing failed: " + e.getMessage(), e);
        }
    }

    /** Python {@code json.dumps(obj, separators=(",", ":"))}: compact, key order kept, non-ASCII escaped. */
    static String compactAsciiJson(JsonObject value) {
        String compact = Json.write(value);
        StringBuilder sb = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (c < 0x80) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    /** Compact JWS: header and payload in the given key order, unpadded base64url, RS256 signature. */
    public static String jwtEncode(JsonObject header, JsonObject payload, PrivateKey key) {
        String head = b64url(compactAsciiJson(header).getBytes(StandardCharsets.UTF_8));
        String body = b64url(compactAsciiJson(payload).getBytes(StandardCharsets.UTF_8));
        String signingInput = head + "." + body;
        return signingInput + "." + b64url(signPkcs1v15Sha256(key, signingInput.getBytes(StandardCharsets.US_ASCII)));
    }
}
