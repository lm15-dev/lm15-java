package dev.lm15.types;

import java.util.List;

/** The chosen token at one decoding step, with ranked alternatives. */
public record TokenLogprob(String token, double logprob, List<Integer> bytes, Integer tokenId, List<TopLogprob> top) {
    public TokenLogprob {
        Check.text(token, "token");
        if (!Double.isFinite(logprob)) throw ValidationException.type("logprob must be a float");
        if (bytes != null) {
            bytes = Check.list(bytes, "bytes");
            for (int b : bytes) if (b < 0) throw ValidationException.type("bytes must contain non-negative ints");
        }
        top = Check.list(top, "TokenLogprob.top");
    }

    public TokenLogprob(String token, double logprob) { this(token, logprob, null, null, List.of()); }
}
