package dev.lm15.auth;

import dev.lm15.errors.LockTimeoutError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cross-process credential-file locking and atomic private writes
 * (spec/auth.md AUTH-4). Locks live in an lm15-owned directory
 * ({@code $LM15_LOCK_DIR}, else {@code $XDG_CACHE_HOME/lm15/locks}, else
 * {@code ~/.cache/lm15/locks}), never next to the guarded file: the Claude
 * Code and Codex files belong to other tools. The lock is advisory and
 * cooperative among lm15 processes; foreign writers do not take it, and the
 * double-checked re-read (AUTH-3) is the mitigation.
 */
public final class CredentialLock {
    private CredentialLock() {}

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final long POLL_MS = 50;

    private static volatile Path lockDirOverride;

    /** Point the lock directory somewhere explicit (an embedding app's own cache, a test's temp dir); null restores the AUTH-8 default. */
    public static void setLockDir(Path dir) { lockDirOverride = dir; }

    static Path lockDir(Map<String, String> env) {
        Path forced = lockDirOverride;
        if (forced != null) return forced;
        String override = env.get("LM15_LOCK_DIR");
        if (override != null && !override.isEmpty()) return expand(override, env);
        String cacheHome = env.get("XDG_CACHE_HOME");
        Path base = cacheHome != null && !cacheHome.isEmpty() ? expand(cacheHome, env) : home(env).resolve(".cache");
        return base.resolve("lm15").resolve("locks");
    }

    static Path home(Map<String, String> env) {
        String h = env.get("HOME");
        return Path.of(h != null && !h.isEmpty() ? h : System.getProperty("user.home"));
    }

    static Path expand(String text, Map<String, String> env) {
        if (text.equals("~")) return home(env);
        if (text.startsWith("~/")) return home(env).resolve(text.substring(2));
        return Path.of(text);
    }

    /** Deterministic lock-file path for a guarded credential file, keyed by its real absolute path. */
    public static Path lockPathFor(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        String canonical;
        try {
            canonical = absolute.toRealPath().toString();
        } catch (IOException e) {
            Path parent = absolute.getParent();
            try {
                canonical = parent == null ? absolute.toString() : parent.toRealPath().resolve(absolute.getFileName()).toString();
            } catch (IOException e2) {
                canonical = absolute.toString();
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format("%02x", digest[i] & 0xff));
            return lockDir(System.getenv()).resolve(hex + ".lock");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hold the exclusive advisory lock for {@code path} while running {@code body}; {@link LockTimeoutError} after the deadline. */
    public static <T> T withLock(Path path, Duration timeout, Supplier<T> body) {
        Path lockFile = lockPathFor(path);
        try {
            Files.createDirectories(lockFile.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                restrict(lockFile);
                long deadline = System.nanoTime() + timeout.toNanos();
                FileLock lock = tryLock(channel);
                while (lock == null) {
                    if (System.nanoTime() >= deadline) {
                        throw new LockTimeoutError("Could not lock credential file " + path + " within " + timeout.toSeconds() + "s (lock file: " + lockFile
                            + "). Another process may be refreshing the same credential; retry, or remove a stale lock only if you are certain no other process holds it.",
                            path.toString(), lockFile.toString());
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new LockTimeoutError("interrupted while waiting for the credential lock on " + path, path.toString(), lockFile.toString());
                    }
                    lock = tryLock(channel);
                }
                try {
                    return body.get();
                } finally {
                    lock.release();
                }
            }
        } catch (IOException e) {
            throw new LockTimeoutError("Could not open the credential lock file " + lockFile + ": " + e.getMessage(), path.toString(), lockFile.toString());
        }
    }

    public static void withLock(Path path, Runnable body) {
        withLock(path, DEFAULT_TIMEOUT, () -> { body.run(); return null; });
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null; // held elsewhere in this JVM: same as another process holding it
        }
    }

    static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // mode 0600 where the platform supports it (AUTH-4)
        }
    }

    /**
     * Atomically replace {@code path} with {@code data} as private (0600) JSON:
     * temp file created private in the same directory → write → fsync → move
     * over the target → fsync the directory. A reader observes the complete
     * old file or the complete new file, never a partial one.
     */
    public static void writePrivateJsonAtomic(Path path, JsonObject data) {
        Path parent = path.toAbsolutePath().getParent();
        try {
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "." + path.getFileName() + ".", ".tmp");
            restrict(temp);
            try {
                byte[] bytes = (Json.writePythonStyle(data) + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort
                }
                throw e;
            }
            restrict(path);
            try (FileChannel dir = FileChannel.open(parent, StandardOpenOption.READ)) {
                dir.force(true);
            } catch (IOException ignored) {
                // fsync of the directory is best-effort
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write credential file " + path + ": " + e.getMessage(), e);
        }
    }
}
