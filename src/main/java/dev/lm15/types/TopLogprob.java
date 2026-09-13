package dev.lm15.types;

import java.util.List;

/** One scored alternative token at a decoding step. */
public record TopLogprob(String token, double logprob, List<Integer> bytes, Integer tokenId) {
    public TopLogprob {
        Check.text(token, "token");
        if (!Double.isFinite(logprob)) throw ValidationException.type("logprob must be a float");
        if (bytes != null) {
            bytes = Check.list(bytes, "bytes");
            for (int b : bytes) if (b < 0) throw ValidationException.type("bytes must contain non-negative ints");
        }
    }

    public TopLogprob(String token, double logprob) { this(token, logprob, null, null); }
}
