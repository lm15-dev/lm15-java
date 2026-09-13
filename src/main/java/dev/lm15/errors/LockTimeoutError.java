package dev.lm15.errors;

import dev.lm15.types.ErrorCode;

/** The credential-file lock could not be taken in time (AUTH-4); local, transient, retryable. */
public class LockTimeoutError extends LM15Error {
    private final String path;
    private final String lockPath;

    public LockTimeoutError(String message, String path, String lockPath) {
        super(message, ErrorCode.LOCK_TIMEOUT, ErrorMeta.NONE);
        this.path = path == null ? "" : path;
        this.lockPath = lockPath == null ? "" : lockPath;
    }

    public String path() { return path; }
    public String lockPath() { return lockPath; }
}
