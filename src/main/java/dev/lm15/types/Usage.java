package dev.lm15.types;

/**
 * Token usage. Every counter is int-or-absent; absent means "not reported",
 * distinct from 0 (INV-029). {@code totalTokens} auto-computes as input +
 * output only when both are present and no explicit total was given.
 * Counters are provider-verbatim (spec/types.md § Usage).
 */
public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens, Integer cacheReadTokens,
                    Integer cacheWriteTokens, Integer reasoningTokens, Integer inputAudioTokens, Integer outputAudioTokens) {
    public static final Usage EMPTY = new Usage(null, null, null, null, null, null, null, null);

    public Usage {
        Check.nonNegative(inputTokens, "input_tokens");
        Check.nonNegative(outputTokens, "output_tokens");
        Check.nonNegative(totalTokens, "total_tokens");
        Check.nonNegative(cacheReadTokens, "cache_read_tokens");
        Check.nonNegative(cacheWriteTokens, "cache_write_tokens");
        Check.nonNegative(reasoningTokens, "reasoning_tokens");
        Check.nonNegative(inputAudioTokens, "input_audio_tokens");
        Check.nonNegative(outputAudioTokens, "output_audio_tokens");
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public Usage(Integer inputTokens, Integer outputTokens) { this(inputTokens, outputTokens, null, null, null, null, null, null); }

    public boolean isEmpty() { return equals(EMPTY); }

    /** Field-wise sum; absent on either side stays absent (LIVE-2, INV-029). */
    public Usage plus(Usage other) {
        return new Usage(add(inputTokens, other.inputTokens), add(outputTokens, other.outputTokens),
            add(totalTokens, other.totalTokens), add(cacheReadTokens, other.cacheReadTokens),
            add(cacheWriteTokens, other.cacheWriteTokens), add(reasoningTokens, other.reasoningTokens),
            add(inputAudioTokens, other.inputAudioTokens), add(outputAudioTokens, other.outputAudioTokens));
    }

    private static Integer add(Integer a, Integer b) {
        return (a == null || b == null) ? null : a + b;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Integer inputTokens, outputTokens, totalTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens, inputAudioTokens, outputAudioTokens;

        public Builder inputTokens(Integer v) { inputTokens = v; return this; }
        public Builder outputTokens(Integer v) { outputTokens = v; return this; }
        public Builder totalTokens(Integer v) { totalTokens = v; return this; }
        public Builder cacheReadTokens(Integer v) { cacheReadTokens = v; return this; }
        public Builder cacheWriteTokens(Integer v) { cacheWriteTokens = v; return this; }
        public Builder reasoningTokens(Integer v) { reasoningTokens = v; return this; }
        public Builder inputAudioTokens(Integer v) { inputAudioTokens = v; return this; }
        public Builder outputAudioTokens(Integer v) { outputAudioTokens = v; return this; }
        public Usage build() {
            return new Usage(inputTokens, outputTokens, totalTokens, cacheReadTokens, cacheWriteTokens, reasoningTokens, inputAudioTokens, outputAudioTokens);
        }
    }
}
