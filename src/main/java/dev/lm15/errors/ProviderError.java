package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The provider returned an error response (the code fallback, MAP-9 complete-path refusals). */
public class ProviderError extends LM15Error {
    public ProviderError(String message) { this(message, ErrorMeta.NONE); }
    public ProviderError(String message, ErrorMeta meta) { super(message, ErrorCode.PROVIDER, meta); }
    protected ProviderError(String message, ErrorCode code, ErrorMeta meta) { super(message, code, meta); }

    /** The displayed form carries provider / HTTP status / request id; {@link #message()} stays untouched. */
    @Override public String getMessage() {
        StringBuilder ctx = new StringBuilder();
        if (provider() != null) ctx.append(provider());
        if (status() != null) ctx.append(ctx.length() > 0 ? ", " : "").append("HTTP ").append(status());
        if (requestId() != null) ctx.append(ctx.length() > 0 ? ", " : "").append("request ").append(requestId());
        String base = message().isEmpty() ? code().wire() : message();
        if (ctx.length() == 0) return base;
        int cut = base.indexOf("\n\n");
        if (cut < 0) return base + " (" + ctx + ")";
        return base.substring(0, cut) + " (" + ctx + ")" + base.substring(cut);
    }
}
