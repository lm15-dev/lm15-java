package dev.lm15.jobs;

import dev.lm15.ProviderLM;
import dev.lm15.types.BatchEntry;
import dev.lm15.types.BatchJobInfo;
import dev.lm15.types.BatchStatus;

import java.util.List;

/**
 * The batch handle (api-family § Beyond chat): reading a property never
 * contacts the provider; {@link #wait} is the only thing that waits and
 * returns at a terminal state, {@code failed} included; a deadline past is
 * a {@link java.util.concurrent.TimeoutException}.
 */
public final class BatchJob {
    private final ProviderLM lm;
    private BatchJobInfo info;

    public BatchJob(ProviderLM lm, BatchJobInfo info) {
        this.lm = lm;
        this.info = info;
    }

    public BatchJobInfo info() { return info; }
    public String id() { return info.id(); }
    public BatchStatus status() { return info.status(); }
    public boolean done() { return info.done(); }

    public BatchJob refresh() {
        info = lm.batchStatus(info.id());
        return this;
    }

    /** Poll until terminal: options.pollEveryMs between polls; a TimeoutException past options.timeoutMs. */
    public BatchJob wait(WaitOptions options) throws java.util.concurrent.TimeoutException {
        Jobs.await(this::done, this::refresh, options.pollEveryMs(), options.timeoutMs(), "batch " + info.id());
        return this;
    }

    public BatchJob waitDone() throws java.util.concurrent.TimeoutException { return wait(WaitOptions.DEFAULT); }

    public List<BatchEntry> results() { return lm.batchResults(info.id()); }

    public BatchJob cancel() {
        info = lm.batchCancel(info.id());
        return this;
    }
}
