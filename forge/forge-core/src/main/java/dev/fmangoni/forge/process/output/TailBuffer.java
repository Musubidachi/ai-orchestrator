package dev.fmangoni.forge.process.output;

import lombok.RequiredArgsConstructor;

/**
 * Retains only the last {@code limit} characters written to it.
 *
 * <p>Agent transcripts and Maven output can reach hundreds of megabytes. Forge only ever reads the
 * tail (the failure, the summary, the rate-limit notice), so holding the whole stream in memory
 * buys nothing and risks an OOM in a process meant to run unattended overnight.
 */
@RequiredArgsConstructor
public final class TailBuffer {

    private final int limit;

    private final StringBuilder buf = new StringBuilder();
    private boolean truncated;

    synchronized void append(String s) {
        buf.append(s);
        if (buf.length() > limit) {
            buf.delete(0, buf.length() - limit);
            truncated = true;
        }
    }

    public synchronized String value() {
        return truncated ? "...[earlier output truncated]...\n" + buf : buf.toString();
    }
}
