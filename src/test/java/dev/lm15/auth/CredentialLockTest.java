package dev.lm15.auth;

import dev.lm15.errors.LockTimeoutError;
import dev.lm15.json.Json;
import dev.lm15.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AUTH-4: the lock lives in an lm15-owned directory, contention is a LockTimeoutError, writes are atomic and private. */
class CredentialLockTest {
    @TempDir Path tmp;

    @BeforeEach void isolateLockDir() { CredentialLock.setLockDir(tmp.resolve("locks")); }
    @AfterEach void restoreLockDir() { CredentialLock.setLockDir(null); }

    @Test
    void contentionIsATypedRetryableError() throws Exception {
        Path guarded = tmp.resolve("foreign").resolve("credentials.json");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> CredentialLock.withLock(guarded, Duration.ofSeconds(5), () -> {
            held.countDown();
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return null;
        }));
        holder.start();
        held.await();
        try {
            LockTimeoutError e = assertThrows(LockTimeoutError.class, () -> CredentialLock.withLock(guarded, Duration.ofMillis(200), () -> "never"));
            assertEquals(guarded.toString(), e.path());
            assertTrue(e.lockPath().startsWith(tmp.resolve("locks").toString()), "lock file lives in the lm15 lock dir, not next to the guarded file");
            assertTrue(e.isRetryable());
            assertFalse(Files.exists(guarded.getParent()), "nothing is written into the foreign directory");
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals("after", CredentialLock.withLock(guarded, Duration.ofSeconds(1), () -> "after"));
    }

    @Test
    void atomicPrivateWrite() throws Exception {
        Path target = tmp.resolve("store").resolve("credentials.json");
        JsonObject data = Json.obj("xai", Json.obj("type", "oauth", "access", "token-not-a-secret", "expires", 1L));
        CredentialLock.writePrivateJsonAtomic(target, data);
        assertEquals(data, Json.parse(Files.readString(target)));
        try (var listing = Files.list(target.getParent())) {
            assertEquals(1, listing.count(), "no temp file is left behind");
        }
        try {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
        } catch (UnsupportedOperationException ignored) {
            // mode 0600 where the platform supports it
        }
    }
}
