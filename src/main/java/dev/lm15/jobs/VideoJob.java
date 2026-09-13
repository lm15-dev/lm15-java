package dev.lm15.jobs;

import dev.lm15.ProviderLM;
import dev.lm15.types.VideoJobInfo;
import dev.lm15.types.VideoPart;
import dev.lm15.types.VideoStatus;

/** The video handle (api-family § Beyond chat); same shape as {@link BatchJob}. */
public final class VideoJob {
    private final ProviderLM lm;
    private VideoJobInfo info;

    public VideoJob(ProviderLM lm, VideoJobInfo info) {
        this.lm = lm;
        this.info = info;
    }

    public VideoJobInfo info() { return info; }
    public String id() { return info.id(); }
    public VideoStatus status() { return info.status(); }
    public boolean done() { return info.done(); }

    public VideoJob refresh() {
        info = lm.videoStatus(info.id());
        return this;
    }

    /** Poll until terminal: options.pollEveryMs between polls; a TimeoutException past options.timeoutMs. */
    public VideoJob wait(WaitOptions options) throws java.util.concurrent.TimeoutException {
        Jobs.await(this::done, this::refresh, options.pollEveryMs(), options.timeoutMs(), "video " + info.id());
        return this;
    }

    public VideoJob waitDone() throws java.util.concurrent.TimeoutException { return wait(WaitOptions.DEFAULT); }

    public VideoPart result() { return lm.videoResult(info.id()); }
}
