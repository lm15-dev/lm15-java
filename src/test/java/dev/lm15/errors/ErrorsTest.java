package dev.lm15.errors;

import dev.lm15.types.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ErrorsTest {
    @Test void httpMapping() {
        assertInstanceOf(AuthError.class, Errors.mapHttpError(401, "x", null));
        assertInstanceOf(AuthError.class, Errors.mapHttpError(403, "x", null));
        assertInstanceOf(BillingError.class, Errors.mapHttpError(402, "x", null));
        assertInstanceOf(TimeoutError.class, Errors.mapHttpError(408, "x", null));
        assertInstanceOf(TimeoutError.class, Errors.mapHttpError(504, "x", null));
        assertInstanceOf(RateLimitError.class, Errors.mapHttpError(429, "x", null));
        assertInstanceOf(InvalidRequestError.class, Errors.mapHttpError(422, "x", null));
        assertInstanceOf(ServerError.class, Errors.mapHttpError(503, "x", null));
        assertEquals(ProviderError.class, Errors.mapHttpError(418, "x", null).getClass());
        assertEquals(ErrorCode.RATE_LIMIT, Errors.mapHttpError(429, "x", null).code());
        assertTrue(Errors.mapHttpError(429, "x", null).isRetryable());
        assertFalse(Errors.mapHttpError(400, "x", null).isRetryable());
        assertEquals("x", Errors.mapHttpError(429, "x", ErrorMeta.of("openai")).message());
        assertTrue(Errors.mapHttpError(429, "x", ErrorMeta.of("openai")).getMessage().contains("openai, HTTP 429"));
    }

    @Test void codesAndClasses() {
        assertEquals(ErrorCode.CONTEXT_LENGTH, new ContextLengthError("x").code());
        assertInstanceOf(InvalidRequestError.class, new ContextLengthError("x"));
        assertInstanceOf(ConfigurationError.class, new UnknownModelError("x", "m"));
        assertInstanceOf(CapabilityError.class, new UnsupportedFeatureError("x"));
        assertEquals(ErrorCode.LOCK_TIMEOUT, new LockTimeoutError("x", "p", "l").code());
        assertTrue(new LockTimeoutError("x", "p", "l").isRetryable());
        assertEquals("StreamAssemblyError", new StreamAssemblyError("x", null).className());
        for (ErrorCode code : ErrorCode.values()) assertEquals(code, Errors.forCode(code, "m", ErrorMeta.NONE).code());
    }

    @Test void retryAfterAndRequestIdFromHeaders() {
        assertEquals(30.0, Errors.retryAfterSeconds("30"));
        assertNull(Errors.retryAfterSeconds("-1"));
        assertNull(Errors.retryAfterSeconds("soon"));
        assertNotNull(Errors.retryAfterSeconds("Wed, 21 Oct 2015 07:28:00 GMT"));
        RateLimitError e = new RateLimitError("x", ErrorMeta.of("openai"));
        Errors.attachMetadata(e, List.of(Map.entry("Retry-After", "12"), Map.entry("X-Request-Id", "req_1")));
        assertEquals(12.0, e.retryAfter());
        assertEquals("req_1", e.requestId());
        RateLimitError body = new RateLimitError("x", ErrorMeta.of("openai").withRetryAfter(5.0).withRequestId("body"));
        Errors.attachMetadata(body, List.of(Map.entry("retry-after", "12"), Map.entry("x-request-id", "hdr")));
        assertEquals(5.0, body.retryAfter());                                            // a body value is never replaced
        assertEquals("body", body.requestId());
    }
}
