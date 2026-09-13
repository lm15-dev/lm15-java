package dev.lm15.cloud;

import dev.lm15.auth.Credential;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The AWS test-suite pair and clock (auth/sigv4-vectors.json): get-vanilla-query-order-key-case and the session-token vector. */
class SigV4Test {
    private static final Credential.AwsCredentials KEYS = new Credential.AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
    private static final Instant NOW = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    void getVanillaQueryOrderKeyCase() {
        SigV4.Signature s = SigV4.sign("GET", "https://example.amazonaws.com/?Param2=value2&Param1=value1", List.of(), new byte[0], KEYS, "us-east-1", "service", NOW);
        assertEquals("GET\n/\nParam1=value1&Param2=value2\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n"
            + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", s.canonicalRequest());
        assertEquals("AWS4-HMAC-SHA256\n20150830T123600Z\n20150830/us-east-1/service/aws4_request\n"
            + "816cd5b414d056048ba4f7c5386d6e0533120fb1fcfa93762cf0fc39e2cf19e0", s.stringToSign());
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, "
            + "Signature=b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500", s.authorization());
    }

    @Test
    void sessionTokenIsSignedAndSent() {
        String token = "6e86291e8372ff2a2260956d9b8aae1d763fbf315fa00fa31553b73ebf194267";
        SigV4.Signature s = SigV4.sign("GET", "https://example.amazonaws.com/", List.of(), new byte[0],
            new Credential.AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", token, null), "us-east-1", "service", NOW);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, "
            + "Signature=07ec1639c89043aa0e3e2de82b96708f198cceab042d4a97044c66dd9f74e7f8", s.authorization());
        assertEquals("067b36aa60031588cea4a4cde1f21215227a047690c72247f1d70b32fbbfad2b", s.stringToSign().substring(s.stringToSign().lastIndexOf('\n') + 1));
        assertEquals(token, s.headers().stream().filter(h -> h.getKey().equals("x-amz-security-token")).map(Map.Entry::getValue).findFirst().orElseThrow());
    }

    @Test
    void headerValuesAreTrimmedAndFolded() {
        SigV4.Signature s = SigV4.sign("GET", "https://example.amazonaws.com/", List.of(Map.entry("My-Header1", "value1\n  value2\n     value3")),
            new byte[0], KEYS, "us-east-1", "service", NOW);
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;my-header1;x-amz-date, "
            + "Signature=cfd34249e4b1c8d6b91ef74165d41a32e5fab3306300901bb65a51a73575eefd", s.authorization());
    }
}
