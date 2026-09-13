package dev.lm15.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** AUTH-9: the RFC 7636 Appendix B vector is a required test. */
class PkceTest {
    @Test
    void rfc7636AppendixB() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void generatedPairIsS256AndRedacted() {
        Pkce.Pair pair = Pkce.generate();
        assertEquals("S256", pair.method());
        assertEquals(86, pair.verifier().length());
        assertEquals(Pkce.challenge(pair.verifier()), pair.challenge());
        assertFalse(pair.toString().contains(pair.verifier()));
    }
}
