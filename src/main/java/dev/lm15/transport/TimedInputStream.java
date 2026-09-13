package dev.lm15.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/** JDK request timeouts end at streaming headers; bound subsequent blocking reads too. */
final class TimedInputStream extends InputStream {
    private final InputStream source;
    private final long timeoutNanos;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();

    TimedInputStream(InputStream source, Duration timeout) {
        this.source = source;
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("read timeout must be positive");
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (closed.get()) throw new IOException("response stream is closed");
        // A late completion after cancellation must never modify the caller's buffer.
        byte[] chunk = new byte[Math.min(length, 8192)];
        Future<Integer> pending;
        try {
            pending = reader.submit(() -> source.read(chunk));
        } catch (RejectedExecutionException error) {
            throw new IOException("response stream is closed", error);
        }
        try {
            int count = pending.get(timeoutNanos, TimeUnit.NANOSECONDS);
            if (count > 0) System.arraycopy(chunk, 0, bytes, offset, count);
            return count;
        } catch (TimeoutException error) {
            pending.cancel(true);
            IOException failure = new SocketTimeoutException("response body read timed out");
            abort(failure);
            throw failure;
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            IOException failure = new InterruptedIOException("response body read interrupted");
            abort(failure);
            throw failure;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IOException("response body read failed", cause);
        }
    }

    private void abort(IOException failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        reader.shutdownNow();
        source.close();
    }
}
